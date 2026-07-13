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

import com.github.tomakehurst.wiremock.common.LocalNotifier;
import com.github.tomakehurst.wiremock.common.Notifier;
import com.github.tomakehurst.wiremock.common.Pair;
import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.http.StubRequestHandler;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import io.grpc.Status;
import org.wiremock.grpc.dsl.WireMockGrpc;

/**
 * Matches a single inbound gRPC message against the registered stub mappings. The {@link
 * ResultHandler} callback passed to {@link #match} fires synchronously, before this method returns
 * — callers rely on that to know the match outcome before processing the next message.
 */
public class GrpcMessageMatcher {

  private GrpcMessageMatcher() {}

  public interface ResultHandler {
    void onMatched(DynamicMessage response);

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
    final GrpcRequest wireMockRequest =
        new GrpcRequest(
            serverAddress.scheme(),
            serverAddress.hostname(),
            serverAddress.port(),
            serviceDescriptor.getFullName(),
            methodDescriptor.getName(),
            jsonMessageConverter.toJson(request));

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

          final DynamicMessage.Builder messageBuilder =
              DynamicMessage.newBuilder(methodDescriptor.getOutputType());
          final DynamicMessage response =
              jsonMessageConverter.toMessage(resp.getBodyAsString(), messageBuilder);
          resultHandler.onMatched(response);
        },
        ServeEvent.of(wireMockRequest));
  }
}
