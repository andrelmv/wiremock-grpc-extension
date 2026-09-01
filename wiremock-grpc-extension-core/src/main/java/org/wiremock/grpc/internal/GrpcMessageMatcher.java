/*
 * Copyright (C) 2026 Thomas Akehurst
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wiremock.grpc.internal;

import static org.wiremock.grpc.dsl.GrpcResponseDefinitionBuilder.GRPC_STATUS_NAME;
import static org.wiremock.grpc.dsl.GrpcResponseDefinitionBuilder.GRPC_STATUS_REASON;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.common.LocalNotifier;
import com.github.tomakehurst.wiremock.common.Notifier;
import com.github.tomakehurst.wiremock.common.Pair;
import com.github.tomakehurst.wiremock.common.Strings;
import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.http.StubRequestHandler;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import io.grpc.Status;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.wiremock.grpc.dsl.WireMockGrpc;

/**
 * Matches inbound gRPC messages against the registered stub mappings by dispatching them through
 * the ordinary WireMock HTTP stub pipeline. The result-handler callbacks fire synchronously, before
 * the {@code match*} method returns.
 *
 * <p>{@link #match} handles a single message (unary / client-streaming). {@link #matchStream}
 * handles a whole bidi stream as one unit: every buffered message is serialised into a single JSON
 * array request body, and the matched stub's response body is read back as a JSON array of response
 * messages.
 */
public class GrpcMessageMatcher {

  private GrpcMessageMatcher() {}

  public interface ResultHandler {
    void onMatched(DynamicMessage response);

    void onGrpcError(WireMockGrpc.Status status, String reason);

    void onNotFound();
  }

  public interface StreamResultHandler {
    void onMatched(List<DynamicMessage> responses);

    void onGrpcError(WireMockGrpc.Status status, String reason);

    void onNotFound();
  }

  public static void match(
      StubRequestHandler stubRequestHandler,
      Descriptors.ServiceDescriptor serviceDescriptor,
      Descriptors.MethodDescriptor methodDescriptor,
      JsonMessageConverter jsonMessageConverter,
      Notifier notifier,
      ServerAddress serverAddress,
      DynamicMessage request,
      ResultHandler resultHandler) {
    dispatch(
        stubRequestHandler,
        serviceDescriptor,
        methodDescriptor,
        notifier,
        serverAddress,
        jsonMessageConverter.toJson(request),
        new RawResultHandler() {
          @Override
          public void onMatchedBody(String responseBody) {
            resultHandler.onMatched(
                toMessage(jsonMessageConverter, methodDescriptor, responseBody));
          }

          @Override
          public void onGrpcError(WireMockGrpc.Status status, String reason) {
            resultHandler.onGrpcError(status, reason);
          }

          @Override
          public void onNotFound() {
            resultHandler.onNotFound();
          }
        });
  }

  public static void matchStream(
      StubRequestHandler stubRequestHandler,
      Descriptors.ServiceDescriptor serviceDescriptor,
      Descriptors.MethodDescriptor methodDescriptor,
      JsonMessageConverter jsonMessageConverter,
      Notifier notifier,
      ServerAddress serverAddress,
      List<DynamicMessage> requests,
      StreamResultHandler resultHandler) {
    dispatch(
        stubRequestHandler,
        serviceDescriptor,
        methodDescriptor,
        notifier,
        serverAddress,
        toJsonArray(jsonMessageConverter, requests),
        new RawResultHandler() {
          @Override
          public void onMatchedBody(String responseBody) {
            resultHandler.onMatched(
                parseResponseArray(jsonMessageConverter, methodDescriptor, responseBody));
          }

          @Override
          public void onGrpcError(WireMockGrpc.Status status, String reason) {
            resultHandler.onGrpcError(status, reason);
          }

          @Override
          public void onNotFound() {
            resultHandler.onNotFound();
          }
        });
  }

  private interface RawResultHandler {
    void onMatchedBody(String responseBody);

    void onGrpcError(WireMockGrpc.Status status, String reason);

    void onNotFound();
  }

  private static void dispatch(
      StubRequestHandler stubRequestHandler,
      Descriptors.ServiceDescriptor serviceDescriptor,
      Descriptors.MethodDescriptor methodDescriptor,
      Notifier notifier,
      ServerAddress serverAddress,
      String requestBodyJson,
      RawResultHandler resultHandler) {
    final GrpcRequest wireMockRequest =
        new GrpcRequest(
            serverAddress.scheme(),
            serverAddress.hostname(),
            serverAddress.port(),
            serviceDescriptor.getFullName(),
            methodDescriptor.getName(),
            requestBodyJson);

    LocalNotifier.set(notifier);
    stubRequestHandler.handle(
        wireMockRequest,
        (req, resp, attributes) -> {
          final HttpHeader statusHeader = resp.getHeaders().getHeader(GRPC_STATUS_NAME);

          if (!statusHeader.isPresent() && resp.getStatus() == 404) {
            resultHandler.onNotFound();
            return;
          }

          if (!statusHeader.isPresent()
              && GrpcStatusUtils.errorHttpToGrpcStatusMappings.containsKey(resp.getStatus())) {
            final Pair<Status, String> statusMapping =
                GrpcStatusUtils.errorHttpToGrpcStatusMappings.get(resp.getStatus());
            final Status grpcStatus = statusMapping.a;
            resultHandler.onGrpcError(
                WireMockGrpc.Status.valueOf(grpcStatus.getCode().name()), statusMapping.b);
            return;
          }

          if (statusHeader.isPresent()
              && !statusHeader.firstValue().equals(Status.Code.OK.name())) {
            final HttpHeader statusReasonHeader = resp.getHeaders().getHeader(GRPC_STATUS_REASON);
            final String reason =
                statusReasonHeader.isPresent() ? statusReasonHeader.firstValue() : "";

            resultHandler.onGrpcError(
                WireMockGrpc.Status.valueOf(statusHeader.firstValue()), reason);
            return;
          }

          resultHandler.onMatchedBody(resp.getBodyAsString());
        },
        ServeEvent.of(wireMockRequest));
  }

  private static String toJsonArray(
      JsonMessageConverter jsonMessageConverter, List<DynamicMessage> requests) {
    return requests.stream()
        .map(jsonMessageConverter::toJson)
        .collect(Collectors.joining(",", "[", "]"));
  }

  private static List<DynamicMessage> parseResponseArray(
      JsonMessageConverter jsonMessageConverter,
      Descriptors.MethodDescriptor methodDescriptor,
      String responseBody) {
    final List<DynamicMessage> responses = new ArrayList<>();
    if (Strings.isNullOrEmpty(responseBody)) {
      return responses;
    }

    final JsonNode root = Json.node(responseBody);
    if (root.isArray()) {
      for (JsonNode element : root) {
        responses.add(toMessage(jsonMessageConverter, methodDescriptor, element.toString()));
      }
    } else {
      responses.add(toMessage(jsonMessageConverter, methodDescriptor, responseBody));
    }
    return responses;
  }

  private static DynamicMessage toMessage(
      JsonMessageConverter jsonMessageConverter,
      Descriptors.MethodDescriptor methodDescriptor,
      String json) {
    return jsonMessageConverter.toMessage(
        json, DynamicMessage.newBuilder(methodDescriptor.getOutputType()));
  }
}
