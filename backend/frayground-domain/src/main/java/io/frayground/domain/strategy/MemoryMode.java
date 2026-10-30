package io.frayground.domain.strategy;

/**
 * How an Agent may retain context between decisions.
 *
 * <p>MVP accepts only {@code STATELESS}: each decision is built from the
 * current observation and the bound prompt. History and strategy memory are
 * future modes and are not values of this enum yet.
 */
public enum MemoryMode {
  STATELESS
}
