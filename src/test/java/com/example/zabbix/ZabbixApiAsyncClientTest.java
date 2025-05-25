package com.example.zabbix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ZabbixApiAsyncClientTest {

    private static final String TEST_API_URL = "http://fake-zabbix-async/api_jsonrpc.php";
    private ZabbixApiAsyncClient client;

    @Mock
    private OkHttpClient mockHttpClient;
    @Mock
    private Call mockCall;
    // Response and ResponseBody are not directly mocked here as enqueue uses a Callback.

    @Captor
    private ArgumentCaptor<Request> requestCaptor;
    @Captor
    private ArgumentCaptor<Callback> callbackCaptor;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        client = new ZabbixApiAsyncClient(TEST_API_URL);
        // Use reflection to inject the mock OkHttpClient
        try {
            java.lang.reflect.Field httpClientField = ZabbixApiAsyncClient.class.getDeclaredField("httpClient");
            httpClientField.setAccessible(true);
            httpClientField.set(client, mockHttpClient);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("Failed to inject mock OkHttpClient into ZabbixApiAsyncClient", e);
        }

        // Standard mock behavior for httpClient.newCall()
    // This will be called for each test that initializes the client in setUp.
    // For tests that create their own client instance (token-based), they'll need their own mock setup if client is not injected.
    // The reflection injects into the 'client' field instance.
    lenient().when(mockHttpClient.newCall(any(Request.class))).thenReturn(mockCall);
}

