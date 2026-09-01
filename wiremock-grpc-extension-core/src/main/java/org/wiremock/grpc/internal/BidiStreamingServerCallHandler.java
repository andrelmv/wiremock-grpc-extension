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

import com.github.tomakehurst.wiremock.common.Notifier;
import com.github.tomakehurst.wiremock.common.Pair;
import com.github.tomakehurst.wiremock.http.StubRequestHandler;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import io.grpc.Status;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.wiremock.grpc.dsl.WireMockGrpc;

public class BidiStreamingServerCallHandler extends BaseCallHandler
    implements ServerCalls.BidiStreamingMethod<DynamicMessage, DynamicMessage> {

  private final Notifier notifier;

  public BidiStreamingServerCallHandler(
      StubRequestHandler stubRequestHandler,
      Descriptors.ServiceDescriptor serviceDescriptor,
      Descriptors.MethodDescriptor methodDescriptor,
      JsonMessageConverter jsonMessageConverter,
      Supplier<ServerAddress> serverAddressSupplier,
      Notifier notifier) {
    super(
        stubRequestHandler,
        serviceDescriptor,
        methodDescriptor,
        jsonMessageConverter,
        serverAddressSupplier);
    this.notifier = notifier;
  }

  @Override
  public StreamObserver<DynamicMessage> invoke(StreamObserver<DynamicMessage> responseObserver) {
    final ServerAddress serverAddress = serverAddressSupplier.get();
    final AtomicBoolean closed = new AtomicBoolean(false);

    // Buffer every inbound message; once the client half-closes, the whole stream is matched
    // as one unit against a single stub whose response body is a JSON array. Kept local to this
    // call so concurrent streams don't share state.
    final List<DynamicMessage> receivedRequests = new ArrayList<>();

    return new StreamObserver<>() {
      @Override
      public void onNext(DynamicMessage request) {
        if (closed.get()) {
          return;
        }
        receivedRequests.add(request);
      }

      @Override
      public void onError(Throwable t) {
        notifier.info("gRPC client closed the stream with an error: " + t.getMessage());
        if (closed.compareAndSet(false, true)) {
          responseObserver.onError(t);
        }
      }

      @Override
      public void onCompleted() {
        if (closed.compareAndSet(false, true)) {
          matchBatch(receivedRequests, responseObserver, serverAddress);
        }
      }
    };
  }

  private void matchBatch(
      List<DynamicMessage> receivedRequests,
      StreamObserver<DynamicMessage> responseObserver,
      ServerAddress serverAddress) {
    GrpcMessageMatcher.matchStream(
        stubRequestHandler,
        serviceDescriptor,
        methodDescriptor,
        jsonMessageConverter,
        notifier,
        serverAddress,
        receivedRequests,
        new GrpcMessageMatcher.StreamResultHandler() {
          @Override
          public void onMatched(List<DynamicMessage> responses) {
            responses.forEach(responseObserver::onNext);
            responseObserver.onCompleted();
          }

          @Override
          public void onGrpcError(WireMockGrpc.Status status, String reason) {
            responseObserver.onError(
                Status.fromCodeValue(status.getValue())
                    .withDescription(reason)
                    .asRuntimeException());
          }

          @Override
          public void onNotFound() {
            final Pair<Status, String> notFoundStatusMapping =
                GrpcStatusUtils.errorHttpToGrpcStatusMappings.get(404);
            responseObserver.onError(
                notFoundStatusMapping
                    .a
                    .withDescription(notFoundStatusMapping.b)
                    .asRuntimeException());
          }
        });
  }
}
