package io.frayground.domain.strategy;

import java.util.Objects;

/**
 * Memory settings fixed in one configuration version.
 *
 * <p>MVP stores only {@code mode}. Limits, token budgets, and a reset policy
 * belong to future modes and are not fields of this record.
 */
public record MemoryPolicy(MemoryMode mode) {
  public MemoryPolicy {
    Objects.requireNonNull(mode);
  }
}
