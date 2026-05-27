/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.storage.it;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.storage.Bucket;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.common.collect.ImmutableList;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientStreamTracer;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * End-to-end integration test confirming the correct propagation of the Regional Access Boundary
 * (RAB) header 'x-allowed-locations' in outbound gRPC requests using the Storage library.
 */
public class ITRegionalAccesBoundaryGrpcGetBucketDetails {

  private static final Metadata.Key<String> X_ALLOWED_LOCATIONS =
      Metadata.Key.of("x-allowed-locations", Metadata.ASCII_STRING_MARSHALLER);

  @Test
  public void testRegionalAccessBoundaryGetBucketDetails() throws Exception {
    String credentialPath = "/Users/pjiyer/Documents/google-auth-adc/cicpclientproj-service-account/pjiyer-sa.json";
    String bucketName = "byoid-test";

    // 1. Load credentials with explicit scopes.
    // CRITICAL FIX: Scopes must be explicitly set. Otherwise, gRPC-java automatically converts
    // the ServiceAccountCredentials to a Self-Signed JWT, bypassing OAuth2 and RAB header injection.
    GoogleCredentials credentials;
    try (FileInputStream serviceAccountStream = new FileInputStream(credentialPath)) {
      credentials = ServiceAccountCredentials.fromStream(serviceAccountStream)
          .createScoped(Collections.singletonList("https://www.googleapis.com/auth/cloud-platform"));
    }

    // 2. Setup the custom gRPC interceptor to capture outbound metadata headers.
    GrpcHeaderCaptureInterceptor headerInterceptor = new GrpcHeaderCaptureInterceptor();

    // 3. Initialize GCS Storage client using gRPC transport with our interceptor.
    Storage storage = StorageOptions.grpc()
        .setCredentials(credentials)
        .setGrpcInterceptorProvider(() -> ImmutableList.of(headerInterceptor))
        .build()
        .getService();

    // 4. First GCS call (Warm-up): Triggers the background asynchronous RAB lookup task.
    try {
      storage.get(bucketName);
    } catch (Exception e) {
      // Ignore RPC warmup failures
    }

    // 5. Wait 5 seconds for the background allowedLocations lookup call to complete and warm cache.
    Thread.sleep(10000);
    headerInterceptor.clear(); // Clear metadata captured from the warmup call

    // 6. Second GCS call: Should successfully carry the cached 'x-allowed-locations' header.
    Bucket bucket = storage.get(bucketName);
    assertNotNull("Bucket details must be retrieved successfully", bucket);
    System.out.printf("Successfully retrieved Bucket '%s' in '%s'%n", bucket.getName(), bucket.getLocation());

    // 7. Verify that the 'x-allowed-locations' header exists in the outbound request metadata.
    List<Metadata> capturedHeaders = headerInterceptor.getHeadersList();
    boolean foundRabHeader = false;
    for (Metadata headers : capturedHeaders) {
      String allowedLocations = headers.get(X_ALLOWED_LOCATIONS);
      if (allowedLocations != null) {
        foundRabHeader = true;
        System.out.println(">>> Verified 'x-allowed-locations' header in outbound gRPC request: " + allowedLocations);
      }
    }

    assertTrue("Should have found the Regional Access Boundary header in outbound gRPC metadata", foundRabHeader);
  }

  /**
   * Custom ClientInterceptor that captures gRPC request stream metadata.
   */
  private static final class GrpcHeaderCaptureInterceptor implements ClientInterceptor {
    private final List<Metadata> headersList = Collections.synchronizedList(new ArrayList<>());

    public List<Metadata> getHeadersList() {
      synchronized (headersList) {
        return new ArrayList<>(headersList);
      }
    }

    public void clear() {
      headersList.clear();
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
      CallOptions withStreamTracerFactory = callOptions.withStreamTracerFactory(new ClientStreamTracer.Factory() {
        @Override
        public ClientStreamTracer newClientStreamTracer(ClientStreamTracer.StreamInfo info, Metadata headers) {
          return new ClientStreamTracer() {
            @Override
            public void streamCreated(Attributes transportAttrs, Metadata headers) {
              headersList.add(headers);
            }
          };
        }
      });
      return next.newCall(method, withStreamTracerFactory);
    }
  }
}
