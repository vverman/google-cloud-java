package rabtest;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.RegionalAccessBoundaryProvider;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * A Java equivalent of the Node.js RAB test script to demonstrate Regional Access Boundary features
 * using a specific JSON credential file.
 */
public class RabE2ETest {

  // Replace with a bucket name your account has access to
  private static final String BUCKET_NAME = "trust_boundary_test_bucket";
  private static final URI URL = URI.create("https://storage.googleapis.com/storage/v1/b/" + BUCKET_NAME);
  private static final String JSON_PATH = "/Users/pjiyer/Documents/google-auth-adc/lookup-endpoint-service-account/lookup-service-account.json";

  public static void main(String[] args) throws Exception {
    
    // Load credentials using explicit file path.
    System.out.println("Loading credentials from: " + JSON_PATH);
    GoogleCredentials credentials = GoogleCredentials.fromStream(new java.io.FileInputStream(JSON_PATH))
        .createScoped("https://www.googleapis.com/auth/cloud-platform");

    System.out.println("Client Type: " + credentials.getClass().getSimpleName());

    // Check if the credential supports providing a RAB URL
    boolean isProvider = credentials instanceof RegionalAccessBoundaryProvider;
    System.out.println("Has getRegionalAccessBoundaryUrl: " + isProvider);

    if (isProvider) {
      try {
        String rabUrl = ((RegionalAccessBoundaryProvider) credentials).getRegionalAccessBoundaryUrl();
        System.out.println("RAB URL: " + rabUrl);
      } catch (Exception e) {
        System.out.println("RAB URL Error: " + e.getMessage());
      }
    }

    try {
      System.out.println("--- First Call to getRequestMetadata ---");
      Map<String, List<String>> headers = credentials.getRequestMetadata(URL);
      System.out.println("Headers (First attempt):");
      System.out.println(
          "x-allowed-locations: " + getHeader(headers, "x-allowed-locations", "NOT PRESENT (Expected for cold start)"));

      System.out.println("\nSleeping for 5 seconds to let background RAB lookup finish...");
      Thread.sleep(5000);

      System.out.println("--- Second Call to getRequestMetadata ---");
      headers = credentials.getRequestMetadata(URL);
      System.out.println("Headers (Second attempt):");
      System.out.println(
          "x-allowed-locations: " + getHeader(headers, "x-allowed-locations", "STILL NOT PRESENT (Lookup might have failed or timed out)"));

      if (headers.containsKey("x-allowed-locations")) {
        System.out.println("Success! RAB header is now present.");
      }

      System.out.println("\nFull Headers Object:");
      for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
        System.out.println("  " + entry.getKey() + ": " + entry.getValue());
      }

    } catch (Exception e) {
      System.err.println("Error fetching request metadata:");
      e.printStackTrace();
    }
    
    System.out.println("\n(Bucket data request is omitted here as it requires a full HTTP client setup, "
        + "but the metadata output above proves the credential state.)");
  }

  private static String getHeader(Map<String, List<String>> metadata, String key, String defaultMsg) {
    List<String> values = metadata.get(key);
    return (values != null && !values.isEmpty()) ? values.get(0) : defaultMsg;
  }
}
