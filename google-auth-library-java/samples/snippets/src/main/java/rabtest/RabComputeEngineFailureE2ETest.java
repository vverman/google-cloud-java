package rabtest;

import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.auth.http.HttpTransportFactory;
import com.google.auth.oauth2.ComputeEngineCredentials;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * E2E test to demonstrate the behavior of Compute Engine Credentials when it 
 * fails to fetch the 'default' service account email from the metadata server.
 */
public class RabComputeEngineFailureE2ETest {

  private static final URI BUCKET_URL = URI.create("https://storage.googleapis.com/storage/v1/b/any-bucket");

  public static void main(String[] args) throws Exception {
    setupLogging();
    
    System.out.println("=== Starting Compute Engine RAB Failure Test ===");
    System.out.println("Goal: Verify the behavior when the metadata server returns an error (404)");
    System.out.println("while attempting to fetch the default service account email.\n");

    // 1. Create a mock transport that always returns a 404 Not Found error
    HttpTransportFactory mockTransportFactory = new HttpTransportFactory() {
      @Override
      public HttpTransport create() {
        return new HttpTransport() {
          @Override
          protected LowLevelHttpRequest buildRequest(String method, String url) throws IOException {
            return new LowLevelHttpRequest() {
              @Override
              public void addHeader(String name, String value) throws IOException {
              }

              @Override
              public LowLevelHttpResponse execute() throws IOException {
                return new LowLevelHttpResponse() {
                  @Override
                  public InputStream getContent() throws IOException {
                    return new ByteArrayInputStream("Not Found".getBytes());
                  }

                  @Override
                  public String getContentEncoding() throws IOException {
                    return null;
                  }

                  @Override
                  public long getContentLength() throws IOException {
                    return 0;
                  }

                  @Override
                  public String getContentType() throws IOException {
                    return "text/plain";
                  }

                  @Override
                  public String getStatusLine() throws IOException {
                    return "HTTP/1.1 404 Not Found";
                  }

                  @Override
                  public int getStatusCode() throws IOException {
                    return 404;
                  }

                  @Override
                  public String getReasonPhrase() throws IOException {
                    return "Not Found";
                  }

                  @Override
                  public int getHeaderCount() throws IOException {
                    return 0;
                  }

                  @Override
                  public String getHeaderName(int index) throws IOException {
                    return null;
                  }

                  @Override
                  public String getHeaderValue(int index) throws IOException {
                    return null;
                  }
                };
              }
            };
          }
        };
      }
    };

    // 2. Instantiate Compute Engine Credentials with the mock metadata server transport
    System.out.println("Instantiating ComputeEngineCredentials with mock metadata server (returns 404)...");
    ComputeEngineCredentials credentials = ComputeEngineCredentials.newBuilder()
        .setHttpTransportFactory(mockTransportFactory)
        // We purposefully do not set the service account email, forcing it to fetch the 'default'.
        .build();

    // 3. Call getRequestMetadata
    System.out.println("\n--- STEP 1: Calling getRequestMetadata ---");
    System.out.println("This will attempt to fetch the token, which first fetches the service account email.");
    
    try {
      Map<String, List<String>> headers = credentials.getRequestMetadata(BUCKET_URL);
      printRABHeader(headers);
      System.err.println("FAILURE: Request unexpectedly succeeded.");
    } catch (Exception e) {
      System.out.println("\nSUCCESS: EXPECTED EXCEPTION caught during getRequestMetadata:");
      System.out.println("Exception message: " + e.getMessage());
      System.out.println("\nBecause the metadata server returned a 404, we cannot get the service account email.");
      System.out.println("Consequently, the access token refresh fails, and the RAB lookup is gracefully skipped.");
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
