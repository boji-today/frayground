package io.frayground.web;

import java.io.PrintStream;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Runs only public game APIs; the same bounded smoke flow is exercised offline. */
final class LaboratoryGameSmokeRunner {
  private LaboratoryGameSmokeRunner() {}

  static void run(LaboratoryGameHttpClient api, Duration pollInterval, PrintStream output) {
    Instant started = Instant.now();
    UUID matchId = null;
    int moves = 0;
    long input = 0, tokensOut = 0;
    boolean usageAvailable = false;
    String result = "UNKNOWN", error = "NONE";
    boolean success = false;
    try {
      JsonNode room = api.request("POST", "/api/v1/rooms/current", "", true);
      matchId = UUID.fromString(room.path("currentMatch").path("matchId").asText());
      api.sendCommand(api.commandBody("START"));
      while (true) {
        JsonNode state = api.request("GET", "/api/v1/rooms/current", "", false);
        moves = state.path("currentMatch").path("game").path("moveCount").asInt();
        result = state.path("currentMatch").path("result").path("status").asText("UNKNOWN");
        List<JsonNode> decisions = api.decisions(matchId, 100);
        input = 0; tokensOut = 0;
        String modelError = null;
        for (JsonNode d : decisions) {
          input += d.path("usage").path("inputTokens").asLong();
          tokensOut += d.path("usage").path("outputTokens").asLong();
          if (!d.path("errorCode").asText("").isBlank()) modelError = d.path("errorCode").asText();
        }
        usageAvailable = true;
        if (moves > 42 || decisions.size() > 42) throw new SmokeFailure("DECISION_LIMIT_EXCEEDED");
        if (modelError != null) throw new SmokeFailure(modelError);
        if ("FINISHED".equals(state.path("currentMatch").path("status").asText())) {
          if (moves == 0 || decisions.size() != moves || !result.matches("WIN|DRAW")
              || decisions.stream().anyMatch(d -> !"COMPLETED".equals(d.path("status").asText())))
            throw new SmokeFailure("INVALID_FINISHED_GAME");
          success = true;
          return;
        }
        if ("PAUSED".equals(state.path("executionStatus").asText())) throw new SmokeFailure("UNEXPECTED_PAUSE");
        long millis = Math.min(pollInterval.toMillis(), Math.max(1, api.remaining().toMillis()));
        Thread.sleep(millis);
      }
    } catch (Exception | AssertionError failure) {
      if (failure instanceof LaboratoryGameHttpClient.DeadlineExceeded) error = "GAME_TIMEOUT";
      else if (failure instanceof HttpTimeoutException) error = "HTTP_TIMEOUT";
      else if (failure instanceof LaboratoryGameHttpClient.HttpStatusFailure status) error = "HTTP_STATUS_" + status.status;
      else if (failure instanceof SmokeFailure smoke) error = smoke.code;
      else if (failure instanceof InterruptedException) { Thread.currentThread().interrupt(); error = "INTERRUPTED"; }
      else error = "HTTP_OR_PROTOCOL_FAILURE";
      // Do not expose raw exception messages, response bodies, or private model data.
      throw new AssertionError("DeepSeek smoke failed: match=" + matchId + ", errorCode=" + error);
    } finally {
      output.printf("DeepSeek smoke: success=%s match=%s result=%s moves=%d elapsedMs=%d errorCode=%s inputTokens=%d outputTokens=%d usageAvailable=%s usageSource=lastSuccessfulPoll%n",
          success, matchId, result, moves, Duration.between(started, Instant.now()).toMillis(), error,
          input, tokensOut, usageAvailable);
    }
  }

  private static final class SmokeFailure extends RuntimeException {
    final String code;
    SmokeFailure(String code) { this.code = code.matches("[A-Z][A-Z0-9_]{0,79}") ? code : "MODEL_FAILURE"; }
  }
}
