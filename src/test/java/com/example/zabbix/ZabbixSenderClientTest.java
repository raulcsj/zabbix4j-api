package com.example.zabbix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ZabbixSenderClientTest {

    private static final String TEST_SERVER_ADDRESS = "localhost";
    private static final int TEST_SERVER_PORT = 10051;
    private ZabbixSenderClient client;

    @Mock
    private Socket mockSocket;
    @Mock
    private OutputStream mockOutputStream;
    @Mock
    private InputStream mockInputStream;

    @Captor
    private ArgumentCaptor<byte[]> payloadCaptor;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        client = new ZabbixSenderClient(TEST_SERVER_ADDRESS, TEST_SERVER_PORT);

        // Mock socket behavior for most tests
        // Individual tests can override this if they need to test socket creation failure
        lenient().when(mockSocket.getOutputStream()).thenReturn(mockOutputStream);
        lenient().when(mockSocket.getInputStream()).thenReturn(mockInputStream);
    }
    
    // Helper to create a Zabbix Sender protocol compliant response
    private byte[] createZabbixResponse(String jsonResponse) {
        byte[] jsonData = jsonResponse.getBytes(StandardCharsets.UTF_8);
        ByteBuffer responseBuffer = ByteBuffer.allocate(13 + jsonData.length); // Header (5) + Length (8) + Data
        responseBuffer.order(ByteOrder.LITTLE_ENDIAN);
        responseBuffer.put("ZBXD".getBytes(StandardCharsets.US_ASCII));
        responseBuffer.put((byte) 0x01);
        responseBuffer.putLong(jsonData.length);
        responseBuffer.put(jsonData);
        return responseBuffer.array();
    }

    @Test
    void sendSingleDataItem_success() throws IOException {
        DataItem item = new DataItem("host1", "key1", "value1");
        String successResponseJson = "{\"response\":\"success\",\"info\":\"processed: 1; failed: 0; total: 1; seconds spent: 0.000123\"}";
        byte[] successResponseBytes = createZabbixResponse(successResponseJson);
        when(mockInputStream.read(any(byte[].class), anyInt(), anyInt()))
            .thenAnswer(invocation -> {
                byte[] buffer = invocation.getArgument(0);
                System.arraycopy(successResponseBytes, 0, buffer, 0, successResponseBytes.length);
                return successResponseBytes.length;
            })
            .thenReturn(-1); // End of stream for subsequent reads if any

        // This is the tricky part: ZabbixSenderClient creates a *new* Socket.
        // We need to mock the Socket constructor or use a factory pattern in the client.
        // For this test, we'll use Mockito's static mocking for the Socket constructor.
        try (MockedStatic<Socket> socketMockedStatic = mockStatic(Socket.class,withSettings().defaultAnswer(Answers.RETURNS_DEFAULTS))) {
             socketMockedStatic.when(() -> new Socket(TEST_SERVER_ADDRESS, TEST_SERVER_PORT)).thenReturn(mockSocket);

            JsonNode response = client.send(item);

            assertNotNull(response);
            assertEquals("success", response.get("response").asText());
            assertTrue(response.get("info").asText().contains("processed: 1"));

            verify(mockOutputStream).write(payloadCaptor.capture());
            byte[] capturedPayload = payloadCaptor.getValue();

            // Verify header "ZBXD\1"
            assertEquals('Z', capturedPayload[0]);
            assertEquals('B', capturedPayload[1]);
            assertEquals('X', capturedPayload[2]);
            assertEquals('D', capturedPayload[3]);
            assertEquals(0x01, capturedPayload[4]);

            // Verify data length (little-endian)
            ByteBuffer buffer = ByteBuffer.wrap(capturedPayload, 5, 8);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            long jsonDataLength = buffer.getLong();

            // Verify JSON data part
            String jsonDataString = new String(capturedPayload, 13, (int) jsonDataLength, StandardCharsets.UTF_8);
            JsonNode requestJson = objectMapper.readTree(jsonDataString);
            assertEquals("sender data", requestJson.get("request").asText());
            assertTrue(requestJson.get("data").isArray());
            assertEquals(1, requestJson.get("data").size());
            assertEquals("host1", requestJson.get("data").get(0).get("host").asText());
            assertEquals("key1", requestJson.get("data").get(0).get("key").asText());
            assertEquals("value1", requestJson.get("data").get(0).get("value").asText());
        }
    }

    @Test
    void sendListOfDataItems_success() throws IOException {
        List<DataItem> items = Arrays.asList(
                new DataItem("hostA", "keyA", "valA").setClock(1000L),
                new DataItem("hostB", "keyB", "valB").setNs(500) // ns without clock is valid for DataItem but Zabbix might ignore ns
        );
        String successResponseJson = "{\"response\":\"success\",\"info\":\"processed: 2; failed: 0; total: 2; seconds spent: 0.0002\"}";
        byte[] successResponseBytes = createZabbixResponse(successResponseJson);

        when(mockInputStream.read(any(byte[].class), anyInt(), anyInt()))
            .thenAnswer(inv -> {
                byte[] buf = inv.getArgument(0);
                System.arraycopy(successResponseBytes, 0, buf, 0, successResponseBytes.length);
                return successResponseBytes.length;
            }).thenReturn(-1);
        
        try (MockedStatic<Socket> mocked = mockStatic(Socket.class, withSettings().defaultAnswer(Answers.RETURNS_DEFAULTS))) {
            mocked.when(() -> new Socket(TEST_SERVER_ADDRESS, TEST_SERVER_PORT)).thenReturn(mockSocket);

            JsonNode response = client.send(items);
            assertEquals("success", response.get("response").asText());
            assertTrue(response.get("info").asText().contains("processed: 2"));

            verify(mockOutputStream).write(payloadCaptor.capture());
            byte[] captured = payloadCaptor.getValue();
            ByteBuffer bb = ByteBuffer.wrap(captured, 5, 8);
            bb.order(ByteOrder.LITTLE_ENDIAN);
            String jsonData = new String(captured, 13, (int)bb.getLong(), StandardCharsets.UTF_8);
            JsonNode sentJson = objectMapper.readTree(jsonData);
            assertEquals(2, sentJson.get("data").size());
            assertEquals(1000L, sentJson.get("data").get(0).get("clock").asLong());
            assertNotNull(sentJson.get("data").get(1).get("ns")); // Jackson includes it due to @JsonInclude NON_NULL
        }
    }

    @Test
    void sendWithTopLevelClockAndNs_success() throws IOException {
        DataItem item = new DataItem("hostC", "keyC", "valC");
        long testClock = System.currentTimeMillis() / 1000L;
        int testNs = 123456789;

        String successResponseJson = "{\"response\":\"success\",\"info\":\"processed: 1; failed: 0; total: 1\"}";
        byte[] successResponseBytes = createZabbixResponse(successResponseJson);
        when(mockInputStream.read(any(byte[].class), anyInt(), anyInt()))
            .thenAnswer(inv -> {
                byte[] buf = inv.getArgument(0);
                System.arraycopy(successResponseBytes, 0, buf, 0, successResponseBytes.length);
                return successResponseBytes.length;
            }).thenReturn(-1);

        try (MockedStatic<Socket> mocked = mockStatic(Socket.class, withSettings().defaultAnswer(Answers.RETURNS_DEFAULTS))) {
            mocked.when(() -> new Socket(TEST_SERVER_ADDRESS, TEST_SERVER_PORT)).thenReturn(mockSocket);

            client.send(Collections.singletonList(item), testClock, testNs);

            verify(mockOutputStream).write(payloadCaptor.capture());
            byte[] captured = payloadCaptor.getValue();
            ByteBuffer bb = ByteBuffer.wrap(captured, 5, 8);
            bb.order(ByteOrder.LITTLE_ENDIAN);
            String jsonData = new String(captured, 13, (int)bb.getLong(), StandardCharsets.UTF_8);
            JsonNode sentJson = objectMapper.readTree(jsonData);
            
            assertEquals(testClock, sentJson.get("clock").asLong());
            assertEquals(testNs, sentJson.get("ns").asInt());
            assertEquals(1, sentJson.get("data").size());
        }
    }
    
    @Test
    void send_zabbixErrorResponse() throws IOException {
        DataItem item = new DataItem("hostErr", "keyErr", "valErr");
        String errorResponseJson = "{\"response\":\"error\",\"info\":\"processed: 0; failed: 1; total: 1; error: some error from zabbix\"}";
        byte[] errorResponseBytes = createZabbixResponse(errorResponseJson);
         when(mockInputStream.read(any(byte[].class), anyInt(), anyInt()))
            .thenAnswer(inv -> {
                byte[] buf = inv.getArgument(0);
                System.arraycopy(errorResponseBytes, 0, buf, 0, errorResponseBytes.length);
                return errorResponseBytes.length;
            }).thenReturn(-1);

        try (MockedStatic<Socket> mocked = mockStatic(Socket.class, withSettings().defaultAnswer(Answers.RETURNS_DEFAULTS))) {
            mocked.when(() -> new Socket(TEST_SERVER_ADDRESS, TEST_SERVER_PORT)).thenReturn(mockSocket);
            
            IOException exception = assertThrows(IOException.class, () -> client.send(item));
            assertTrue(exception.getMessage().contains("Zabbix Sender reported an error"));
            assertTrue(exception.getMessage().contains("processed: 0; failed: 1"));
        }
    }

    @Test
    void send_ioExceptionOnSocketCreation() throws IOException {
         try (MockedStatic<Socket> mocked = mockStatic(Socket.class, withSettings().defaultAnswer(Answers.RETURNS_DEFAULTS))) {
            mocked.when(() -> new Socket(TEST_SERVER_ADDRESS, TEST_SERVER_PORT)).thenThrow(new IOException("Cannot connect"));
            
            DataItem item = new DataItem("hostNetErr", "keyNetErr", "valNetErr");
            IOException exception = assertThrows(IOException.class, () -> client.send(item));
            assertEquals("Cannot connect", exception.getMessage());
        }
    }

    @Test
    void send_ioExceptionOnGetOutputStream() throws IOException {
        try (MockedStatic<Socket> mocked = mockStatic(Socket.class, withSettings().defaultAnswer(Answers.RETURNS_DEFAULTS))) {
            mocked.when(() -> new Socket(TEST_SERVER_ADDRESS, TEST_SERVER_PORT)).thenReturn(mockSocket);
            when(mockSocket.getOutputStream()).thenThrow(new IOException("OutputStream failed"));
            
            DataItem item = new DataItem("hostOutErr", "keyOutErr", "valOutErr");
            IOException exception = assertThrows(IOException.class, () -> client.send(item));
            assertEquals("OutputStream failed", exception.getMessage());
        }
    }
    
    @Test
    void send_ioExceptionOnWrite() throws IOException {
        try (MockedStatic<Socket> mocked = mockStatic(Socket.class, withSettings().defaultAnswer(Answers.RETURNS_DEFAULTS))) {
            mocked.when(() -> new Socket(TEST_SERVER_ADDRESS, TEST_SERVER_PORT)).thenReturn(mockSocket);
            doThrow(new IOException("Write failed")).when(mockOutputStream).write(any(byte[].class));
            
            DataItem item = new DataItem("hostWriteErr", "keyWriteErr", "valWriteErr");
            IOException exception = assertThrows(IOException.class, () -> client.send(item));
            assertEquals("Write failed", exception.getMessage());
        }
    }

    @Test
    void send_ioExceptionOnReadResponseHeader() throws IOException {
         try (MockedStatic<Socket> mocked = mockStatic(Socket.class, withSettings().defaultAnswer(Answers.RETURNS_DEFAULTS))) {
            mocked.when(() -> new Socket(TEST_SERVER_ADDRESS, TEST_SERVER_PORT)).thenReturn(mockSocket);
            when(mockInputStream.read(any(byte[].class), anyInt(), anyInt())).thenThrow(new IOException("Read header failed"));
            
            DataItem item = new DataItem("hostReadHeadErr", "keyReadHeadErr", "valReadHeadErr");
            IOException exception = assertThrows(IOException.class, () -> client.send(item));
            assertEquals("Read header failed", exception.getMessage());
        }
    }
    
    @Test
    void send_incompleteResponseHeader() throws IOException {
        // Simulate reading only part of the header
        byte[] partialHeader = Arrays.copyOf("ZBXD\1".getBytes(StandardCharsets.US_ASCII), 10); // Only 10 bytes of 13
        when(mockInputStream.read(any(byte[].class), anyInt(), anyInt()))
            .thenAnswer(inv -> {
                byte[] buf = inv.getArgument(0);
                System.arraycopy(partialHeader, 0, buf, 0, partialHeader.length);
                return partialHeader.length; // Report that only 10 bytes were read
            }).thenReturn(-1); // EOF

        try (MockedStatic<Socket> mocked = mockStatic(Socket.class, withSettings().defaultAnswer(Answers.RETURNS_DEFAULTS))) {
            mocked.when(() -> new Socket(TEST_SERVER_ADDRESS, TEST_SERVER_PORT)).thenReturn(mockSocket);
            
            DataItem item = new DataItem("hostPartHead", "keyPartHead", "valPartHead");
            IOException exception = assertThrows(IOException.class, () -> client.send(item));
            assertTrue(exception.getMessage().contains("Failed to read complete Zabbix Sender response header"));
        }
    }
    
    @Test
    void send_invalidResponseHeaderMagic() throws IOException {
        byte[] badHeader = "XXXX\1".getBytes(StandardCharsets.US_ASCII); // Invalid magic
        byte[] fullBadHeaderResponse = createZabbixResponse("{\"response\":\"success\"}");
        System.arraycopy(badHeader, 0, fullBadHeaderResponse, 0, badHeader.length); // Corrupt the header

        when(mockInputStream.read(any(byte[].class), anyInt(), anyInt()))
            .thenAnswer(inv -> {
                byte[] buf = inv.getArgument(0);
                System.arraycopy(fullBadHeaderResponse, 0, buf, 0, fullBadHeaderResponse.length);
                return fullBadHeaderResponse.length;
            }).thenReturn(-1);

        try (MockedStatic<Socket> mocked = mockStatic(Socket.class, withSettings().defaultAnswer(Answers.RETURNS_DEFAULTS))) {
            mocked.when(() -> new Socket(TEST_SERVER_ADDRESS, TEST_SERVER_PORT)).thenReturn(mockSocket);
            
            DataItem item = new DataItem("hostBadMagic", "keyBadMagic", "valBadMagic");
            IOException exception = assertThrows(IOException.class, () -> client.send(item));
            assertEquals("Invalid Zabbix Sender response header magic.", exception.getMessage());
        }
    }


    @Test
    void send_emptyListOfDataItems_throwsIllegalArgumentException() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> client.send(Collections.emptyList()));
        assertEquals("List of DataItems cannot be empty", exception.getMessage());
    }

    @Test
    void constructor_invalidPort_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> new ZabbixSenderClient(TEST_SERVER_ADDRESS, 0));
        assertThrows(IllegalArgumentException.class, () -> new ZabbixSenderClient(TEST_SERVER_ADDRESS, 65536));
    }
    
    @Test
    void send_nullDataItem_throwsNullPointerException() {
         assertThrows(NullPointerException.class, () -> client.send((DataItem) null));
    }

    @Test
    void send_nullDataItemList_throwsNullPointerException() {
         assertThrows(NullPointerException.class, () -> client.send((List<DataItem>) null));
    }

}
