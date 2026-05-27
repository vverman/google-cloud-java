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
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * E2E test for Regional Access Boundary (RAB) Malformed Response behavior.
 * Demonstrates that a 200 OK response missing the encodedLocations field
 * is correctly identified as an error and triggers the cooldown state.
 */
public class RabMalformedResponseE2ETest {
  
  private static final String BUCKET_NAME = "trust_boundary_test_bucket";
  private static final URI BUCKET_URL = URI.create("https://storage.googleapis.com/storage/v1/b/" + BUCKET_NAME);
  private static final String JSON_PATH = "/Users/pjiyer/Documents/google-auth-adc/lookup-endpoint-service-account/lookup-service-account.json";

  public static void main(String[] args) throws Exception {
    System.out.println("=== Starting Malformed Response RAB Test ===");

    final AtomicInteger lookupCount = new AtomicInteger(0);

    HttpTransportFactory mockTransportFactory = new HttpTransportFactory() {
      @Override
      public HttpTransport create() {
        return new MockHttpTransport() {
          @Override
          public LowLevelHttpRequest buildRequest(String method, String url) throws IOException {
            if (url != null && url.contains("allowedLocations")) {
               int count = lookupCount.incrementAndGet();
               System.out.println("\n[Mock] Intercepted RAB lookup request #" + count + ". Simulating a malformed response (missing encodedLocations)...");
               return new MockLowLevelHttpRequest() {
                 @Override
                 public LowLevelHttpResponse execute() throws IOException {
                   MockLowLevelHttpResponse response = new MockLowLevelHttpResponse();
                   response.setStatusCode(200);
                   // Return JSON missing "encodedLocations"
                   response.setContent("{\"locations\":[\"us-central1\"]}");
                   return response;
                 }
               };
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
    // First call should trigger background lookup which will fail due to the malformed response.
    // The header should not be present (Fail Open).
    Map<String, List<String>> headers = credentials.getRequestMetadata(BUCKET_URL);
    System.out.println("x-allowed-locations (First attempt): " + getHeader(headers, "NOT PRESENT (Fail Open)"));

    System.out.println("\nSleeping for 5 seconds to let initial background RAB lookup fail on malformed response and enter cooldown...");
    Thread.sleep(5000);

    // Access the internal RegionalAccessBoundaryManager via reflection
    Field managerField = GoogleCredentials.class.getDeclaredField("regionalAccessBoundaryManager");
    managerField.setAccessible(true);
    Object rabManager = managerField.get(credentials);

    if (rabManager == null) {
      System.err.println("Failed to access RegionalAccessBoundaryManager");
      return;
    }

    // Access the cooldownState field
    Field cooldownStateField = rabManager.getClass().getDeclaredField("cooldownState");
    cooldownStateField.setAccessible(true);
    AtomicReference<?> cooldownStateRef = (AtomicReference<?>) cooldownStateField.get(rabManager);
    Object cooldownState = cooldownStateRef.get();

    Class<?> cooldownStateClass = cooldownState.getClass();
    Field expiryTimeField = cooldownStateClass.getDeclaredField("expiryTime");
    expiryTimeField.setAccessible(true);
    long expiryTime = expiryTimeField.getLong(cooldownState);

    if (expiryTime == 0) {
      System.out.println("\nFAILURE: Cooldown time was not set. Background lookup might not have finished or failed properly.");
      return;
    }

    System.out.println("\nRAB Cooldown Time after malformed response: " + new Date(expiryTime).toString());
    System.out.println("SUCCESS: Verified that a malformed response correctly triggers the cooldown state.");

    int finalCount = lookupCount.get();
    if (finalCount >= 1) {
      System.out.println("\nSUCCESS: Lookup endpoint was called " + finalCount + " time(s).");
    } else {
      System.out.println("\nFAILURE: Lookup endpoint was not called.");
    }
  }

  private static String getHeader(Map<String, List<String>> headers, String defaultMsg) {
    List<String> val = headers.get("x-allowed-locations");
    return (val != null && !val.isEmpty()) ? val.get(0) : defaultMsg;
  }
}
