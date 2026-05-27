package rabtest;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.RegionalAccessBoundaryProvider;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.storage.Bucket;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import java.io.FileInputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * E2E test for Regional Access Boundary (RAB) using Self-Signed JWT Access.
 * Demonstrates the full flow including fetching data from Google Cloud Storage.
 */
public class RabSelfSignedJwtE2ETest {
  
  private static final String BUCKET_NAME = "byoid-test";
  private static final URI BUCKET_URL = URI.create("https://storage.googleapis.com/storage/v1/b/" + BUCKET_NAME);

  public static void main(String[] args) throws Exception {
    // Explicit file path for the service account
    String jsonPath = "/Users/pjiyer/Documents/google-auth-adc/cicpclientproj-service-account/pjiyer-sa.json";

    System.out.println("Loading credentials from: " + jsonPath);
    
    // Instantiate ServiceAccountCredentials and force self-signed JWT
    ServiceAccountCredentials credentials = ((ServiceAccountCredentials) GoogleCredentials
        .fromStream(new FileInputStream(jsonPath))
        .createScoped("https://www.googleapis.com/auth/cloud-platform"))
        .toBuilder()
        .setUseJwtAccessWithScope(true)
        .build();
    
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
      System.out.println("\n--- First Call to getRequestMetadata ---");
      Map<String, List<String>> headers = credentials.getRequestMetadata(BUCKET_URL);
      System.out.println("Headers (First attempt):");
      System.out.println("x-allowed-locations: " + getHeader(headers, "NOT PRESENT (Expected for cold start)"));

      System.out.println("\nSleeping for 5 seconds to let background RAB lookup finish...");
      Thread.sleep(5000);

      System.out.println("\n--- Second Call to getRequestMetadata ---");
      headers = credentials.getRequestMetadata(BUCKET_URL);
      System.out.println("Headers (Second attempt):");
      System.out.println("x-allowed-locations: " + getHeader(headers, "STILL NOT PRESENT (Lookup might have failed or timed out)"));

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

    // Attempting to request info for the bucket
    System.out.println("\nAttempting to request info for bucket: " + BUCKET_NAME);
    System.out.println("Request URL: " + BUCKET_URL);

    try {
      Storage storage = StorageOptions.newBuilder().setCredentials(credentials).build().getService();
      Bucket bucket = storage.get(BUCKET_NAME);
      if (bucket != null) {
        System.out.println("Success! Bucket Data:");
        System.out.println("  Name: " + bucket.getName());
        System.out.println("  Location: " + bucket.getLocation());
        System.out.println("  Storage Class: " + bucket.getStorageClass());
        System.out.println("  Time Created: " + bucket.getCreateTimeOffsetDateTime());
      } else {
        System.out.println("Bucket not found.");
      }
    } catch (Exception e) {
      System.err.println("Error fetching bucket data:");
      e.printStackTrace();
    }
  }

  private static String getHeader(Map<String, List<String>> headers, String defaultMsg) {
    List<String> val = headers.get("x-allowed-locations");
    return (val != null && !val.isEmpty()) ? val.get(0) : defaultMsg;
  }
}
