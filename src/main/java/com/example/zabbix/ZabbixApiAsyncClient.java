package com.example.zabbix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Asynchronous client for interacting with the Zabbix API.
 * This client uses OkHttp for asynchronous HTTP requests and Jackson for JSON processing.
 * All API calls return a {@link CompletableFuture} that will be completed with the result
 * or completed exceptionally if an error occurs.
 * This client supports authentication via username/password or by providing a pre-configured authentication token.
 */
public class ZabbixApiAsyncClient {

    private final String apiUrl;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private volatile String authToken; // Volatile as it can be set by an async callback
    private String preConfiguredAuthToken; // Field for pre-configured token

    /**
     * Constructs a new ZabbixApiAsyncClient with the specified API URL.
     * After construction, {@link #authenticateAsync(String, String)} must be called to obtain an
     * authentication token before making other API requests.
     *
     * @param apiUrl The URL of the Zabbix API (e.g., "http://your-zabbix-server/api_jsonrpc.php").
     *               Cannot be null.
     */
    public ZabbixApiAsyncClient(String apiUrl) {
        Objects.requireNonNull(apiUrl, "API URL cannot be null");
        this.apiUrl = apiUrl;
        this.httpClient = new OkHttpClient(); // OkHttpClient is designed to be shared
        this.objectMapper = new ObjectMapper();
        this.preConfiguredAuthToken = null;
    }

    /**
     * Constructs a new ZabbixApiAsyncClient with the specified API URL and a pre-configured authentication token.
     * Using this constructor, the client is immediately ready to make authenticated API requests.
     * Calling {@link #authenticateAsync(String, String)} on a client constructed this way will result in the
     * returned {@link CompletableFuture} completing exceptionally with an {@link IllegalStateException}.
     *
     * @param apiUrl The URL of the Zabbix API (e.g., "http://your-zabbix-server/api_jsonrpc.php"). Cannot be null.
     * @param token The pre-configured Zabbix authentication token. Cannot be null or empty.
     */
    public ZabbixApiAsyncClient(String apiUrl, String token) {
        Objects.requireNonNull(apiUrl, "API URL cannot be null");
        Objects.requireNonNull(token, "Authentication token cannot be null");
        this.apiUrl = apiUrl;
        this.httpClient = new OkHttpClient();
        this.objectMapper = new ObjectMapper();
        this.preConfiguredAuthToken = token;
        this.authToken = token; // Use the pre-configured token directly
    }

    /**
     * Asynchronously authenticates with the Zabbix API using the provided username and password.
     * The authentication token is stored internally for subsequent requests if successful.
     *
     * @param username The Zabbix username. Cannot be null.
     * @param password The Zabbix password. Cannot be null.
     * @return A {@link CompletableFuture} that will be completed with the authentication token (a String)
     *         upon successful authentication.
     *         The future will be completed exceptionally if authentication fails, a network error occurs,
     *         the API returns an error, or if the client was constructed with a pre-configured token
     *         (in which case it completes exceptionally with an {@link IllegalStateException}).
     */
    public CompletableFuture<String> authenticateAsync(String username, String password) {
        if (this.preConfiguredAuthToken != null) {
            CompletableFuture<String> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalStateException("Client is already configured with an authentication token. Manual authentication is not required."));
            return future;
        }
        Objects.requireNonNull(username, "Username cannot be null");
        Objects.requireNonNull(password, "Password cannot be null");

        Map<String, String> params = new HashMap<>();
        params.put("user", username);
        params.put("password", password);

