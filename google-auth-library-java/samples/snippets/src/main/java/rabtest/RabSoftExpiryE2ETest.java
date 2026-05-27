package rabtest;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import java.io.FileInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.URI;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * E2E test for Regional Access Boundary (RAB) Soft Expiry behavior.
 * Demonstrates proactive background refresh when the cached RAB is near its expiration.
 */
public class RabSoftExpiryE2ETest {
  
  private static final String BUCKET_NAME = "trust_boundary_test_bucket";
  private static final URI BUCKET_URL = URI.create("https://storage.googleapis.com/storage/v1/b/" + BUCKET_NAME);
  private static final String JSON_PATH = "/Users/pjiyer/Documents/google-auth-adc/cicpclientproj-service-account/pjiyer-sa.json";

  public static void main(String[] args) throws Exception {
    System.out.println("=== Starting Soft Expiry RAB Test ===");

    System.out.println("Loading credentials from: " + JSON_PATH);
    GoogleCredentials credentials = GoogleCredentials.fromStream(new FileInputStream(JSON_PATH))
        .createScoped("https://www.googleapis.com/auth/cloud-platform");
    
    System.out.println("Client Type: " + credentials.getClass().getSimpleName());

    System.out.println("\n--- First Call to getRequestMetadata (Cold Start) ---");
    Map<String, List<String>> headers = credentials.getRequestMetadata(BUCKET_URL);
    System.out.println("x-allowed-locations (First attempt): " + getHeader(headers, "NOT PRESENT (Expected)"));

    System.out.println("\nSleeping for 5 seconds to let initial background RAB lookup finish...");
    Thread.sleep(5000);

    // Access the internal RegionalAccessBoundaryManager via reflection
    Field managerField = GoogleCredentials.class.getDeclaredField("regionalAccessBoundaryManager");
    managerField.setAccessible(true);
    Object rabManager = managerField.get(credentials);

    if (rabManager == null) {
      System.err.println("Failed to access RegionalAccessBoundaryManager");
      return;
    }

    // Access the cachedRAB field
    Field cachedRabField = rabManager.getClass().getDeclaredField("cachedRAB");
    cachedRabField.setAccessible(true);
    AtomicReference<?> cachedRabRef = (AtomicReference<?>) cachedRabField.get(rabManager);
    Object currentRab = cachedRabRef.get();

    if (currentRab == null) {
      System.out.println("RAB lookup might have failed or is still pending. Cannot test soft expiry.");
      return;
    }

    // Get current RAB properties
    Class<?> rabClass = currentRab.getClass();
    java.lang.reflect.Method getEncodedLocationsMethod = rabClass.getMethod("getEncodedLocations");
    getEncodedLocationsMethod.setAccessible(true);
    String encodedLocations = (String) getEncodedLocationsMethod.invoke(currentRab);
    
    java.lang.reflect.Method getLocationsMethod = rabClass.getMethod("getLocations");
    getLocationsMethod.setAccessible(true);
    List<String> locations = (List<String>) getLocationsMethod.invoke(currentRab);
    
    Field refreshTimeField = rabClass.getDeclaredField("refreshTime");
    refreshTimeField.setAccessible(true);
    long initialRefreshTime = refreshTimeField.getLong(currentRab);

    System.out.println("\nRAB Refresh Time after initial fetch: " + new Date(initialRefreshTime).toString());
    System.out.println("RAB Value after initial fetch: " + encodedLocations);

    System.out.println("\n--- Manipulating Refresh Time for Soft Expiry Test ---");
    // TTL is 6 hours (21600000 ms), Threshold is 1 hour (3600000 ms).
    // Soft expiry happens when currentTime > refreshTime + (TTL - Threshold)
    // which means currentTime > refreshTime + 5 hours.
    // So we set refreshTime = currentTime - 5 hours - 5 minutes, 
    // making it well within the 1-hour grace period.
    long fiveHoursFiveMinutesMs = (5 * 60 * 60 * 1000L) + (5 * 60 * 1000L);
    long newRefreshTime = System.currentTimeMillis() - fiveHoursFiveMinutesMs;
    
    // Create a new RAB instance with the manipulated refreshTime using reflection
    Constructor<?> rabConstructor = rabClass.getDeclaredConstructor(String.class, List.class, long.class, com.google.api.client.util.Clock.class);
    rabConstructor.setAccessible(true);
    Object manipulatedRab = rabConstructor.newInstance(encodedLocations, locations, newRefreshTime, null);
    
    // Set the manipulated RAB back into the manager
    ((AtomicReference<Object>) cachedRabRef).set(manipulatedRab);
    System.out.println("Manually set RAB Refresh Time to: " + new Date(newRefreshTime).toString() + " (Within 1-hour grace period)");

    System.out.println("\n--- Second Call to getRequestMetadata (Triggers Soft Expiry Refresh) ---");
    // This call should attach the cached (but expiring) RAB header and trigger a background refresh.
    headers = credentials.getRequestMetadata(BUCKET_URL);
    String xAllowedLocations = getHeader(headers, null);
    System.out.println("x-allowed-locations (Second attempt): " + (xAllowedLocations != null ? xAllowedLocations : "NOT PRESENT"));

    if (xAllowedLocations != null) {
      System.out.println("SUCCESS: x-allowed-locations is still attached because the RAB has not hard-expired.");
    } else {
      System.out.println("FAILURE: x-allowed-locations should be attached during the soft expiry window.");
    }

    System.out.println("\nSleeping for 5 seconds to let the background soft-expiry refresh finish...");
    Thread.sleep(5000);

    // Get the new cached RAB
    Object refreshedRab = cachedRabRef.get();
    long refreshedRefreshTime = refreshTimeField.getLong(refreshedRab);
    
    java.lang.reflect.Method getRefreshedEncodedLocationsMethod = rabClass.getMethod("getEncodedLocations");
    getRefreshedEncodedLocationsMethod.setAccessible(true);
    String refreshedEncodedLocations = (String) getRefreshedEncodedLocationsMethod.invoke(refreshedRab);

    System.out.println("\nRAB Refresh Time after soft-expiry refresh: " + new Date(refreshedRefreshTime).toString());
    System.out.println("RAB Value after soft-expiry refresh: " + refreshedEncodedLocations);

    // If the refresh succeeded, the new refreshTime should be very close to now,
    // which is significantly larger than the manipulated refreshTime (which was ~5 hours ago).
    if (refreshedRefreshTime > newRefreshTime + (4 * 60 * 60 * 1000L)) {
      System.out.println("\nSUCCESS: RAB Refresh Time was updated significantly, confirming a proactive background refresh occurred.");
    } else {
      System.out.println("\nFAILURE: RAB Refresh Time was not updated as expected. Background refresh may have failed or not triggered.");
    }
  }

  private static String getHeader(Map<String, List<String>> headers, String defaultMsg) {
    List<String> val = headers.get("x-allowed-locations");
    return (val != null && !val.isEmpty()) ? val.get(0) : defaultMsg;
  }
}
