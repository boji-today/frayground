package io.frayground.adapter.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One {@link VendorHttpPool} for each configured model vendor.
 */
public final class VendorHttpPools implements AutoCloseable {
  private final Map<String, VendorHttpPool> pools;

  public VendorHttpPools(ModelProperties properties) {
    Map<String, VendorHttpPool> created = new LinkedHashMap<>();
    properties.getVendors().forEach((name, vendor) -> {
      int maxConnections = vendor.getPool() == null ? 8 : vendor.getPool().getMaxConnections();
      created.put(name, new VendorHttpPool(name, maxConnections));
    });
    this.pools = Map.copyOf(created);
  }

  public VendorHttpPool get(String vendor) {
    VendorHttpPool pool = pools.get(vendor);
    if (pool == null) {
      throw new IllegalStateException("model vendor pool: " + vendor);
    }
    return pool;
  }

  @Override
  public void close() {
    pools.values().forEach(VendorHttpPool::close);
  }
}
