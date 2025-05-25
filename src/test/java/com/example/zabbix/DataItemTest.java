package com.example.zabbix;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

public class DataItemTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void constructor_shouldSetMandatoryFields() {
        DataItem item = new DataItem("host1", "item.key1", "value1");
        assertEquals("host1", item.getHost());
        assertEquals("item.key1", item.getKey());
        assertEquals("value1", item.getValue());
        assertNull(item.getClock());
        assertNull(item.getNs());
    }

    @Test
    void constructor_shouldThrowNullPointerExceptionForNullHost() {
        assertThrows(NullPointerException.class, () -> new DataItem(null, "item.key", "value"));
    }

    @Test
    void constructor_shouldThrowNullPointerExceptionForNullKey() {
        assertThrows(NullPointerException.class, () -> new DataItem("host", null, "value"));
    }

    @Test
    void constructor_shouldThrowNullPointerExceptionForNullValue() {
        assertThrows(NullPointerException.class, () -> new DataItem("host", "item.key", null));
    }

    @Test
    void setClock_shouldSetClock() {
        DataItem item = new DataItem("host1", "item.key1", "value1");
        item.setClock(1234567890L);
        assertEquals(1234567890L, item.getClock());
    }

    @Test
    void setNs_shouldSetNs() {
        DataItem item = new DataItem("host1", "item.key1", "value1");
        item.setNs(123456789);
        assertEquals(123456789, item.getNs());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 1_000_000_000})
    void setNs_shouldThrowIllegalArgumentExceptionForInvalidNs(int invalidNs) {
        DataItem item = new DataItem("host1", "item.key1", "value1");
        assertThrows(IllegalArgumentException.class, () -> item.setNs(invalidNs));
    }

    @Test
    void equals_shouldReturnTrueForSameObjects() {
        DataItem item1 = new DataItem("host", "key", "value").setClock(1L).setNs(1);
        DataItem item2 = item1;
        assertTrue(item1.equals(item2));
        assertEquals(item1.hashCode(), item2.hashCode());
    }
    
    @Test
    void equals_shouldReturnTrueForEqualObjects() {
        DataItem item1 = new DataItem("host", "key", "value").setClock(1L).setNs(1);
        DataItem item2 = new DataItem("host", "key", "value").setClock(1L).setNs(1);
        assertTrue(item1.equals(item2));
        assertEquals(item1.hashCode(), item2.hashCode());
    }

    @Test
    void equals_shouldReturnFalseForDifferentHost() {
        DataItem item1 = new DataItem("host1", "key", "value");
        DataItem item2 = new DataItem("host2", "key", "value");
        assertFalse(item1.equals(item2));
        assertNotEquals(item1.hashCode(), item2.hashCode());
    }

    @Test
    void equals_shouldReturnFalseForDifferentKey() {
        DataItem item1 = new DataItem("host", "key1", "value");
        DataItem item2 = new DataItem("host", "key2", "value");
        assertFalse(item1.equals(item2));
        assertNotEquals(item1.hashCode(), item2.hashCode());
    }

    @Test
    void equals_shouldReturnFalseForDifferentValue() {
        DataItem item1 = new DataItem("host", "key", "value1");
        DataItem item2 = new DataItem("host", "key", "value2");
        assertFalse(item1.equals(item2));
        assertNotEquals(item1.hashCode(), item2.hashCode());
    }

    @Test
    void equals_shouldReturnFalseForDifferentClock() {
        DataItem item1 = new DataItem("host", "key", "value").setClock(1L);
        DataItem item2 = new DataItem("host", "key", "value").setClock(2L);
        assertFalse(item1.equals(item2));
        assertNotEquals(item1.hashCode(), item2.hashCode());
    }
    
    @Test
    void equals_shouldReturnFalseForNullOtherClock() {
        DataItem item1 = new DataItem("host", "key", "value").setClock(1L);
        DataItem item2 = new DataItem("host", "key", "value"); // clock is null
        assertFalse(item1.equals(item2));
        assertNotEquals(item1.hashCode(), item2.hashCode());
    }

    @Test
    void equals_shouldReturnFalseForDifferentNs() {
        DataItem item1 = new DataItem("host", "key", "value").setNs(1);
        DataItem item2 = new DataItem("host", "key", "value").setNs(2);
        assertFalse(item1.equals(item2));
        assertNotEquals(item1.hashCode(), item2.hashCode());
    }
    
    @Test
    void equals_shouldReturnFalseForNullOtherNs() {
        DataItem item1 = new DataItem("host", "key", "value").setNs(1);
        DataItem item2 = new DataItem("host", "key", "value"); // ns is null
        assertFalse(item1.equals(item2));
        assertNotEquals(item1.hashCode(), item2.hashCode());
    }

    @Test
    void equals_shouldReturnFalseForNullObject() {
        DataItem item1 = new DataItem("host", "key", "value");
        assertFalse(item1.equals(null));
    }

    @Test
    void equals_shouldReturnFalseForDifferentClass() {
        DataItem item1 = new DataItem("host", "key", "value");
        assertFalse(item1.equals("a string"));
    }

    @Test
    void toString_shouldContainAllFields() {
        DataItem item = new DataItem("myHost", "my.key", "myValue")
                .setClock(1678886400L)
                .setNs(123450000);
        String str = item.toString();
        assertTrue(str.contains("host='myHost'"));
        assertTrue(str.contains("key='my.key'"));
        assertTrue(str.contains("value='myValue'"));
        assertTrue(str.contains("clock=1678886400"));
        assertTrue(str.contains("ns=123450000"));
    }

    @Test
    void toString_shouldNotContainNullOptionalFields() {
        DataItem item = new DataItem("myHost", "my.key", "myValue");
        String str = item.toString();
        assertTrue(str.contains("host='myHost'"));
        assertTrue(str.contains("key='my.key'"));
        assertTrue(str.contains("value='myValue'"));
        assertFalse(str.contains("clock="));
        assertFalse(str.contains("ns="));
    }

    @Test
    void jsonSerialization_shouldIncludeNonNullFields() throws JsonProcessingException {
        DataItem item = new DataItem("host1", "item.key1", "value1")
                .setClock(12345L)
                .setNs(67890);
        String json = objectMapper.writeValueAsString(item);

        assertTrue(json.contains("\"host\":\"host1\""));
        assertTrue(json.contains("\"key\":\"item.key1\""));
        assertTrue(json.contains("\"value\":\"value1\""));
        assertTrue(json.contains("\"clock\":12345"));
        assertTrue(json.contains("\"ns\":67890"));
    }

    @Test
    void jsonSerialization_shouldOmitNullOptionalFields() throws JsonProcessingException {
        DataItem item = new DataItem("host1", "item.key1", "value1");
        String json = objectMapper.writeValueAsString(item);

        assertTrue(json.contains("\"host\":\"host1\""));
        assertTrue(json.contains("\"key\":\"item.key1\""));
        assertTrue(json.contains("\"value\":\"value1\""));
        assertFalse(json.contains("\"clock\""));
        assertFalse(json.contains("\"ns\""));
    }
}
