package io.frayground.domain.strategy;

/**
 * Which hosted runtime executes a decision.
 *
 * <p>{@code MOCK} returns a test candidate without calling a model.
 * {@code LLM} uses the bound {@code ModelConfiguration}. A remote runtime is
 * a future type and is not a value of this enum yet.
 */
public enum RuntimeType {
  MOCK,
  LLM
}
