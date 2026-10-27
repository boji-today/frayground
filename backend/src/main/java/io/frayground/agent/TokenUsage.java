package io.frayground.agent;

/**
 * Token counts reported for one model call.
 *
 * <p>A null count means the provider omitted that number. It does not mean
 * zero.
 */
public record TokenUsage(
    Long inputTokens, Long outputTokens, Long cacheHitTokens, Long cacheMissTokens) {}
