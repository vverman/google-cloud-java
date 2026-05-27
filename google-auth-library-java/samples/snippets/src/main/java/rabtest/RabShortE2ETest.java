package rabtest;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.Bucket;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Succinct E2E test for Regional Access Boundary (RAB) including a bucket fetch.
 * Designed for documentation and ease of reading.
 */
public class RabShortE2ETest {
  
  private static final String BUCKET_NAME = "byoid-test";
  private static final URI BUCKET_URL = URI.create("https://storage.googleapis.com/storage/v1/b/" + BUCKET_NAME);
  private static final String JSON_PATH = "/Users/pjiyer/Documents/google-auth-adc/cicpclientproj-service-account/pjiyer-sa.json";

  public static void main(String[] args) throws Exception {
    // Load credentials using explicit file path.
    System.out.println("Loading credentials from: " + JSON_PATH);
    GoogleCredentials credentials = GoogleCredentials.fromStream(new java.io.FileInputStream(JSON_PATH))
        .createScoped("https://www.googleapis.com/auth/cloud-platform");
    
    System.out.println("Client Type: " + credentials.getClass().getSimpleName());

    // 1. First call triggers background RAB lookup
    Map<String, List<String>> headers = credentials.getRequestMetadata(BUCKET_URL);
    System.out.println("\nCall 1 - x-allowed-locations: " + getHeader(headers));

    System.out.println("Waiting 5s for background lookup...");
    Thread.sleep(5000);

    // 2. Second call should have cached RAB header
    headers = credentials.getRequestMetadata(BUCKET_URL);
    System.out.println("Call 2 - x-allowed-locations: " + getHeader(headers));

    // 3. Perform actual bucket fetch
    Storage storage = StorageOptions.newBuilder().setCredentials(credentials).build().getService();
    System.out.println("\nAttempting to fetch bucket: " + BUCKET_NAME);
    Bucket bucket = storage.get(BUCKET_NAME);
    System.out.println("Success! Bucket Data: " + bucket.getName() + " (Location: " + bucket.getLocation() + ")");
  }

  private static String getHeader(Map<String, List<String>> headers) {
    List<String> val = headers.get("x-allowed-locations");
    return (val != null && !val.isEmpty()) ? val.get(0) : "NOT PRESENT (Expected)";
  }
}
