package io.frayground.web;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Assertions over public HTTP representations, shared by the real and controlled runtimes. */
final class LaboratoryGameAssertions {
  private LaboratoryGameAssertions() {}

  record History(JsonNode match, List<JsonNode> decisions, List<JsonNode> details, List<JsonNode> events) {}

  static History finishedHistory(LaboratoryGameHttpClient api, UUID matchId) throws Exception {
    JsonNode match = api.request("GET", "/api/v1/matches/" + matchId, "", false);
    assertEquals("FINISHED", match.path("status").asText());
    int moves = match.path("game").path("moveCount").asInt();
    assertTrue(moves > 0 && moves <= 42);
    assertEquals(moves, match.path("stateVersion").asInt());
    var participants = new HashMap<String, String>();
    var seats = new HashMap<String, String>();
    for (JsonNode p : match.path("participants")) {
      assertNull(participants.put(p.path("participantId").asText(), p.path("agentId").asText()));
      assertNull(seats.put(p.path("seatKey").asText(), p.path("participantId").asText()));
    }
    assertEquals(2, participants.size());
    var decisions = api.decisions(matchId, 2);
    var details = new ArrayList<JsonNode>();
    var byId = new HashMap<String, JsonNode>();
    var actualParticipants = new HashSet<String>();
    java.time.Instant previous = java.time.Instant.MIN;
    for (JsonNode decision : decisions) {
      String id = decision.path("decisionId").asText();
      assertNull(byId.put(id, decision), "decision IDs must be unique across pages");
      assertEquals(matchId.toString(), decision.path("matchId").asText());
      assertEquals("COMPLETED", decision.path("status").asText());
      assertTrue(participants.containsKey(decision.path("participantId").asText()));
      actualParticipants.add(decision.path("participantId").asText());
      var started = java.time.Instant.parse(decision.path("startedAt").asText());
      assertFalse(started.isBefore(previous));
      previous = started;
      JsonNode detail = api.request("GET", "/api/v1/decisions/" + id, "", false);
      for (String field : List.of("decisionId", "matchId", "participantId", "turnId", "stateVersion", "promptVersionId", "configVersionId"))
        assertEquals(decision.path(field), detail.path(field));
      assertEquals("EXECUTED", detail.path("receipt").path("status").asText());
      assertEquals(detail.path("submittedAction"), detail.path("receipt").path("executedAction"));
      assertEquals(detail.path("stateVersion").asLong() + 1, detail.path("receipt").path("stateVersion").asLong());
      details.add(detail);
    }
    assertEquals(participants.keySet(), actualParticipants);
    assertEquals(moves, decisions.size());
    var events = api.events(matchId, 2);
    var board = new ArrayList<List<String>>();
    for (int row = 0; row < 6; row++) board.add(new ArrayList<>(Collections.nCopies(7, "EMPTY")));
    var placedIds = new HashSet<String>();
    int placed = 0;
    for (JsonNode event : events) {
      assertEquals(matchId.toString(), event.path("matchId").asText());
      if (!"DiscPlaced".equals(event.path("type").asText())) continue;
      String id = event.path("decisionId").asText();
      assertTrue(placedIds.add(id), "each decision places exactly one disc");
      JsonNode decision = byId.get(id);
      assertNotNull(decision);
      JsonNode payload = event.path("payload");
      assertEquals(decision.path("participantId"), payload.path("participantId"));
      assertEquals(seats.get(payload.path("seat").asText()), payload.path("participantId").asText());
      assertEquals(++placed, event.path("stateVersion").asInt());
      assertEquals(decision.path("stateVersion").asLong() + 1, event.path("stateVersion").asLong());
      JsonNode detail = details.stream().filter(d -> id.equals(d.path("decisionId").asText())).findFirst().orElseThrow();
      assertEquals(detail.path("receipt").path("executedAction").path("column"), payload.path("column"));
      assertEquals("EMPTY", board.get(payload.path("row").asInt()).set(payload.path("column").asInt(), payload.path("seat").asText()));
    }
    assertEquals(byId.keySet(), placedIds);
    assertEquals(moves, placed);
    assertEquals(JsonMapper.builder().build().valueToTree(board), match.path("game").path("board"));
    return new History(match, List.copyOf(decisions), List.copyOf(details), List.copyOf(events));
  }

  static void resetPreservesHistory(LaboratoryGameHttpClient api, JsonNode originalRoom,
      History history, String oldCommand) throws Exception {
    api.sendCommand(api.commandBody("RESET"));
    JsonNode reset = api.request("GET", "/api/v1/rooms/current", "", false);
    assertEquals(originalRoom.path("roomId"), reset.path("roomId"));
    assertEquals("STEP", reset.path("executionMode").asText());
    assertEquals("PAUSED", reset.path("executionStatus").asText());
    JsonNode fresh = reset.path("currentMatch");
    assertNotEquals(history.match().path("matchId"), fresh.path("matchId"));
    assertEquals("READY", fresh.path("status").asText());
    assertEquals(0, fresh.path("stateVersion").asInt());
    assertEquals(0, fresh.path("game").path("moveCount").asInt());
    var oldIds = new HashSet<String>();
    var oldAgents = new HashMap<String, String>();
    for (JsonNode p : history.match().path("participants")) {
      oldIds.add(p.path("participantId").asText());
      oldAgents.put(p.path("seatKey").asText(), p.path("agentId").asText());
    }
    assertEquals(2, fresh.path("participants").size());
    var newIds = new HashSet<String>();
    for (JsonNode p : fresh.path("participants")) {
      assertFalse(oldIds.contains(p.path("participantId").asText()));
      assertTrue(newIds.add(p.path("participantId").asText()));
      assertEquals(oldAgents.get(p.path("seatKey").asText()), p.path("agentId").asText());
    }
    for (JsonNode row : fresh.path("game").path("board"))
      for (JsonNode cell : row) assertEquals("EMPTY", cell.asText());
    api.sendCommand(oldCommand);
    assertEquals(reset, api.request("GET", "/api/v1/rooms/current", "", false), "old command replay must not change the new room/match");
    UUID oldMatch = UUID.fromString(history.match().path("matchId").asText());
    assertEquals(history, finishedHistory(api, oldMatch), "reset must preserve the full old result and all public records");
  }
}
