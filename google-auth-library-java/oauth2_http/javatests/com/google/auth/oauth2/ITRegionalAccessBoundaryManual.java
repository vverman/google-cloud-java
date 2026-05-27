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

package com.google.auth.oauth2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileInputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class ITRegionalAccessBoundaryManual {

  @Test
  public void testRegionalAccessBoundaryIntegration() throws Exception {
    // 1. Point this to the service account json file.
    String credentialPath = "/Users/pjiyer/Documents/google-auth-adc/lookup-endpoint-service-account/lookup-service-account.json";
    System.out.println("Loading credentials from: " + credentialPath);

    // 2. Load the credentials.
    GoogleCredentials credentials;
    try (FileInputStream serviceAccountStream = new FileInputStream(credentialPath)) {
      credentials = ServiceAccountCredentials.fromStream(serviceAccountStream);
    }

    // 3. Print basic info.
    System.out.println("Client Class: " + credentials.getClass().getName());
    System.out.println("Universe Domain: " + credentials.getUniverseDomain());
    
    if (credentials instanceof RegionalAccessBoundaryProvider) {
      System.out.println("Credentials implement RegionalAccessBoundaryProvider: Yes");
      String url = ((RegionalAccessBoundaryProvider) credentials).getRegionalAccessBoundaryUrl();
      System.out.println("RAB URL: " + url);
    } else {
      System.out.println("Credentials implement RegionalAccessBoundaryProvider: No");
    }

    // 4. First call to getRequestMetadata.
    URI targetUri = URI.create("https://storage.googleapis.com/storage/v1/b/trust_boundary_test_bucket");
    System.out.println("\n--- First Call to getRequestMetadata ---");
    Map<String, List<String>> headers1 = credentials.getRequestMetadata(targetUri);
    
    String val1 = getHeaderValue(headers1, "x-allowed-locations");
    System.out.println("x-allowed-locations (First attempt): " + 
        (val1 != null ? val1 : "NOT PRESENT (Expected for cold start/async refresh in progress)"));

    // 5. Sleep for 5 seconds to let background RAB lookup finish.
    System.out.println("\nSleeping for 5 seconds to let background RAB lookup finish...");
    Thread.sleep(5000);

    // 6. Second call to getRequestMetadata.
    System.out.println("--- Second Call to getRequestMetadata ---");
    Map<String, List<String>> headers2 = credentials.getRequestMetadata(targetUri);
    String val2 = getHeaderValue(headers2, "x-allowed-locations");
    System.out.println("x-allowed-locations (Second attempt): " + 
        (val2 != null ? val2 : "STILL NOT PRESENT (Lookup might have failed or was disabled)"));

    assertNotNull(val2, "RAB header 'x-allowed-locations' should be present on the second attempt");
    System.out.println("\nSuccess! RAB header is present: " + val2);

    // Print all headers
    System.out.println("\nFull Headers on Second Attempt:");
    for (Map.Entry<String, List<String>> entry : headers2.entrySet()) {
      System.out.println("  " + entry.getKey() + ": " + entry.getValue());
    }
  }

  private String getHeaderValue(Map<String, List<String>> headers, String key) {
    if (headers == null) {
      return null;
    }
    for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
      if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
        List<String> values = entry.getValue();
        if (values != null && !values.isEmpty()) {
          return values.get(0);
        }
      }
    }
    return null;
  }
}
