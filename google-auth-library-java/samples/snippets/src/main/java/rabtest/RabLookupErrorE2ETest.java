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
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.URI;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * E2E test for Regional Access Boundary (RAB) Lookup Error (Fail Open) behavior.
 * Demonstrates that a 400 error from the endpoint fails open and enters a cooldown.
 */
public class RabLookupErrorE2ETest {
  
  private static final String BUCKET_NAME = "trust_boundary_test_bucket";
  private static final URI BUCKET_URL = URI.create("https://storage.googleapis.com/storage/v1/b/" + BUCKET_NAME);
  private static final String JSON_PATH = "/Users/pjiyer/Documents/google-auth-adc/lookup-endpoint-service-account/lookup-service-account.json";

  public static void main(String[] args) throws Exception {
    System.out.println("=== Starting Lookup Error (Fail Open) RAB Test ===");

    final AtomicInteger lookupCount = new AtomicInteger(0);

    HttpTransportFactory mockTransportFactory = new HttpTransportFactory() {
      @Override
      public HttpTransport create() {
        return new MockHttpTransport() {
          @Override
          public LowLevelHttpRequest buildRequest(String method, String url) throws IOException {
            if (url != null && url.contains("allowedLocations")) {
               int count = lookupCount.incrementAndGet();
               System.out.println("\n[Mock] Intercepted RAB lookup request #" + count + ". Simulating 400 Bad Request...");
               return new MockLowLevelHttpRequest() {
                 @Override
                 public LowLevelHttpResponse execute() throws IOException {
                   MockLowLevelHttpResponse response = new MockLowLevelHttpResponse();
                   response.setStatusCode(400);
                   response.setContent("Bad Request");
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
    Map<String, List<String>> headers = credentials.getRequestMetadata(BUCKET_URL);
    System.out.println("x-allowed-locations (First attempt): " + getHeader(headers, "NOT PRESENT (Fail Open)"));

    System.out.println("\nSleeping for 5 seconds to let initial background RAB lookup fail and enter cooldown...");
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
      System.out.println("FAILURE: Cooldown time was not set. Background lookup might not have finished or failed properly.");
      return;
    }

    System.out.println("\nRAB Cooldown Time after initial failure: " + new Date(expiryTime).toString());

    System.out.println("\n--- Manipulating Cooldown Time ---");
    // We manipulate the cooldown time to be in the past to bypass the waiting period.
    long newExpiryTime = System.currentTimeMillis() - 1000; // 1 second ago
    
    // Create new CooldownState
    Constructor<?> cooldownConstructor = cooldownStateClass.getDeclaredConstructor(long.class, long.class);
    cooldownConstructor.setAccessible(true);
    
    Field durationMillisField = cooldownStateClass.getDeclaredField("durationMillis");
    durationMillisField.setAccessible(true);
    long durationMillis = durationMillisField.getLong(cooldownState);
    
    Object manipulatedCooldown = cooldownConstructor.newInstance(newExpiryTime, durationMillis);
    ((AtomicReference<Object>) cooldownStateRef).set(manipulatedCooldown);
    
    System.out.println("Manually set RAB Cooldown Time to the past to bypass cooldown.");

    System.out.println("\n--- Second Call to getRequestMetadata (Triggers RAB Refresh after Cooldown Skip) ---");
    headers = credentials.getRequestMetadata(BUCKET_URL);
    System.out.println("x-allowed-locations (Second attempt): " + getHeader(headers, "NOT PRESENT (Fail Open)"));

    System.out.println("\nSleeping for 5 seconds to let the second background RAB lookup fail...");
    Thread.sleep(5000);

    Object newCooldownState = cooldownStateRef.get();
    long newCooldownExpiry = expiryTimeField.getLong(newCooldownState);
    long newDurationMillis = durationMillisField.getLong(newCooldownState);
    
    System.out.println("\nRAB Cooldown Time after second failure: " + new Date(newCooldownExpiry).toString());
    long cooldownDiffMinutes = newDurationMillis / 60000;
    System.out.println("New cooldown duration is roughly " + cooldownDiffMinutes + " minutes.");

    int finalCount = lookupCount.get();
    if (finalCount >= 2) {
      System.out.println("\nSUCCESS: Lookup endpoint was called " + finalCount + " times, confirming refresh successfully triggered after cooldown skip.");
    } else {
      System.out.println("\nFAILURE: Lookup endpoint was only called " + finalCount + " times.");
    }
  }

  private static String getHeader(Map<String, List<String>> headers, String defaultMsg) {
    List<String> val = headers.get("x-allowed-locations");
    return (val != null && !val.isEmpty()) ? val.get(0) : defaultMsg;
  }
}
