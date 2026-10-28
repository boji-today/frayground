package io.frayground.adapter.model;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Platform model vendors and system API keys.
 *
 * <p>Keys are references to configuration, not a database of user secrets.
 */
@ConfigurationProperties(prefix = "frayground.model")
public class ModelProperties {
  private Map<String, Vendor> vendors = new LinkedHashMap<>();
  private Map<String, String> systemApiKeys = new LinkedHashMap<>();

  public Map<String, Vendor> getVendors() {
    return vendors;
  }

  public void setVendors(Map<String, Vendor> vendors) {
    this.vendors = vendors;
  }

  public Map<String, String> getSystemApiKeys() {
    return systemApiKeys;
  }

  public void setSystemApiKeys(Map<String, String> systemApiKeys) {
    this.systemApiKeys = systemApiKeys;
  }

  public static class Vendor {
    private String baseUrl;
    private Pool pool = new Pool();

    public String getBaseUrl() {
      return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
    }

    public Pool getPool() {
      return pool;
    }

    public void setPool(Pool pool) {
      this.pool = pool;
    }
  }

  public static class Pool {
    private int maxConnections = 8;

    public int getMaxConnections() {
      return maxConnections;
    }

    public void setMaxConnections(int maxConnections) {
      this.maxConnections = maxConnections;
    }
  }
}