        return postRequestAsync("user.login", params)
                .thenApply(jsonNode -> {
                    if (jsonNode != null && jsonNode.isTextual()) {
                        this.authToken = jsonNode.asText();
                        return this.authToken;
                    } else {
                        throw new CompletionException(new ZabbixApiException("Authentication failed: Unexpected response format or missing token. Response: " + (jsonNode != null ? jsonNode.toString() : "null")));
                    }
                });
    }

    /**
     * Retrieves information about a host asynchronously by its name.
     * Requires prior successful authentication.
     *
     * @param hostName The name of the host to retrieve. Cannot be null.
     * @return A {@link CompletableFuture} that will be completed with a {@link JsonNode}
     *         representing the host information if found, or {@code null} if not found.
     *         The future completes exceptionally if the client is not authenticated or if an API/network error occurs.
     */
    public CompletableFuture<JsonNode> getHostAsync(String hostName) {
        Objects.requireNonNull(hostName, "Host name cannot be null");
        if (this.authToken == null) {
            CompletableFuture<JsonNode> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(new IllegalStateException("Client is not authenticated. Call authenticateAsync() first."));
            return failedFuture;
        }

        Map<String, Object> params = new HashMap<>();
        Map<String, String> filter = new HashMap<>();
        filter.put("host", hostName);
        params.put("filter", filter);
        params.put("output", "extend"); // Request all available fields

        return postRequestAsync("host.get", params)
                .thenApply(result -> {
                    if (result != null && result.isArray() && result.size() > 0) {
                        return result.get(0); // Return the first host found
                    }
                    return null; // Host not found or empty result
                });
    }

    /**
     * Retrieves information about an item asynchronously by its name and the host name it belongs to.
     * Requires prior successful authentication.
     *
     * @param itemName The name of the item to retrieve. Cannot be null.
     * @param hostName The name of the host the item belongs to. Cannot be null.
     * @return A {@link CompletableFuture} that will be completed with a {@link JsonNode}
     *         representing the item information if found, or {@code null} if not found or if the host is not found.
     *         The future completes exceptionally if the client is not authenticated or if an API/network error occurs
     *         during host or item retrieval.
     */
    public CompletableFuture<JsonNode> getItemAsync(String itemName, String hostName) {
        Objects.requireNonNull(itemName, "Item name cannot be null");
        Objects.requireNonNull(hostName, "Host name cannot be null");

        if (this.authToken == null) {
            CompletableFuture<JsonNode> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(new IllegalStateException("Client is not authenticated. Call authenticateAsync() first."));
            return failedFuture;
        }

        return getHostAsync(hostName).thenComposeAsync(hostNode -> {
            if (hostNode == null || !hostNode.has("hostid")) {
                // Host not found or hostid is missing, complete with null for the item
                return CompletableFuture.completedFuture(null);
            }
            String hostId = hostNode.get("hostid").asText();

            Map<String, Object> itemParams = new HashMap<>();
            itemParams.put("hostids", hostId);
            Map<String, String> search = new HashMap<>();
            search.put("name", itemName); // Search by item name
            itemParams.put("search", search);
            itemParams.put("output", "extend"); // Request all available fields

            return postRequestAsync("item.get", itemParams);
        }).thenApply(result -> {
            if (result != null && result.isArray() && result.size() > 0) {
                return result.get(0); // Return the first item found
            }
            return null; // Item not found or empty result
        });
    }


    /**
     * Sends a generic asynchronous POST request to the Zabbix API.
     *
     * @param method The Zabbix API method (e.g., "host.get", "user.login"). Cannot be null.
     * @param params The parameters for the API method. Cannot be null.
     * @return A {@link CompletableFuture} that will be completed with the "result" field
     *         from the Zabbix API response as a {@link JsonNode}.
     *         The future will be completed exceptionally if a network error occurs,
     *         the API returns an HTTP error, or the Zabbix API response contains an error object.
     */
    private CompletableFuture<JsonNode> postRequestAsync(String method, Object params) {
        Objects.requireNonNull(method, "Zabbix API method cannot be null");
        Objects.requireNonNull(params, "Zabbix API params cannot be null");

        CompletableFuture<JsonNode> future = new CompletableFuture<>();

        // Use the class-level objectMapper
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("jsonrpc", "2.0");
        requestBody.put("method", method);
        requestBody.set("params", objectMapper.valueToTree(params));
        requestBody.put("id", System.currentTimeMillis()); // ID is already dynamic

        // authToken is used here, which is set either by authenticateAsync() or by the token-based constructor
        String currentAuthToken = this.authToken;
        if (currentAuthToken != null && !method.equals("user.login")) {
            requestBody.put("auth", currentAuthToken);
        }

        RequestBody body = RequestBody.create(
                requestBody.toString(),
                MediaType.get("application/json-rpc")
        );

        Request request = new Request.Builder()
                .url(this.apiUrl)
                .post(body)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                future.completeExceptionally(e);
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (ResponseBody responseBody = response.body()) {
                    if (!response.isSuccessful()) {
                        future.completeExceptionally(new IOException("Unexpected HTTP code " + response.code() + " - " + response.message() + " - " + (responseBody != null ? responseBody.string() : "")));
                        return;
                    }
                    if (responseBody == null) {
                        future.completeExceptionally(new IOException("Empty response body received from Zabbix API."));
                        return;
                    }

                    String responseBodyString = responseBody.string();
                    JsonNode responseJson = objectMapper.readTree(responseBodyString);

                    if (responseJson.has("error")) {
                        JsonNode errorNode = responseJson.get("error");
                        future.completeExceptionally(new ZabbixApiException("Zabbix API Error: " +
                                errorNode.get("message").asText() +
                                " | Code: " + errorNode.get("code").asInt() +
                                " | Data: " + errorNode.get("data").asText()));
                    } else if (responseJson.has("result")) {
                        future.complete(responseJson.get("result"));
                    } else {
                        future.completeExceptionally(new ZabbixApiException("Invalid Zabbix API response: Missing 'result' or 'error' field. Response: " + responseBodyString));
                    }
                } catch (IOException e) {
                    future.completeExceptionally(e);
                }
            }
        });
        return future;
    }
}

// Custom exception for Zabbix API specific errors
class ZabbixApiException extends IOException {
    public ZabbixApiException(String message) {
        super(message);
    }
}
