package rabtest;

import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.auth.http.HttpTransportFactory;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * E2E test for Regional Access Boundary (RAB) Retryable Errors behavior.
 * Demonstrates that 503 errors trigger automatic retries before eventual success.
 */
public class RabRetryableErrorE2ETest {
  
  private static final String BUCKET_NAME = "trust_boundary_test_bucket";
  private static final URI BUCKET_URL = URI.create("https://storage.googleapis.com/storage/v1/b/" + BUCKET_NAME);
  private static final String JSON_PATH = "";

  public static void main(String[] args) throws Exception {
    System.out.println("=== Starting Retryable Error (503) RAB Test ===");

    final AtomicInteger lookupAttemptCount = new AtomicInteger(0);

    HttpTransportFactory mockTransportFactory = new HttpTransportFactory() {
      @Override
      public HttpTransport create() {
        return new MockHttpTransport() {
          @Override
          public LowLevelHttpRequest buildRequest(String method, String url) throws IOException {
            if (url != null && url.contains("allowedLocations")) {
               int count = lookupAttemptCount.incrementAndGet();
               
               if (count <= 2) {
                 System.out.println("\n[Mock] Intercepted RAB lookup attempt #" + count + " to " + url + ". Simulating 503 Service Unavailable...");
                 return new MockLowLevelHttpRequest() {
                   @Override
                   public LowLevelHttpResponse execute() throws IOException {
                     MockLowLevelHttpResponse response = new MockLowLevelHttpResponse();
                     response.setStatusCode(503);
                     response.setContent("Service Unavailable");
                     return response;
                   }
                 };
               } else {
                 System.out.println("\n[Mock] Intercepted RAB lookup attempt #" + count + " to " + url + ". Allowing request to proceed successfully...");
                 return new MockLowLevelHttpRequest() {
                   @Override
                   public LowLevelHttpResponse execute() throws IOException {
                     MockLowLevelHttpResponse response = new MockLowLevelHttpResponse();
                     response.setStatusCode(200);
                     String jsonResponse = "{\"locations\":[\"us-central1\"], \"encodedLocations\":\"mocked-encoded-locations-after-retries\"}";
                     response.setContent(jsonResponse);
                     return response;
                   }
                 };
               }
            }
            return super.buildRequest(method, url);
          }
        };
      }
    };

    System.out.println("Loading credentials from: " + JSON_PATH);
    ServiceAccountCredentials credentials = ((ServiceAccountCredentials) GoogleCredentials
        .fromStream(new FileInputStream(JSON_PATH)))
        .toBuilder()
        .setUseJwtAccessWithScope(true) // Avoids network token fetch
        .setHttpTransportFactory(mockTransportFactory)
        .build();
    
    System.out.println("Client Type: " + credentials.getClass().getSimpleName());

    System.out.println("\n--- First Call to getRequestMetadata (Cold Start) ---");
    // This call will return immediately without the RAB header (Fail Open).
    // However, in the background, it will trigger the lookup.
    // The library will automatically retry the 503 errors.
    Map<String, List<String>> headers = credentials.getRequestMetadata(BUCKET_URL);
    System.out.println("x-allowed-locations (First attempt): " + getHeader(headers, "NOT PRESENT (Fail Open)"));

    System.out.println("\nSleeping for 5 seconds to let the background retries and eventual success complete...");
    // It takes a moment because the library uses exponential backoff for retries.
    Thread.sleep(5000);

    // Access the internal RegionalAccessBoundaryManager via reflection to check cache
    Field managerField = GoogleCredentials.class.getDeclaredField("regionalAccessBoundaryManager");
    managerField.setAccessible(true);
    Object rabManager = managerField.get(credentials);

    if (rabManager == null) {
      System.err.println("Failed to access RegionalAccessBoundaryManager");
      return;
    }

    Field cachedRabField = rabManager.getClass().getDeclaredField("cachedRAB");
    cachedRabField.setAccessible(true);
    AtomicReference<?> cachedRabRef = (AtomicReference<?>) cachedRabField.get(rabManager);
    Object cachedRab = cachedRabRef.get();

    if (cachedRab != null) {
      java.lang.reflect.Method getEncodedLocationsMethod = cachedRab.getClass().getMethod("getEncodedLocations");
      getEncodedLocationsMethod.setAccessible(true);
      String encodedLocations = (String) getEncodedLocationsMethod.invoke(cachedRab);
      if ("mocked-encoded-locations-after-retries".equals(encodedLocations)) {
        System.out.println("\nSUCCESS: RAB was successfully fetched and cached after retries: " + encodedLocations);
      } else {
        System.out.println("\nFAILURE: RAB was cached but has unexpected value: " + encodedLocations);
      }
    } else {
      System.out.println("\nFAILURE: RAB was not cached. Retries might have failed or not occurred.");
    }

    int finalCount = lookupAttemptCount.get();
    if (finalCount >= 3) {
      System.out.println("\nSUCCESS: The lookup endpoint was called " + finalCount + " times, confirming that retry logic for 5xx errors works.");
    } else {
      System.out.println("\nFAILURE: The lookup endpoint was only called " + finalCount + " times. Expected at least 3 attempts.");
    }

    System.out.println("\n--- Final Call to getRequestMetadata (Verify Header Attachment) ---");
    // It should now attach the cached RAB.
    headers = credentials.getRequestMetadata(BUCKET_URL);
    String finalHeader = getHeader(headers, null);
    System.out.println("x-allowed-locations (Final attempt): " + (finalHeader != null ? finalHeader : "NOT PRESENT"));

    if ("mocked-encoded-locations-after-retries".equals(finalHeader)) {
      System.out.println("SUCCESS: x-allowed-locations header is now correctly attached to the request.");
    } else {
      System.out.println("FAILURE: x-allowed-locations header is missing or incorrect.");
    }
  }

  private static String getHeader(Map<String, List<String>> headers, String defaultMsg) {
    List<String> val = headers.get("x-allowed-locations");
    return (val != null && !val.isEmpty()) ? val.get(0) : defaultMsg;
  }
}
