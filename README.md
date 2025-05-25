# Zabbix Java Integration Library

## Overview

This library provides a set of Java clients for interacting with a Zabbix monitoring system. It includes:

*   **Zabbix API Client (Synchronous):** For making blocking calls to the Zabbix JSON-RPC API.
*   **Zabbix API Client (Asynchronous):** For making non-blocking, `CompletableFuture`-based calls to the Zabbix JSON-RPC API.
*   **Zabbix Sender Client:** For sending data to Zabbix Server/Proxy using the Zabbix Sender protocol.

This library is built with Java 11 and uses Gradle.

## Features

*   Targets Java 11.
*   Built with Gradle.
*   **Zabbix API Client:**
    *   Supports interaction with the Zabbix JSON-RPC API. (Tested against Zabbix versions 5.0+, but should be generally compatible with versions supporting JSON-RPC).
    *   Synchronous (blocking) operations.
    *   Asynchronous (`CompletableFuture`-based) operations.
    *   Authentication (`user.login`).
    *   Example methods for `host.get` and `item.get`.
    *   Uses OkHttp for HTTP communication and Jackson for JSON processing.
*   **Zabbix Sender Client:**
    *   Implements the Zabbix Sender protocol for sending metric data.
    *   Supports sending single or multiple data items.
    *   Allows optional timestamps (including high-resolution `clock` and `ns`) for data items.
    *   Uses Jackson for JSON payload construction.

## Prerequisites

*   Java Development Kit (JDK) 11 or newer.
*   Gradle 7.x or newer (for building from source).

## Installation / Adding to your project

### Building from source

1.  Clone the repository:
    ```bash
    git clone <repository-url>
    cd zabbix-java-lib
    ```
2.  Build the JAR file using Gradle:
    ```bash
    ./gradlew build
    ```
    The JAR file will be located in `build/libs/zabbix-java-lib-1.0-SNAPSHOT.jar` (the version might vary).

### Including in your Gradle project

After building the JAR, you can include it as a local dependency in your project's `build.gradle` file:

```gradle
dependencies {
    // ... other dependencies
    implementation files('libs/zabbix-java-lib-1.0-SNAPSHOT.jar') // Assuming you copy the JAR to a 'libs' folder in your project
}
```

If the library were published to a Maven repository (like Maven Central or a private repository), you would add it like this:

```gradle
// Example if published (not currently set up)
// repositories {
//     mavenCentral() 
// }
// dependencies {
//     implementation 'com.example:zabbix-java-lib:1.0-SNAPSHOT'
// }
```

## Usage Examples

### Zabbix API Sync Client (`ZabbixApiSyncClient`)

**1. Instantiate the client:**

```java
import com.example.zabbix.ZabbixApiSyncClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;

public class SyncClientExample {
    public static void main(String[] args) {
        ZabbixApiSyncClient apiClient = new ZabbixApiSyncClient("http://your-zabbix-server/api_jsonrpc.php");

        try {
            // ... usage below
        } catch (IOException e) {
            System.err.println("API request failed: " + e.getMessage());
            // Log the full stack trace for debugging
            // e.printStackTrace();
        }
    }
}
```

**2. Authenticate:**

```java
// (Inside try block from above)
apiClient.authenticate("ZabbixUser", "YourPassword");
System.out.println("Successfully authenticated with Zabbix API.");
```

**3. Example API call (get host):**

```java
// (After successful authentication)
String hostNameToFind = "Zabbix server"; // Replace with a host you want to find
JsonNode hostInfo = apiClient.getHost(hostNameToFind);

if (hostInfo != null) {
    System.out.println("Host Found: " + hostInfo.toString());
    String hostId = hostInfo.get("hostid").asText();
    System.out.println("Host ID: " + hostId);

    // Example: Get an item from this host
    JsonNode itemInfo = apiClient.getItem("system.cpu.load[percpu,avg1]", hostNameToFind);
    if (itemInfo != null) {
        System.out.println("Item Found: " + itemInfo.toString());
    } else {
        System.out.println("Item not found on host " + hostNameToFind);
    }

} else {
    System.out.println("Host '" + hostNameToFind + "' not found.");
}
```

**4. Basic Error Handling:**
API calls throw `IOException` for network issues or Zabbix API errors (e.g., authentication failure, invalid parameters). The `ZabbixApiSyncClient` includes details from the Zabbix API error response in the exception message.

### Zabbix API Async Client (`ZabbixApiAsyncClient`)

**1. Instantiate the client:**

```java
import com.example.zabbix.ZabbixApiAsyncClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class AsyncClientExample {
    public static void main(String[] args) {
        ZabbixApiAsyncClient asyncApiClient = new ZabbixApiAsyncClient("http://your-zabbix-server/api_jsonrpc.php");

        // ... usage below
    }
}
```

**2. Asynchronous Authentication:**

```java
CompletableFuture<String> authFuture = asyncApiClient.authenticateAsync("ZabbixUser", "YourPassword");

authFuture.whenComplete((token, ex) -> {
    if (ex != null) {
        System.err.println("Async authentication failed: " + ex.getMessage());
        // ex.printStackTrace();
        return;
    }
    System.out.println("Successfully authenticated asynchronously. Token: " + token);

    // Proceed with other API calls once authenticated
    getHostExample(asyncApiClient, "Zabbix server");
});

// Keep the main thread alive for async operations to complete in this example
try {
    Thread.sleep(5000); // Adjust as needed for demo
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
}
```

