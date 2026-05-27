package rabtest;

import com.google.auth.oauth2.IdToken;
import com.google.auth.oauth2.IdTokenCredentials;
import com.google.auth.oauth2.IdTokenProvider;
import com.google.auth.oauth2.RegionalAccessBoundaryProvider;
import java.io.IOException;
import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * E2E test for Regional Access Boundary (RAB) with IdTokenCredentials.
 * Demonstrates that RAB is not supported and not attached for ID tokens.
 */
public class RabIdTokenE2ETest {
  
  private static final String BUCKET_NAME = "trust_boundary_test_bucket";
  private static final URI BUCKET_URL = URI.create("https://storage.googleapis.com/storage/v1/b/" + BUCKET_NAME);

  public static void main(String[] args) throws Exception {
    setupLogging();
    
    System.out.println("=== Starting IdToken RAB Test ===");

    // Create a mock provider that returns a dummy ID token
    IdTokenProvider mockIdTokenProvider = new IdTokenProvider() {
      @Override
      public IdToken idTokenWithAudience(String targetAudience, List<Option> options) throws IOException {
        System.out.println("Mocking ID token fetch for audience: " + targetAudience);
        long exp = (System.currentTimeMillis() / 1000) + 3600;
        String payloadJson = "{\"exp\": " + exp + "}";
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes("UTF-8"));
        String tokenString = "eyJhbGciOiJSUzI1NiJ9." + payload + ".ZHVtbXlfc2lnbmF0dXJl";
        return IdToken.create(tokenString);
      }
    };

    IdTokenCredentials credentials = IdTokenCredentials.newBuilder()
        .setIdTokenProvider(mockIdTokenProvider)
        .setTargetAudience("https://example.com")
        .build();
    
    System.out.println("Client Type: " + credentials.getClass().getSimpleName());

    // Check if the credential supports providing a RAB URL
    boolean isProvider = credentials instanceof RegionalAccessBoundaryProvider;
    System.out.println("Has getRegionalAccessBoundaryUrl: " + isProvider);

    try {
      System.out.println("\n--- First Call to getRequestMetadata ---");
      Map<String, List<String>> headers = credentials.getRequestMetadata(BUCKET_URL);
      
      String xAllowedLocations = getHeader(headers);
      System.out.println("x-allowed-locations: " + (xAllowedLocations != null ? xAllowedLocations : "NOT PRESENT (Correct behavior for ID tokens)"));

      if (xAllowedLocations != null) {
        System.out.println("FAILURE: RAB header should NOT be present for ID tokens.");
      }

      System.out.println("\nSleeping for 10 seconds...");
      Thread.sleep(10000);

      System.out.println("\n--- Second Call to getRequestMetadata (after wait) ---");
      headers = credentials.getRequestMetadata(BUCKET_URL);
      xAllowedLocations = getHeader(headers);
      System.out.println("x-allowed-locations: " + (xAllowedLocations != null ? xAllowedLocations : "STILL NOT PRESENT (Success)"));

      if (xAllowedLocations != null) {
        System.out.println("FAILURE: RAB header appeared after wait, which is incorrect for ID tokens.");
      } else {
        System.out.println("\nSUCCESS: Verified that RAB headers are never attached to IdTokenClient requests.");
      }

      System.out.println("\nFull Headers Object:");
      for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
        System.out.println("  " + entry.getKey() + ": " + entry.getValue());
      }

    } catch (Exception e) {
      System.err.println("Error during verification:");
      e.printStackTrace();
    }
  }

  private static String getHeader(Map<String, List<String>> headers) {
    List<String> val = headers.get("x-allowed-locations");
    return (val != null && !val.isEmpty()) ? val.get(0) : null;
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
