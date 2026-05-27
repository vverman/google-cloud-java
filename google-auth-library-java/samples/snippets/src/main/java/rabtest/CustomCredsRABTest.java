package rabtest;

import com.google.api.client.http.HttpContent;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.UrlEncodedContent;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonObjectParser;
import com.google.api.client.json.gson.GsonFactory;
import com.google.auth.oauth2.ExternalAccountSupplierContext;
import com.google.auth.oauth2.IdentityPoolCredentials;
import com.google.auth.oauth2.IdentityPoolSubjectTokenSupplier;
import com.google.cloud.storage.Bucket;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CustomCredsRABTest {

  private static final String BUCKET_NAME = "byoid-test";
  private static final URI BUCKET_URL = URI.create("https://storage.googleapis.com/storage/v1/b/" + BUCKET_NAME);

  public static void main(String[] args) throws Exception {
    String audience = "//iam.googleapis.com/projects/654269145772/locations/global/workloadIdentityPools/byoid-pool/providers/aion-sdk-okta-oidc";
    String impersonationUrl = "https://iamcredentials.googleapis.com/v1/projects/-/serviceAccounts/byoid-test@cicpclientproj.iam.gserviceaccount.com:generateAccessToken";
    String oktaDomain = "https://integrator-9388438.okta.com";
    String oktaClientId = "0oa10mpmuhiPTvrtK698";
    String oktaClientSecret = "get from Valentine"; // REFER https://valentine.corp.google.com/#/show/1772514205897767?tab=metadata

    System.out.println("=== Starting Custom Credentials RAB Test ===");

    OktaSupplier oktaSupplier = new OktaSupplier(oktaDomain, oktaClientId, oktaClientSecret);

    IdentityPoolCredentials credentials = IdentityPoolCredentials.newBuilder()
        .setAudience(audience)
        .setSubjectTokenType("urn:ietf:params:oauth:token-type:jwt")
        .setSubjectTokenSupplier(oktaSupplier)
        .setServiceAccountImpersonationUrl(impersonationUrl)
        .build();

    System.out.println("--- First Call to getRequestMetadata ---");
    // Trigger background RAB lookup
    Map<String, List<String>> headers = credentials.getRequestMetadata(BUCKET_URL);
    printRABHeader(headers, "NOT PRESENT (Expected for cold start)");

    System.out.println("\nSleeping for 5 seconds to let background RAB lookup finish...");
    Thread.sleep(5000);

    System.out.println("--- Second Call to getRequestMetadata ---");
    headers = credentials.getRequestMetadata(BUCKET_URL);
    printRABHeader(headers, "STILL NOT PRESENT (Lookup might have failed)");

    if (headers.containsKey("x-allowed-locations")) {
      System.out.println("Success! RAB header is now present.");
    }

    System.out.println("\nAttempting to fetch bucket: " + BUCKET_NAME);
    try {
        Storage storage = StorageOptions.newBuilder().setCredentials(credentials).build().getService();
        Bucket bucket = storage.get(BUCKET_NAME);
        if (bucket != null) {
          System.out.println("Success! Bucket Data: " + bucket.getName() + " (Location: " + bucket.getLocation() + ")");
        } else {
          System.out.println("Bucket not found.");
        }
    } catch (Exception e) {
        System.err.println("Error fetching bucket: " + e.getMessage());
        e.printStackTrace();
    }
  }

  private static void printRABHeader(Map<String, List<String>> headers, String defaultMsg) {
    List<String> val = headers.get("x-allowed-locations");
    System.out.println("x-allowed-locations: " + ((val != null && !val.isEmpty()) ? val.get(0) : defaultMsg));
  }

  private static class OktaSupplier implements IdentityPoolSubjectTokenSupplier {
    private static final long serialVersionUID = 1L;
    private final String tokenUrl;
    private final String authHeader;

    public OktaSupplier(String domain, String clientId, String clientSecret) {
      this.tokenUrl = domain.endsWith("/") ? domain + "oauth2/default/v1/token" : domain + "/oauth2/default/v1/token";
      String auth = clientId + ":" + clientSecret;
      this.authHeader = "Basic " + Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String getSubjectToken(ExternalAccountSupplierContext context) throws IOException {
      GenericUrl url = new GenericUrl(tokenUrl);
      Map<String, String> params = new HashMap<>();
      params.put("grant_type", "client_credentials");
      params.put("scope", "access-gcp");

      HttpContent content = new UrlEncodedContent(params);
      HttpRequest request = new NetHttpTransport().createRequestFactory().buildPostRequest(url, content);
      request.getHeaders().setAuthorization(authHeader);
      request.setParser(new JsonObjectParser(GsonFactory.getDefaultInstance()));

      HttpResponse response = request.execute();
      try {
        Map<String, Object> data = (Map<String, Object>) response.parseAs(new TypeToken<Map<String, Object>>(){}.getType());
        return (String) data.get("access_token");
      } finally {
        response.disconnect();
      }
    }
  }
}
