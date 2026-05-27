package rabtest;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import java.io.FileInputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * E2E test to demonstrate that Regional Access Boundary (RAB) refresh is NOT triggered
 * when making a request to a regional endpoint (e.g., *.rep.googleapis.com).
 */
public class RabRegionalEndpointE2ETest {

  // A regional endpoint URI
  private static final URI REGIONAL_URL = URI.create("https://us-central1.rep.googleapis.com/v1/some-resource");
  private static final String JSON_PATH = "/Users/pjiyer/Documents/google-auth-adc/lookup-endpoint-service-account/lookup-service-account.json";

  public static void main(String[] args) throws Exception {
    setupLogging();
    
    System.out.println("=== Starting Regional Endpoint RAB Test ===");
    System.out.println("Goal: Verify that RAB refresh is skipped for regional endpoints (*.rep.googleapis.com).\n");

    // 1. Instantiate Service Account credentials (default universe)
    System.out.println("Loading credentials from: " + JSON_PATH);
    
    ServiceAccountCredentials credentials = (ServiceAccountCredentials) ServiceAccountCredentials
        .fromStream(new FileInputStream(JSON_PATH));

    System.out.println("Universe Domain: " + credentials.getUniverseDomain());
    System.out.println("Is Default Universe: " + "googleapis.com".equals(credentials.getUniverseDomain()));
    System.out.println("Target URI: " + REGIONAL_URL);

    // 2. First call to getRequestMetadata with regional URI
    // This should skip the background RAB refresh because of the regional endpoint.
    System.out.println("\n--- STEP 1: Initial request (Should NOT trigger background refresh) ---");
    Map<String, List<String>> headers = credentials.getRequestMetadata(REGIONAL_URL);
    printRABHeader(headers);

    // 3. Sleep to prove nothing happens in the background
    System.out.println("\n--- STEP 2: Sleeping 5s (No RAB logs should appear above) ---");
    Thread.sleep(5000);

    // 4. Second call
    System.out.println("\n--- STEP 3: Second request (Header should still be missing) ---");
    headers = credentials.getRequestMetadata(REGIONAL_URL);
    printRABHeader(headers);

    if (!headers.containsKey("x-allowed-locations")) {
      System.out.println("\nSUCCESS: No RAB header was attached, as expected for a regional endpoint.");
    } else {
      System.err.println("\nFAILURE: RAB header was unexpectedly found!");
    }
    
    System.out.println("\n=== Test Complete ===");
  }

  private static void printRABHeader(Map<String, List<String>> headers) {
    List<String> val = headers.get("x-allowed-locations");
    String result = (val != null && !val.isEmpty()) ? val.get(0) : "NOT PRESENT";
    System.out.println("RESULT -> x-allowed-locations: " + result);
  }

  private static void setupLogging() {
    Logger logger = Logger.getLogger("com.google.auth");
    logger.setLevel(Level.ALL);
    ConsoleHandler handler = new ConsoleHandler();
    handler.setLevel(Level.ALL);
    logger.addHandler(handler);
    System.setProperty("GOOGLE_SDK_JAVA_LOGGING", "true");
  }
}