package rabtest;

import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.http.javanet.NetHttpTransport;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * E2E test for Regional Access Boundary (RAB) Cooldown Recovery behavior.
 * Demonstrates that if a lookup fails and enters a cooldown, bypassing the cooldown
 * allows the next request to succeed and recover the RAB cache.
 */
public class RabCooldownRecoveryE2ETest {
  
  private static final String BUCKET_NAME = "trust_boundary_test_bucket";
  private static final URI BUCKET_URL = URI.create("https://storage.googleapis.com/storage/v1/b/" + BUCKET_NAME);
  private static final String JSON_PATH = "/Users/pjiyer/Documents/google-auth-adc/cicpclientproj-service-account/pjiyer-sa.json";

  public static void main(String[] args) throws Exception {
    System.out.println("=== Starting Cooldown Recovery RAB Test ===");

    final AtomicInteger lookupCount = new AtomicInteger(0);
    final AtomicBoolean mockShouldFail = new AtomicBoolean(true);

    HttpTransportFactory mockTransportFactory = new HttpTransportFactory() {
      @Override
      public HttpTransport create() {
        return new MockHttpTransport() {
          @Override
          public LowLevelHttpRequest buildRequest(String method, String url) throws IOException {
            if (url != null && url.contains("allowedLocations")) {
               int count = lookupCount.incrementAndGet();
               if (mockShouldFail.get()) {
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
               } else {
                 System.out.println("\n[Mock] Intercepted RAB lookup request #" + count + ". Allowing request to proceed successfully...");
                 return new MockLowLevelHttpRequest() {
                   @Override
                   public LowLevelHttpResponse execute() throws IOException {
                     MockLowLevelHttpResponse response = new MockLowLevelHttpResponse();
                     response.setStatusCode(200);
                     String jsonResponse = "{\"locations\":[\"us-central1\"], \"encodedLocations\":\"mocked-encoded-locations-after-recovery\"}";
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
    // First call should "Fail Open" because the background lookup will fail.
    // The header should not be present.
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
    long cooldownTime = expiryTimeField.getLong(cooldownState);

    if (cooldownTime == 0) {
      System.out.println("\nFAILURE: Cooldown time was not set. Background lookup might not have finished or failed properly.");
      return;
    }

    System.out.println("\nRAB Cooldown Time after initial failure: " + new Date(cooldownTime).toString());

    System.out.println("\n--- Manipulating Cooldown Time & Disabling Mock Failure ---");
    // We manipulate the cooldown time to be in the past to bypass the waiting period.
    long newCooldownTime = System.currentTimeMillis() - 1000; // 1 second ago
    
    // Create new CooldownState
    Constructor<?> cooldownConstructor = cooldownStateClass.getDeclaredConstructor(long.class, long.class);
    cooldownConstructor.setAccessible(true);
    
    Field durationMillisField = cooldownStateClass.getDeclaredField("durationMillis");
    durationMillisField.setAccessible(true);
    long durationMillis = durationMillisField.getLong(cooldownState);
    
    Object manipulatedCooldown = cooldownConstructor.newInstance(newCooldownTime, durationMillis);
    ((AtomicReference<Object>) cooldownStateRef).set(manipulatedCooldown);
    
    System.out.println("Manually set RAB Cooldown Time to the past to bypass cooldown.");
    mockShouldFail.set(false); // Allow the next lookup to succeed
    System.out.println("Disabled mock failure. The next lookup will hit the actual endpoint.");

    System.out.println("\n--- Second Call to getRequestMetadata (Triggers Successful RAB Refresh) ---");
    // This call triggers another background lookup because we bypassed the cooldown.
    // Because we disabled the mock failure, it should succeed.
    headers = credentials.getRequestMetadata(BUCKET_URL);
    System.out.println("x-allowed-locations (Second attempt): " + getHeader(headers, "NOT PRESENT (Still Fail Open during background fetch)"));

    System.out.println("\nSleeping for 5 seconds to let the successful background RAB lookup finish...");
    Thread.sleep(5000);

    Object finalCooldownState = cooldownStateRef.get();
    long finalCooldownTime = expiryTimeField.getLong(finalCooldownState);
    System.out.println("\nRAB Cooldown Time after success: " + (finalCooldownTime == 0 ? "0 (Reset)" : new Date(finalCooldownTime).toString()));

    Field cachedRabField = rabManager.getClass().getDeclaredField("cachedRAB");
    cachedRabField.setAccessible(true);
    AtomicReference<?> cachedRabRef = (AtomicReference<?>) cachedRabField.get(rabManager);
    Object currentRab = cachedRabRef.get();

    if (currentRab != null) {
      java.lang.reflect.Method getEncodedLocationsMethod = currentRab.getClass().getMethod("getEncodedLocations");
      getEncodedLocationsMethod.setAccessible(true);
      String encodedLocations = (String) getEncodedLocationsMethod.invoke(currentRab);
      System.out.println("\nSUCCESS: RAB Value successfully fetched and cached: " + encodedLocations);
    } else {
      System.out.println("\nFAILURE: RAB Value was not successfully fetched and cached.");
    }

    System.out.println("\n--- Third Call to getRequestMetadata (Verifies Header Attachment) ---");
    // Now that the cache is populated, this call should successfully attach the header.
    headers = credentials.getRequestMetadata(BUCKET_URL);
    String finalXAllowedLocations = getHeader(headers, null);
    System.out.println("x-allowed-locations (Third attempt): " + (finalXAllowedLocations != null ? finalXAllowedLocations : "NOT PRESENT"));

    if (finalXAllowedLocations != null) {
      System.out.println("SUCCESS: x-allowed-locations header is now attached to the request.");
    } else {
      System.out.println("FAILURE: x-allowed-locations header should be attached to the request.");
    }
  }

  private static String getHeader(Map<String, List<String>> headers, String defaultMsg) {
    List<String> val = headers.get("x-allowed-locations");
    return (val != null && !val.isEmpty()) ? val.get(0) : defaultMsg;
  }
}
