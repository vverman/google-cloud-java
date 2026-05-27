package rabtest;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.RegionalAccessBoundaryProvider;
import com.google.cloud.storage.Bucket;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Verbose E2E test for Regional Access Boundary (RAB) with full logging enabled.
 * Designed for capturing screenshots of the complete workflow including background thread activity.
 */
public class RabVerboseE2ETest {

  private static final String BUCKET_NAME = "byoid-test";
  private static final URI BUCKET_URL = URI.create("https://storage.googleapis.com/storage/v1/b/" + BUCKET_NAME);
  private static final String JSON_PATH = "/Users/pjiyer/.config/gcloud/application_default_credentials.json";

  public static void main(String[] args) throws Exception {
    // Enable fine-grained logging for the auth library to see RAB activity
    setupLogging();

    System.out.println("=== Starting Verbose RAB E2E Test ===");
    
    // Load credentials using explicit file path.
    System.out.println("Loading credentials from: " + JSON_PATH);
    GoogleCredentials credentials = GoogleCredentials.fromStream(new java.io.FileInputStream(JSON_PATH))
        .createScoped("https://www.googleapis.com/auth/cloud-platform");

    System.out.println("Credential Instance: " + credentials.getClass().getName());
    
    if (credentials instanceof RegionalAccessBoundaryProvider) {
      String url = ((RegionalAccessBoundaryProvider) credentials).getRegionalAccessBoundaryUrl();
      System.out.println("Target RAB Lookup URL: " + url);
    }

    System.out.println("\n--- STEP 1: Initial request (Triggers Async Refresh) ---");
    Map<String, List<String>> headers = credentials.getRequestMetadata(BUCKET_URL);
    printRABHeader(headers);

    System.out.println("\n--- STEP 2: Sleeping 5s (Watch logs for background thread completion) ---");
    Thread.sleep(5000);

    System.out.println("\n--- STEP 3: Second request (Should use cached RAB) ---");
    headers = credentials.getRequestMetadata(BUCKET_URL);
    printRABHeader(headers);

    System.out.println("\n--- STEP 4: Storage Bucket Fetch ---");
    Storage storage = StorageOptions.newBuilder().setCredentials(credentials).build().getService();
    try {
      Bucket bucket = storage.get(BUCKET_NAME);
      System.out.println("Bucket Request Successful!");
      System.out.println("Bucket Info: " + bucket.toString());
    } catch (Exception e) {
      System.err.println("Bucket Request Failed: " + e.getMessage());
      e.printStackTrace();
    }
    
    System.out.println("\n=== Verbose Test Complete ===");
  }

  private static void printRABHeader(Map<String, List<String>> headers) {
    List<String> val = headers.get("x-allowed-locations");
    String result = (val != null && !val.isEmpty()) ? val.get(0) : "MISSING";
    System.out.println("RESULT -> x-allowed-locations: " + result);
  }

  private static void setupLogging() {
    // We try to enable JUL logging which many Google libraries use as a fallback
    Logger logger = Logger.getLogger("com.google.auth");
    logger.setLevel(Level.ALL);
    ConsoleHandler handler = new ConsoleHandler();
    handler.setLevel(Level.ALL);
    logger.addHandler(handler);
    
    // Also set property for SDK logging if the library supports it
    System.setProperty("GOOGLE_SDK_JAVA_LOGGING", "true");
  }
}
