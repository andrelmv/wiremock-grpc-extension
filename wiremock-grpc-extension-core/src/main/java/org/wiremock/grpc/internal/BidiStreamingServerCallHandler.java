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

    return new StreamObserver<>() {
      @Override
      public void onNext(DynamicMessage request) {
        if (closed.get()) {
          return;
        }

        BaseCallHandler.CONTEXT.set(
            new GrpcContext(serviceDescriptor, methodDescriptor, jsonMessageConverter, request));

        GrpcMessageMatcher.match(
            stubRequestHandler,
            serviceDescriptor,
            methodDescriptor,
            jsonMessageConverter,
            notifier,
            serverAddress,
            request,
            new GrpcMessageMatcher.ResultHandler() {
              @Override
              public void onMatched(DynamicMessage response) {
                if (!closed.get()) {
                  responseObserver.onNext(response);
                }
              }

              @Override
              public void onGrpcError(WireMockGrpc.Status status, String reason) {
                if (closed.compareAndSet(false, true)) {
                  responseObserver.onError(
                      Status.fromCodeValue(status.getValue())
                          .withDescription(reason)
                          .asRuntimeException());
                }
              }

              @Override
              public void onNotFound() {
                if (closed.compareAndSet(false, true)) {
                  final Pair<Status, String> notFoundStatusMapping =
                      GrpcStatusUtils.errorHttpToGrpcStatusMappings.get(404);
                  final Status grpcStatus = notFoundStatusMapping.a;

                  responseObserver.onError(
                      grpcStatus.withDescription(notFoundStatusMapping.b).asRuntimeException());
                }
              }
            });
      }

      @Override
      public void onError(Throwable t) {
        notifier.info("gRPC client closed the stream with an error: " + t.getMessage());
      }

      @Override
      public void onCompleted() {
        if (closed.compareAndSet(false, true)) {
          responseObserver.onCompleted();
        }
      }
    };
  }
}