**3. Example Asynchronous API call (get host):**

```java
// (Method called after successful async authentication)
public static void getHostExample(ZabbixApiAsyncClient client, String hostNameToFind) {
    CompletableFuture<JsonNode> hostFuture = client.getHostAsync(hostNameToFind);

    hostFuture.whenComplete((hostInfo, ex) -> {
        if (ex != null) {
            System.err.println("Failed to get host '" + hostNameToFind + "': " + ex.getMessage());
            // ex.printStackTrace();
            return;
        }

        if (hostInfo != null) {
            System.out.println("Async Host Found: " + hostInfo.toString());
            String hostId = hostInfo.get("hostid").asText();
            System.out.println("Async Host ID: " + hostId);

            // Example: Get an item asynchronously
            client.getItemAsync("agent.ping", hostNameToFind).whenComplete((itemInfo, itemEx) -> {
                 if (itemEx != null) {
                     System.err.println("Failed to get item: " + itemEx.getMessage());
                     return;
                 }
                 if (itemInfo != null) {
                     System.out.println("Async Item Found: " + itemInfo.toString());
                 } else {
                     System.out.println("Async item not found.");
                 }
            });

        } else {
            System.out.println("Async Host '" + hostNameToFind + "' not found.");
        }
    });
}
```

**4. Basic Error Handling with `CompletableFuture`:**
Use `whenComplete` or `handle` on the `CompletableFuture` to process results or exceptions. Exceptions are typically wrapped in `ExecutionException` if you call `future.get()`, or passed directly to the `exception` parameter in `whenComplete`.

### Zabbix Sender Client (`ZabbixSenderClient`)

**1. Instantiate the client:**

```java
import com.example.zabbix.ZabbixSenderClient;
import com.example.zabbix.DataItem;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class SenderClientExample {
    public static void main(String[] args) {
        // Replace with your Zabbix server/proxy address and sender port (default 10051)
        ZabbixSenderClient senderClient = new ZabbixSenderClient("your-zabbix-server-or-proxy", 10051);

        try {
            // ... usage below
        } catch (IOException e) {
            System.err.println("Failed to send data: " + e.getMessage());
            // e.printStackTrace();
        }
    }
}
```

**2. Create `DataItem` objects:**

```java
// (Inside try block)
DataItem item1 = new DataItem(
    "MonitoredHost1",        // Hostname as registered in Zabbix
    "app.metric.value",      // Item key
    "123.45"                 // Value
);

// Optionally set timestamp (Unix epoch seconds) and nanoseconds
long currentEpochSeconds = System.currentTimeMillis() / 1000L;
item1.setClock(currentEpochSeconds).setNs(123450000); // Optional nanoseconds

DataItem item2 = new DataItem("MonitoredHost1", "app.status", "OK");
```

**3. Example of sending a single `DataItem`:**

```java
// (Inside try block)
JsonNode responseSingle = senderClient.send(item1);
System.out.println("Single item send response: " + responseSingle.toString());
// Check response.get("info") for details like "processed: 1; failed: 0;"
```

**4. Example of sending a list of `DataItem`s:**

```java
// (Inside try block)
List<DataItem> dataItems = new ArrayList<>();
dataItems.add(item1);
dataItems.add(item2);

// Optionally, provide a top-level clock/ns for all items in the batch
// if their individual clock/ns are not set.
// long batchClock = System.currentTimeMillis() / 1000L;
// int batchNs = 0;
// JsonNode responseList = senderClient.send(dataItems, batchClock, batchNs);

JsonNode responseList = senderClient.send(dataItems);
System.out.println("List items send response: " + responseList.toString());
```

**5. Interpreting the response:**
The `send` method returns a `JsonNode` representing the Zabbix Sender's response.
A successful response typically looks like:
`{"response":"success","info":"processed: 2; failed: 0; total: 2; seconds spent: 0.000300"}`
If Zabbix Sender reports an error, an `IOException` is thrown with details from the "info" field.

## Error Handling

*   **API Clients (`ZabbixApiSyncClient`, `ZabbixApiAsyncClient`):**
    *   Network errors or unexpected HTTP responses typically result in an `IOException`.
    *   Zabbix API errors (e.g., invalid credentials, parameters) also result in an `IOException`. The exception message will contain details from the Zabbix API's error object.
    *   For the async client, errors are propagated through the `CompletableFuture` (e.g., by completing exceptionally).
*   **Sender Client (`ZabbixSenderClient`):**
    *   Network errors or issues with the Zabbix Sender protocol result in an `IOException`.
    *   If the Zabbix server/proxy processes the data but reports failures for some items, an `IOException` is thrown, containing the "info" string from the Zabbix Sender response which details processed/failed counts.

Always wrap client interactions in try-catch blocks or handle `CompletableFuture` exceptions appropriately.

## Contributing

Contributions are welcome! Please feel free to fork the repository, make changes, and submit pull requests. For major changes, please open an issue first to discuss what you would like to change.

(Placeholder - consider adding more specific guidelines if this were a public project, e.g., coding standards, test requirements.)

## License

This project is licensed under the MIT License. (Placeholder - Create a `LICENSE` file with the MIT License text if applicable.)

---

*Note: Ensure your Zabbix server/proxy is configured to accept connections from the machine running this client, especially for the Zabbix Sender (port 10051 by default).*
```
