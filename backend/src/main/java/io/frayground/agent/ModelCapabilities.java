package io.frayground.agent;

import java.util.Objects;
import java.util.Set;

/**
 * Output modes a model configuration can use.
 *
 * <p>{@code betaRequired} means a mode is available only when the
 * configuration explicitly allows beta behavior. {@code supportedSchemaKeywords}
 * limits what the adapter may send; local validation still uses the full schema.
 */
public record ModelCapabilities(
    boolean nativeJsonSchema,
    boolean strictTools,
    boolean jsonMode,
    boolean plainJson,
    boolean betaRequired,
    Set<String> supportedSchemaKeywords) {

  public ModelCapabilities {
    supportedSchemaKeywords = Set.copyOf(Objects.requireNonNull(supportedSchemaKeywords));
  }
}
