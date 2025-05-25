package com.example.zabbix;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Client for sending data to Zabbix Server or Proxy using the Zabbix Sender protocol.
 */
public class ZabbixSenderClient {

    private final String serverAddress;
    private final int serverPort;
    private final ObjectMapper objectMapper;

    private static final byte[] ZBX_HEADER_MAGIC = "ZBXD".getBytes(StandardCharsets.US_ASCII);
    private static final byte ZBX_PROTOCOL_VERSION = 0x01;
    private static final int ZBX_HEADER_LENGTH = ZBX_HEADER_MAGIC.length + 1 + 8; // Magic (4) + Version (1) + Data Length (8)

    /**
     * Constructs a ZabbixSenderClient.
     *
     * @param serverAddress The hostname or IP address of the Zabbix server or proxy. Cannot be null.
     * @param serverPort    The port number of the Zabbix sender listener (default is 10051).
     */
    public ZabbixSenderClient(String serverAddress, int serverPort) {
        this.serverAddress = Objects.requireNonNull(serverAddress, "Server address cannot be null");
        if (serverPort <= 0 || serverPort > 65535) {
            throw new IllegalArgumentException("Server port must be between 1 and 65535");
        }
        this.serverPort = serverPort;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Sends a single DataItem to the Zabbix server.
     *
     * @param dataItem The DataItem to send. Cannot be null.
     * @return A JsonNode representing the Zabbix server's response.
     * @throws IOException If a network error occurs, if there's an issue processing the request/response,
     *                     or if Zabbix reports an error.
     */
    public JsonNode send(DataItem dataItem) throws IOException {
        Objects.requireNonNull(dataItem, "DataItem cannot be null");
        return send(Collections.singletonList(dataItem), null, null);
    }

    /**
     * Sends a list of DataItems to the Zabbix server.
     *
     * @param dataItems The list of DataItems to send. Cannot be null or empty.
     * @return A JsonNode representing the Zabbix server's response.
     * @throws IOException If a network error occurs, if there's an issue processing the request/response,
     *                     or if Zabbix reports an error.
     */
    public JsonNode send(List<DataItem> dataItems) throws IOException {
        Objects.requireNonNull(dataItems, "List of DataItems cannot be null");
        if (dataItems.isEmpty()) {
            throw new IllegalArgumentException("List of DataItems cannot be empty");
        }
        return send(dataItems, null, null);
    }

    /**
     * Sends a list of DataItems to the Zabbix server, optionally with a high-resolution timestamp
     * for the entire batch.
     *
     * @param dataItems The list of DataItems to send. Cannot be null or empty.
     * @param clock     Optional Unix timestamp (seconds) for the entire batch. Zabbix uses this if individual
     *                  DataItem clock values are not set.
     * @param ns        Optional nanoseconds part of the timestamp for the entire batch (0-999,999,999).
     *                  Used with {@code clock}.
     * @return A JsonNode representing the Zabbix server's response.
     * @throws IOException If a network error occurs, if there's an issue processing the request/response,
     *                     or if Zabbix reports an error.
     */
    public JsonNode send(List<DataItem> dataItems, Long clock, Integer ns) throws IOException {
        Objects.requireNonNull(dataItems, "List of DataItems cannot be null");
        if (dataItems.isEmpty()) {
            throw new IllegalArgumentException("List of DataItems cannot be empty");
        }
        // Validate ns range if provided.
        // preparePayload method handles the logic of including clock/ns or defaulting ns if clock is present.
        if (ns != null && (ns < 0 || ns > 999_999_999)) {
            throw new IllegalArgumentException("Nanoseconds must be between 0 and 999,999,999 if provided.");
        }
        // It's generally acceptable for 'clock' to be provided without 'ns' (ns might default to 0 on Zabbix side or by preparePayload).
        // It's also acceptable for 'ns' to be provided without 'clock' for an individual DataItem,
        // but for a batch timestamp, 'clock' is essential if 'ns' is to be used meaningfully.
        // However, preparePayload will only add 'ns' if 'clock' is also present for the batch.
        // So, the main explicit validation here is the range of 'ns'.

        byte[] payload = preparePayload(dataItems, clock, ns);

        try (Socket socket = new Socket(serverAddress, serverPort);
             OutputStream out = socket.getOutputStream();
             InputStream in = socket.getInputStream()) {

            out.write(payload);
            out.flush(); // Ensure data is sent

            // Read response header
            byte[] responseHeaderBytes = new byte[ZBX_HEADER_LENGTH];
            int bytesRead = readFully(in, responseHeaderBytes);

            if (bytesRead < ZBX_HEADER_LENGTH) {
                throw new IOException("Failed to read complete Zabbix Sender response header. Read " + bytesRead + " bytes.");
            }

            // Validate response header magic
            for (int i = 0; i < ZBX_HEADER_MAGIC.length; i++) {
                if (responseHeaderBytes[i] != ZBX_HEADER_MAGIC[i]) {
                    throw new IOException("Invalid Zabbix Sender response header magic.");
                }
            }
            // byte responseVersion = responseHeaderBytes[ZBX_HEADER_MAGIC.length]; // TODO: Optionally check version

            ByteBuffer headerBuffer = ByteBuffer.wrap(responseHeaderBytes, ZBX_HEADER_MAGIC.length + 1, 8);
            headerBuffer.order(ByteOrder.LITTLE_ENDIAN);
            long responseDataLength = headerBuffer.getLong();

            if (responseDataLength < 0 || responseDataLength > Integer.MAX_VALUE - ZBX_HEADER_LENGTH) { // Protect against huge allocation
                 throw new IOException("Invalid response data length from Zabbix: " + responseDataLength);
            }

            // Read response data
            byte[] responseDataBytes = new byte[(int) responseDataLength];
            bytesRead = readFully(in, responseDataBytes);

            if (bytesRead < responseDataLength) {
                throw new IOException("Failed to read complete Zabbix Sender response data. Expected " +
                                      responseDataLength + ", got " + bytesRead + " bytes.");
            }

            JsonNode responseJson = objectMapper.readTree(responseDataBytes);
            checkZabbixResponseForErrors(responseJson);
            return responseJson;

        } catch (JsonProcessingException e) {
            throw new IOException("Error processing JSON for Zabbix Sender request: " + e.getMessage(), e);
        }
    }

    private byte[] preparePayload(List<DataItem> dataItems, Long clock, Integer ns) throws JsonProcessingException {
        ObjectNode requestNode = objectMapper.createObjectNode();
        requestNode.put("request", "sender data");
        requestNode.set("data", objectMapper.valueToTree(dataItems));

        if (clock != null) {
            requestNode.put("clock", clock);
            if (ns != null) { // ns is only added if clock is present
                requestNode.put("ns", ns);
            } else { // if clock is present but ns is null, zabbix expects ns to be 0
                 requestNode.put("ns", 0);
            }
        }


        byte[] jsonData = objectMapper.writeValueAsBytes(requestNode);
        long dataLength = jsonData.length;

        ByteBuffer payloadBuffer = ByteBuffer.allocate(ZBX_HEADER_LENGTH + (int) dataLength);
        payloadBuffer.order(ByteOrder.LITTLE_ENDIAN); // For data length field

        payloadBuffer.put(ZBX_HEADER_MAGIC);
        payloadBuffer.put(ZBX_PROTOCOL_VERSION);
        payloadBuffer.putLong(dataLength); // Little-endian by buffer's order
        payloadBuffer.put(jsonData);

        return payloadBuffer.array();
    }
    
    private int readFully(InputStream in, byte[] b) throws IOException {
        int n = 0;
        while (n < b.length) {
            int count = in.read(b, n, b.length - n);
            if (count < 0)
                break;
            n += count;
        }
        return n;
    }


    private void checkZabbixResponseForErrors(JsonNode responseJson) throws IOException {
        if (responseJson == null || !responseJson.has("response")) {
            throw new IOException("Invalid Zabbix Sender response: 'response' field missing. Full response: " + responseJson);
        }
        if (!"success".equalsIgnoreCase(responseJson.get("response").asText())) {
            String info = responseJson.has("info") ? responseJson.get("info").asText() : "No additional info.";
            throw new IOException("Zabbix Sender reported an error: " + info + ". Full response: " + responseJson);
        }
        // Example of info field: "processed: 1; failed: 0; total: 1; seconds spent: 0.000035"
        // One could parse this for more detailed feedback if needed.
    }
}
