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
  private final List<DynamicMessage> receivedRequests = new ArrayList<>();

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
        
        // 1. Armazenar a requisição em vez de responder imediatamente
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
          // 2. Processar todas as requisições armazenadas e responder em lote
          processBatchResponse(responseObserver);
        }
      }
    };
  }

  private void processBatchResponse(StreamObserver<DynamicMessage> responseObserver) {
    final List<String> errors = new ArrayList<>();
    boolean hasError = false;

    for (DynamicMessage request : receivedRequests) {
      // Para cada requisição, tentamos fazer o match e enviar a resposta
      try {
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
                // Envia a resposta imediatamente após o match, mas dentro do fluxo de onCompleted
                responseObserver.onNext(response);
              }

              @Override
              public void onGrpcError(WireMockGrpc.Status status, String reason) {
                // Se houver erro, registra o erro, mas não encerra o stream
                errors.add("gRPC Error: " + status.name() + " - " + reason);
                hasError = true;
              }

              @Override
              public void onNotFound() {
                // Se não encontrar, registra o erro, mas não encerra o stream
                final Pair<Status, String> notFoundStatusMapping =
                    GrpcStatusUtils.errorHttpToGrpcStatusMappings.get(404);
                errors.add("Not Found (404): " + notFoundStatusMapping.b);
                hasError = true;
              }
            });
      } catch (Exception e) {
        // Captura qualquer exceção durante o processamento do batch
        errors.add("Processing Exception: " + e.getMessage());
        hasError = true;
      }
    }
    
    if (hasError) {
      // Se houver erros, consolida e envia um erro geral
      String errorMessage = "Failed to process one or more requests in the batch. Errors: " + String.join("; ", errors);
      final Status status = Status.INTERNAL;
      responseObserver.onError(status.withDescription(errorMessage).asRuntimeException());
    } else {
      // Se tudo correu bem, sinaliza o fim do stream
      responseObserver.onCompleted();
    }
  }
}
