package com.example.zabbix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.io.IOException;
import java.util.Objects;

/**
 * Synchronous client for interacting with the Zabbix API.
 */
public class ZabbixApiSyncClient {

    private final String apiUrl;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private String authToken;

    /**
     * Constructs a new ZabbixApiSyncClient.
     *
     * @param apiUrl The URL of the Zabbix API (e.g., "http://your-zabbix-server/api_jsonrpc.php").
     */
    public ZabbixApiSyncClient(String apiUrl) {
        this.apiUrl = apiUrl;
        this.httpClient = new OkHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Authenticates with the Zabbix API using the provided username and password.
     * The authentication token is stored for subsequent requests.
     *
     * @param username The Zabbix username.
     * @param password The Zabbix password.
     * @throws IOException If a network error occurs or the API returns an error.
     */
    public void authenticate(String username, String password) throws IOException {
        Objects.requireNonNull(username, "Username cannot be null");
        Objects.requireNonNull(password, "Password cannot be null");

        // Using a simple Map for params, Jackson will convert it to a JSON object
        java.util.Map<String, String> params = new java.util.HashMap<>();
        params.put("user", username);
        params.put("password", password);

        JsonNode response = postRequest("user.login", params);
        if (response != null && response.isTextual()) {
            this.authToken = response.asText();
        } else {
            // This case should ideally be handled by postRequest if the response structure is not as expected
            // or if 'result' is not a text node.
            throw new IOException("Authentication failed: Unexpected response format or missing token.");
        }
    }

    /**
     * Sends a generic POST request to the Zabbix API.
     *
     * @param method The Zabbix API method (e.g., "host.get").
     * @param params The parameters for the API method.
     * @return The "result" field from the Zabbix API response as a JsonNode.
     * @throws IOException If a network error occurs or the API returns an error.
     */
    private JsonNode postRequest(String method, Object params) throws IOException {
        Objects.requireNonNull(method, "Zabbix API method cannot be null");
        Objects.requireNonNull(params, "Zabbix API params cannot be null");

        ObjectMapper localMapper = new ObjectMapper(); // For constructing the request body
        ObjectNode requestBody = localMapper.createObjectNode();
        requestBody.put("jsonrpc", "2.0");
        requestBody.put("method", method);
        requestBody.set("params", localMapper.valueToTree(params));
        requestBody.put("id", 1); // Request ID, can be any integer or string

        if (this.authToken != null && !method.equals("user.login")) {
            requestBody.put("auth", this.authToken);
        }

        RequestBody body = RequestBody.create(
                requestBody.toString(),
                MediaType.get("application/json-rpc")
        );

        Request request = new Request.Builder()
                .url(this.apiUrl)
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected HTTP code " + response.code() + " - " + response.message());
            }

            String responseBodyString = Objects.requireNonNull(response.body()).string();
            JsonNode responseJson = objectMapper.readTree(responseBodyString);

            if (responseJson.has("error")) {
                JsonNode errorNode = responseJson.get("error");
                throw new IOException("Zabbix API Error: " + errorNode.get("message").asText() +
                        " | Code: " + errorNode.get("code").asInt() +
                        " | Data: " + errorNode.get("data").asText());
            }
            return responseJson.get("result");
        }
    }

    /**
     * Retrieves information about a host by its name.
     *
     * @param hostName The name of the host to retrieve.
     * @return A JsonNode containing the host information, or null if not found.
     * @throws IOException If a network error occurs or the API returns an error.
     */
    public JsonNode getHost(String hostName) throws IOException {
        Objects.requireNonNull(hostName, "Host name cannot be null");
        if (this.authToken == null) {
            throw new IllegalStateException("Client is not authenticated. Call authenticate() first.");
        }

        java.util.Map<String, Object> params = new java.util.HashMap<>();
        java.util.Map<String, String> filter = new java.util.HashMap<>();
        filter.put("host", hostName);
        params.put("filter", filter);
        params.put("output", "extend"); // Request all available fields for the host

        JsonNode result = postRequest("host.get", params);
        if (result != null && result.isArray() && result.size() > 0) {
            return result.get(0); // Return the first host found
        }
        return null; // Host not found or empty result
    }

    /**
     * Retrieves information about an item by its name and host name.
     *
     * @param itemName The name of the item.
     * @param hostName The name of the host the item belongs to.
     * @return A JsonNode containing the item information, or null if not found.
     * @throws IOException If a network error occurs or the API returns an error.
     */
    public JsonNode getItem(String itemName, String hostName) throws IOException {
        Objects.requireNonNull(itemName, "Item name cannot be null");
        Objects.requireNonNull(hostName, "Host name cannot be null");
        if (this.authToken == null) {
            throw new IllegalStateException("Client is not authenticated. Call authenticate() first.");
        }

        // First, get the host to find its hostid
        JsonNode host = getHost(hostName);
        if (host == null || !host.has("hostid")) {
            // Host not found or hostid is missing
            return null;
        }
        String hostId = host.get("hostid").asText();

        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("hostids", hostId);
        java.util.Map<String, String> search = new java.util.HashMap<>();
        search.put("name", itemName); // Search by item name
        params.put("search", search);
        params.put("output", "extend"); // Request all available fields for the item

        JsonNode result = postRequest("item.get", params);
        if (result != null && result.isArray() && result.size() > 0) {
            return result.get(0); // Return the first item found
        }
        return null; // Item not found or empty result
    }
}