private void injectMockHttpClient(ZabbixApiAsyncClient clientInstance) {
    try {
        java.lang.reflect.Field httpClientField = ZabbixApiAsyncClient.class.getDeclaredField("httpClient");
        httpClientField.setAccessible(true);
        httpClientField.set(clientInstance, mockHttpClient);
    } catch (NoSuchFieldException | IllegalAccessException e) {
        throw new RuntimeException("Failed to inject mock OkHttpClient into ZabbixApiAsyncClient", e);
    }
    }

    private void mockCallbackSuccess(String jsonResponse) {
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                Callback callback = invocation.getArgument(0, Callback.class);
                Response mockResponse = mock(Response.class);
                ResponseBody mockResponseBody = mock(ResponseBody.class);

                when(mockResponse.isSuccessful()).thenReturn(true);
                when(mockResponse.body()).thenReturn(mockResponseBody);
                when(mockResponseBody.string()).thenReturn(jsonResponse);

                // Need to ensure the Call object passed to onResponse is the same mockCall
                callback.onResponse(mockCall, mockResponse);
                return null;
            }
        }).when(mockCall).enqueue(any(Callback.class));
    }

    private void mockCallbackApiError(String jsonErrorResponse) {
         doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                Callback callback = invocation.getArgument(0, Callback.class);
                Response mockResponse = mock(Response.class);
                ResponseBody mockResponseBody = mock(ResponseBody.class);

                when(mockResponse.isSuccessful()).thenReturn(true); // HTTP is fine
                when(mockResponse.body()).thenReturn(mockResponseBody);
                when(mockResponseBody.string()).thenReturn(jsonErrorResponse); // But Zabbix returns an error object

                callback.onResponse(mockCall, mockResponse);
                return null;
            }
        }).when(mockCall).enqueue(any(Callback.class));
    }
    
    private void mockCallbackHttpError(int code, String message) {
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                Callback callback = invocation.getArgument(0, Callback.class);
                Response mockResponse = mock(Response.class);
                ResponseBody mockResponseBody = mock(ResponseBody.class); // Optional: can be null for some errors

                when(mockResponse.isSuccessful()).thenReturn(false);
                when(mockResponse.code()).thenReturn(code);
                when(mockResponse.message()).thenReturn(message);
                if (mockResponseBody != null) { // Some HTTP errors might have bodies
                    when(mockResponse.body()).thenReturn(mockResponseBody);
                    when(mockResponseBody.string()).thenReturn("{\"error\":\"HTTP error occurred\"}");
                } else {
                    when(mockResponse.body()).thenReturn(null);
                }


                callback.onResponse(mockCall, mockResponse);
                return null;
            }
        }).when(mockCall).enqueue(any(Callback.class));
    }

    private void mockCallbackNetworkFailure(IOException exceptionToThrow) {
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                Callback callback = invocation.getArgument(0, Callback.class);
                callback.onFailure(mockCall, exceptionToThrow);
                return null;
            }
        }).when(mockCall).enqueue(any(Callback.class));
    }


    @Test
    void authenticateAsync_success() throws Exception {
        String authToken = "asyncAuthToken789";
        String responseJson = "{\"jsonrpc\":\"2.0\",\"result\":\"" + authToken + "\",\"id\":1}";
        mockCallbackSuccess(responseJson);

        CompletableFuture<String> authFuture = client.authenticateAsync("asyncUser", "asyncPass");
        String resultToken = authFuture.get(5, TimeUnit.SECONDS); // Wait for completion

        assertEquals(authToken, resultToken);
        verify(mockHttpClient).newCall(requestCaptor.capture());
        Request capturedRequest = requestCaptor.getValue();
        assertEquals(TEST_API_URL, capturedRequest.url().toString());
        // Further request body inspection can be done similar to sync tests if needed
    }

    @Test
    void authenticateAsync_apiError() {
        String errorResponseJson = "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32602,\"message\":\"Invalid params.\",\"data\":\"Async invalid login\"},\"id\":1}";
        mockCallbackApiError(errorResponseJson);

        CompletableFuture<String> authFuture = client.authenticateAsync("user", "wrongpass");

        ExecutionException exception = assertThrows(ExecutionException.class, () -> authFuture.get(5, TimeUnit.SECONDS));
        Throwable cause = exception.getCause();
        assertTrue(cause instanceof ZabbixApiException); // Custom exception defined in ZabbixApiAsyncClient
        assertTrue(cause.getMessage().contains("Async invalid login"));
    }
    
    @Test
    void authenticateAsync_httpError() {
        mockCallbackHttpError(503, "Service Unavailable");
        CompletableFuture<String> authFuture = client.authenticateAsync("user", "pass");

        ExecutionException ex = assertThrows(ExecutionException.class, () -> authFuture.get(5, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof IOException);
        assertTrue(ex.getCause().getMessage().contains("Unexpected HTTP code 503"));
    }


    @Test
    void authenticateAsync_networkError() {
        IOException networkException = new IOException("Async network connection failed");
        mockCallbackNetworkFailure(networkException);

        CompletableFuture<String> authFuture = client.authenticateAsync("user", "pass");

        ExecutionException exception = assertThrows(ExecutionException.class, () -> authFuture.get(5, TimeUnit.SECONDS));
        Throwable cause = exception.getCause();
        assertTrue(cause instanceof IOException);
        assertEquals("Async network connection failed", cause.getMessage());
    }

    @Test
    void getHostAsync_success() throws Exception {
        // Step 1: Authenticate successfully
        String authToken = "hostAsyncAuthToken";
        String authResponseJson = "{\"jsonrpc\":\"2.0\",\"result\":\"" + authToken + "\",\"id\":1}";

        // Step 2: Mock response for getHostAsync
        String hostName = "AsyncZabbixServer";
        String hostId = "async10084";
        String hostResponseJson = "{\"jsonrpc\":\"2.0\",\"result\":[{\"hostid\":\"" + hostId + "\",\"host\":\"" + hostName + "\"}],\"id\":2}";

        // Capture enqueue calls and provide responses sequentially
        doAnswer(new Answer<Void>() { // For auth call
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                Callback callback = invocation.getArgument(0, Callback.class);
                Response mockResp = mock(Response.class);
                ResponseBody mockRespBody = mock(ResponseBody.class);
                when(mockResp.isSuccessful()).thenReturn(true);
                when(mockResp.body()).thenReturn(mockRespBody);
                when(mockRespBody.string()).thenReturn(authResponseJson);
                callback.onResponse(mockCall, mockResp);
                return null;
            }
        }).doAnswer(new Answer<Void>() { // For getHost call
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                Callback callback = invocation.getArgument(0, Callback.class);
                Response mockResp = mock(Response.class);
                ResponseBody mockRespBody = mock(ResponseBody.class);
                when(mockResp.isSuccessful()).thenReturn(true);
                when(mockResp.body()).thenReturn(mockRespBody);
                when(mockRespBody.string()).thenReturn(hostResponseJson);
                callback.onResponse(mockCall, mockResp);
                return null;
            }
        }).when(mockCall).enqueue(any(Callback.class));


        client.authenticateAsync("user", "pass").get(5, TimeUnit.SECONDS); // Ensure auth completes

        CompletableFuture<JsonNode> hostFuture = client.getHostAsync(hostName);
        JsonNode hostNode = hostFuture.get(5, TimeUnit.SECONDS);

        assertNotNull(hostNode);
        assertEquals(hostName, hostNode.get("host").asText());
        assertEquals(hostId, hostNode.get("hostid").asText());

        verify(mockHttpClient, times(2)).newCall(any(Request.class));
    }
    
    @Test
    void getHostAsync_notFound() throws Exception {
        String authToken = "hostNotFoundToken";
        String authResponseJson = "{\"jsonrpc\":\"2.0\",\"result\":\"" + authToken + "\",\"id\":1}";
        String hostNotFoundJson = "{\"jsonrpc\":\"2.0\",\"result\":[],\"id\":2}";

        doAnswer(inv -> { // Auth
            Callback cb = inv.getArgument(0);
            Response r = mock(Response.class); ResponseBody rb = mock(ResponseBody.class);
            when(r.isSuccessful()).thenReturn(true); when(r.body()).thenReturn(rb); when(rb.string()).thenReturn(authResponseJson);
            cb.onResponse(mockCall, r); return null;
        }).doAnswer(inv -> { // getHost
            Callback cb = inv.getArgument(0);
            Response r = mock(Response.class); ResponseBody rb = mock(ResponseBody.class);
            when(r.isSuccessful()).thenReturn(true); when(r.body()).thenReturn(rb); when(rb.string()).thenReturn(hostNotFoundJson);
            cb.onResponse(mockCall, r); return null;
        }).when(mockCall).enqueue(any(Callback.class));
        
        client.authenticateAsync("user", "pass").get();
        
        JsonNode host = client.getHostAsync("NonExistentAsyncHost").get();
        assertNull(host);
    }


    @Test
    void getHostAsync_notAuthenticated() {
        CompletableFuture<JsonNode> hostFuture = client.getHostAsync("AnyHost");

        ExecutionException exception = assertThrows(ExecutionException.class, () -> hostFuture.get(5, TimeUnit.SECONDS));
        Throwable cause = exception.getCause();
        assertTrue(cause instanceof IllegalStateException);
        assertEquals("Client is not authenticated. Call authenticateAsync() first.", cause.getMessage());
        verify(mockHttpClient, never()).newCall(any()); // No HTTP call should be made
    }

    @Test
    void getItemAsync_success() throws Exception {
        String authToken = "itemAsyncAuth";
        String hostName = "AsyncLinux";
        String hostId = "asyncH1";
        String itemName = "async.cpu.load";
        String itemId = "asyncI1";

        String authResp = "{\"jsonrpc\":\"2.0\",\"result\":\"" + authToken + "\",\"id\":1}";
        String hostResp = "{\"jsonrpc\":\"2.0\",\"result\":[{\"hostid\":\"" + hostId + "\",\"host\":\"" + hostName + "\"}],\"id\":2}";
        String itemResp = "{\"jsonrpc\":\"2.0\",\"result\":[{\"itemid\":\"" + itemId + "\",\"name\":\"" + itemName + "\"}],\"id\":3}";

        doAnswer(inv -> { // Auth
            ((Callback)inv.getArgument(0)).onResponse(mockCall, successfulResponseMock(authResp)); return null;
        }).doAnswer(inv -> { // getHost in getItem
            ((Callback)inv.getArgument(0)).onResponse(mockCall, successfulResponseMock(hostResp)); return null;
        }).doAnswer(inv -> { // item.get in getItem
            ((Callback)inv.getArgument(0)).onResponse(mockCall, successfulResponseMock(itemResp)); return null;
        }).when(mockCall).enqueue(any(Callback.class));
        
        client.authenticateAsync("user", "pass").get(); // Complete auth

        CompletableFuture<JsonNode> itemFuture = client.getItemAsync(itemName, hostName);
        JsonNode itemNode = itemFuture.get(5, TimeUnit.SECONDS);

        assertNotNull(itemNode);
        assertEquals(itemName, itemNode.get("name").asText());
        assertEquals(itemId, itemNode.get("itemid").asText());

        verify(mockHttpClient, times(3)).newCall(any(Request.class)); // auth, host.get, item.get
    }
    
    private Response successfulResponseMock(String jsonBody) throws IOException {
        Response r = mock(Response.class);
        ResponseBody rb = mock(ResponseBody.class);
        when(r.isSuccessful()).thenReturn(true);
        when(r.body()).thenReturn(rb);
        when(rb.string()).thenReturn(jsonBody);
        return r;
    }

    @Test
    void getItemAsync_hostNotFound() throws Exception {
        String authToken = "itemHostNotFoundToken";
        String authResp = "{\"jsonrpc\":\"2.0\",\"result\":\"" + authToken + "\",\"id\":1}";
        String hostNotFoundResp = "{\"jsonrpc\":\"2.0\",\"result\":[],\"id\":2}"; // getHost returns empty

        doAnswer(inv -> { // Auth
            ((Callback)inv.getArgument(0)).onResponse(mockCall, successfulResponseMock(authResp)); return null;
        }).doAnswer(inv -> { // getHost in getItem
            ((Callback)inv.getArgument(0)).onResponse(mockCall, successfulResponseMock(hostNotFoundResp)); return null;
        }).when(mockCall).enqueue(any(Callback.class));

        client.authenticateAsync("user", "pass").get();
        JsonNode item = client.getItemAsync("any.item", "UnknownAsyncHost").get();
        assertNull(item);
        verify(mockHttpClient, times(2)).newCall(any()); // auth, host.get
    }
    
    @Test
    void getItemAsync_itemNotFound() throws Exception {
        String authToken = "itemNotFoundToken";
        String hostName = "KnownAsyncServer";
        String hostId = "asyncH2";

        String authResp = "{\"jsonrpc\":\"2.0\",\"result\":\"" + authToken + "\",\"id\":1}";
        String hostResp = "{\"jsonrpc\":\"2.0\",\"result\":[{\"hostid\":\"" + hostId + "\",\"host\":\"" + hostName + "\"}],\"id\":2}";
        String itemNotFoundResp = "{\"jsonrpc\":\"2.0\",\"result\":[],\"id\":3}"; // item.get returns empty


        doAnswer(inv -> { // Auth
            ((Callback)inv.getArgument(0)).onResponse(mockCall, successfulResponseMock(authResp)); return null;
        }).doAnswer(inv -> { // getHost in getItem
            ((Callback)inv.getArgument(0)).onResponse(mockCall, successfulResponseMock(hostResp)); return null;
        }).doAnswer(inv -> { // item.get in getItem
            ((Callback)inv.getArgument(0)).onResponse(mockCall, successfulResponseMock(itemNotFoundResp)); return null;
        }).when(mockCall).enqueue(any(Callback.class));

        client.authenticateAsync("user", "pass").get();
        JsonNode item = client.getItemAsync("non.existent.async.key", hostName).get();
        assertNull(item);
        verify(mockHttpClient, times(3)).newCall(any()); // auth, host.get, item.get
    }


    @Test
    void getItemAsync_notAuthenticated() {
        CompletableFuture<JsonNode> itemFuture = client.getItemAsync("any.item", "any.host");
        ExecutionException ex = assertThrows(ExecutionException.class, () -> itemFuture.get(5, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof IllegalStateException);
        assertEquals("Client is not authenticated. Call authenticateAsync() first.", ex.getCause().getMessage());
        verify(mockHttpClient, never()).newCall(any());
    }

    // --- Token-based Authentication Tests ---

    @Test
    void apiCallWithPreConfiguredToken_successAsync() throws Exception {
        String testToken = "preConfiguredAsyncToken456";
        ZabbixApiAsyncClient tokenClient = new ZabbixApiAsyncClient(TEST_API_URL, testToken);
        // We need a new OkHttpClient mock instance for this specific client or re-use the class-level one carefully.
        // For simplicity, let's assume we can inject/re-target the existing mockHttpClient.
        injectMockHttpClient(tokenClient); // Inject mock into the new client instance

        String hostName = "AsyncTokenHost";
        String hostId = "asyncT101";
        String hostResponseJson = "{\"jsonrpc\":\"2.0\",\"result\":[{\"hostid\":\"" + hostId + "\",\"host\":\"" + hostName + "\"}],\"id\":1}";

        // Mock the callback for the getHostAsync call
        doAnswer(invocation -> {
            Callback callback = invocation.getArgument(0, Callback.class);
            // Verify the request body for the token
            Request capturedRequest = requestCaptor.getValue(); // Capture from mockHttpClient.newCall
            okio.Buffer buffer = new okio.Buffer();
            assertNotNull(capturedRequest.body());
            capturedRequest.body().writeTo(buffer);
            String requestBodyString = buffer.readUtf8();
            JsonNode requestJsonNode = objectMapper.readTree(requestBodyString);

            assertEquals("host.get", requestJsonNode.get("method").asText());
            assertEquals(testToken, requestJsonNode.get("auth").asText()); // Key verification
            assertEquals(hostName, requestJsonNode.get("params").get("filter").get("host").asText());
            
            // Simulate successful response
            Response mockHttpResponse = successfulResponseMock(hostResponseJson);
            callback.onResponse(mockCall, mockHttpResponse);
            return null;
        }).when(mockCall).enqueue(any(Callback.class));

        // This will trigger the actual mockHttpClient.newCall, need to capture request there.
        // The actual newCall is on the mockHttpClient instance associated with 'tokenClient'.
        // The requestCaptor is on 'this.mockHttpClient'. So, ensure they are the same.
        when(this.mockHttpClient.newCall(requestCaptor.capture())).thenReturn(mockCall);


        CompletableFuture<JsonNode> hostFuture = tokenClient.getHostAsync(hostName);
        JsonNode hostNode = hostFuture.get(5, TimeUnit.SECONDS);

        assertNotNull(hostNode);
        assertEquals(hostName, hostNode.get("host").asText());
        assertEquals(hostId, hostNode.get("hostid").asText());

        verify(this.mockHttpClient, times(1)).newCall(any(Request.class)); // Only one call for getHost
        verify(mockCall, times(1)).enqueue(any(Callback.class));
    }

    @Test
    void authenticateAsync_completesExceptionally_whenTokenPreConfigured() {
        String testToken = "anotherAsyncToken789";
        ZabbixApiAsyncClient tokenClient = new ZabbixApiAsyncClient(TEST_API_URL, testToken);
        // No HTTP call expected, so no need to inject mockHttpClient or mock calls for it.

        CompletableFuture<String> authFuture = tokenClient.authenticateAsync("user", "pass");

        ExecutionException exception = assertThrows(ExecutionException.class,
                () -> authFuture.get(5, TimeUnit.SECONDS));

        Throwable cause = exception.getCause();
        assertTrue(cause instanceof IllegalStateException);
        assertEquals("Client is already configured with an authentication token. Manual authentication is not required.", cause.getMessage());
        
        // Verify that no interactions happened with the class-level mockHttpClient
        // if tokenClient created its own (which it does, but we mock it for other tests).
        // If tokenClient was using the class-level mock, then verifyNoInteractions(this.mockHttpClient)
        // For this test, it's safer to assume it might have its own, and the important part is the exception.
        // If we are sure 'tokenClient' does not use 'this.mockHttpClient' unless injected, then this is fine.
        // Given our injectMockHttpClient, if we *don't* call it on tokenClient, its internal httpClient is real.
        // If we *do* call it, then we can verifyNoInteractions on the mock.
        // Let's assume we don't inject for this specific test, as no HTTP interaction should occur.
        // verifyNoInteractions(this.mockHttpClient); // This would be for the class field client, not tokenClient's own.
    }
}
