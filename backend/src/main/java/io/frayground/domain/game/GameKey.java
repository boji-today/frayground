package io.frayground.domain.game;

/**
 * Identity of one rules definition.
 *
 * <p>{@code gameType} names the game, such as connect four.
 * {@code gameRulesVersion} fixes the rule revision used by a match. A match
 * keeps the same key for its whole life.
 */
public record GameKey(String gameType, String gameRulesVersion) {
  public GameKey {
    requireText(gameType, "gameType");
    requireText(gameRulesVersion, "gameRulesVersion");
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name);
    }
  }
}
