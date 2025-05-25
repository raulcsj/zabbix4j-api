package com.example.zabbix;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Represents a single data item to be sent to Zabbix via the Zabbix Sender protocol.
 */
@JsonInclude(JsonInclude.Include.NON_NULL) // Don't include null fields in JSON
public class DataItem {

    @JsonProperty("host")
    private final String host;

    @JsonProperty("key")
    private final String key;

    @JsonProperty("value")
    private final String value;

    @JsonProperty("clock")
    private Long clock; // Unix timestamp

    @JsonProperty("ns")
    private Integer ns; // Nanoseconds for high-resolution timestamp

    /**
     * Constructs a DataItem with the mandatory fields.
     *
     * @param host  The hostname of the monitored host as registered in Zabbix. Cannot be null.
     * @param key   The item key as registered in Zabbix. Cannot be null.
     * @param value The item value. Cannot be null.
     */
    public DataItem(String host, String key, String value) {
        this.host = Objects.requireNonNull(host, "Host cannot be null");
        this.key = Objects.requireNonNull(key, "Key cannot be null");
        this.value = Objects.requireNonNull(value, "Value cannot be null");
    }

    /**
     * Gets the hostname.
     * @return The hostname.
     */
    public String getHost() {
        return host;
    }

    /**
     * Gets the item key.
     * @return The item key.
     */
    public String getKey() {
        return key;
    }

    /**
     * Gets the item value.
     * @return The item value.
     */
    public String getValue() {
        return value;
    }

    /**
     * Gets the timestamp for this data item.
     * @return The Unix timestamp (seconds), or null if not set.
     */
    public Long getClock() {
        return clock;
    }

    /**
     * Sets the timestamp for this data item.
     * If not set, Zabbix server will use the time it received the data.
     *
     * @param clock The Unix timestamp (seconds).
     * @return This DataItem instance for chaining.
     */
    public DataItem setClock(long clock) {
        this.clock = clock;
        return this;
    }

    /**
     * Gets the nanoseconds part of the timestamp for high-resolution timing.
     * @return The nanoseconds, or null if not set.
     */
    public Integer getNs() {
        return ns;
    }

    /**
     * Sets the nanoseconds part of the timestamp for high-resolution timing.
     * This is typically used in conjunction with {@link #setClock(long)}.
     *
     * @param ns The nanoseconds (0-999,999,999).
     * @return This DataItem instance for chaining.
     */
    public DataItem setNs(int ns) {
        if (ns < 0 || ns > 999_999_999) {
            throw new IllegalArgumentException("Nanoseconds must be between 0 and 999,999,999");
        }
        this.ns = ns;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DataItem dataItem = (DataItem) o;
        return host.equals(dataItem.host) &&
               key.equals(dataItem.key) &&
               value.equals(dataItem.value) &&
               Objects.equals(clock, dataItem.clock) &&
               Objects.equals(ns, dataItem.ns);
    }

    @Override
    public int hashCode() {
        return Objects.hash(host, key, value, clock, ns);
    }

    @Override
    public String toString() {
        return "DataItem{" +
               "host='" + host + '\'' +
               ", key='" + key + '\'' +
               ", value='" + value + '\'' +
               (clock != null ? ", clock=" + clock : "") +
               (ns != null ? ", ns=" + ns : "") +
               '}';
    }
}
