/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.showcase.v1beta1.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.common.collect.ImmutableList;
import com.google.showcase.v1beta1.EchoClient;
import com.google.showcase.v1beta1.EchoRequest;
import com.google.showcase.v1beta1.EchoSettings;
import io.grpc.ClientInterceptor;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ForwardingClientCall;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import java.io.FileInputStream;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class ITRegionalAccessBoundary {

  private static EchoClient grpcClient;
  private static OutgoingHeadersInterceptor grpcInterceptor;
  private static GoogleCredentials credentials;

  private static class OutgoingHeadersInterceptor implements ClientInterceptor {
    private final List<Metadata> capturedHeaders = new java.util.ArrayList<>();

    public void clear() {
      capturedHeaders.clear();
    }

    public List<Metadata> getCapturedHeaders() {
      return capturedHeaders;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
      return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
        @Override
        public void start(Listener<RespT> responseListener, Metadata headers) {
          capturedHeaders.add(headers);
          super.start(responseListener, headers);
        }
      };
    }
  }

  @BeforeAll
  static void setUp() throws Exception {
    // 1. Load the real service account credentials.
    String credentialPath = "/Users/pjiyer/Documents/google-auth-adc/lookup-endpoint-service-account/lookup-service-account.json";
    try (FileInputStream serviceAccountStream = new FileInputStream(credentialPath)) {
      credentials = ServiceAccountCredentials.fromStream(serviceAccountStream);
    }

    // 2. Initialize the gRPC capture interceptor and Showcase client.
    grpcInterceptor = new OutgoingHeadersInterceptor();
    
    EchoSettings grpcEchoSettings = EchoSettings.newBuilder()
        .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
        .setTransportChannelProvider(
            EchoSettings.defaultGrpcTransportProviderBuilder()
                .setChannelConfigurator(ManagedChannelBuilder::usePlaintext)
                .setInterceptorProvider(() -> ImmutableList.<ClientInterceptor>of(grpcInterceptor))
                .build())
        .setEndpoint("localhost:7469")
        .build();

    grpcClient = EchoClient.create(grpcEchoSettings);
  }

  @AfterAll
  static void tearDown() {
    if (grpcClient != null) {
      grpcClient.close();
    }
  }

  @Test
  public void testRegionalAccessBoundaryHeaderPropagation() throws Exception {
    // 1. First call to warm up the async fetch of regional access boundaries
    try {
      grpcClient.echo(EchoRequest.newBuilder().setContent("warmup").build());
    } catch (Exception e) {
      // Ignore connection/RPC errors because the local showcase server might not be running.
      // The client interceptor will still capture the outgoing headers!
    }

    // 2. Wait for the async background refresh of regional access boundaries to complete.
    System.out.println("Waiting for regional access boundary manager to fetch the location...");
    int retries = 0;
    ServiceAccountCredentials sac = (ServiceAccountCredentials) credentials;
    while (sac.getRegionalAccessBoundary() == null && retries < 50) {
      Thread.sleep(200);
      retries++;
    }

    assertNotNull(sac.getRegionalAccessBoundary(), "RAB lookup failed or did not complete");
    System.out.println("Cached RAB value: " + sac.getRegionalAccessBoundary().getEncodedLocations());

    // 3. Clear captured headers from the first warmup call
    grpcInterceptor.clear();

    // 4. Second call - this one will carry the cached allowed locations header propagated via GAX gRPC transport!
    try {
      grpcClient.echo(EchoRequest.newBuilder().setContent("test").build());
    } catch (Exception e) {
      // Again, ignore RPC errors.
    }

    // 5. Verify that the captured metadata contains the "x-allowed-locations" header!
    List<Metadata> capturedHeaders = grpcInterceptor.getCapturedHeaders();
    assertTrue(!capturedHeaders.isEmpty(), "No headers were captured by the gRPC interceptor!");

    boolean headerFound = false;
    for (Metadata headers : capturedHeaders) {
      for (String key : headers.keys()) {
        if (key.equalsIgnoreCase("x-allowed-locations")) {
          headerFound = true;
          String value = headers.get(Metadata.Key.of("x-allowed-locations", Metadata.ASCII_STRING_MARSHALLER));
          System.out.println("Successfully intercepted 'x-allowed-locations' header via gRPC: " + value);
          assertEquals("0x80000000000", value);
        }
      }
    }
    assertTrue(headerFound, "Header 'x-allowed-locations' was not found in outgoing gRPC request metadata!");
  }
}
