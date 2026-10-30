package io.frayground.web;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.io.IOException;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Small loopback API client shared by the acceptance tests. */
final class LaboratoryGameHttpClient {
  private static final int MAX_PAGES = 1000;
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final int port;
  private final UUID roomId;
  private final Instant deadline;
  private final Duration requestTimeout;

  LaboratoryGameHttpClient(int port, UUID roomId) { this(port, roomId, null, Duration.ofSeconds(5)); }

  LaboratoryGameHttpClient(int port, UUID roomId, Instant deadline, Duration requestTimeout) {
    this.port = port;
    this.roomId = roomId;
    this.deadline = deadline;
    this.requestTimeout = requestTimeout;
  }

  Duration remaining() {
    if (deadline == null) return requestTimeout;
    Duration remaining = Duration.between(Instant.now(), deadline);
    if (remaining.isNegative() || remaining.isZero()) throw new DeadlineExceeded();
    return remaining;
  }

  String commandBody(String type) throws Exception {
    JsonNode room = request("GET", "/api/v1/rooms/current", "", false);
    return JSON.writeValueAsString(Map.of("commandId", UUID.randomUUID().toString(),
        "expectedRoomRevision", room.path("revision").asLong(),
        "expectedMatchId", room.path("currentMatch").path("matchId").asText(),
        "type", type.toLowerCase(Locale.ROOT)));
  }

  JsonNode sendCommand(String body) throws Exception {
    return request("POST", "/api/v1/rooms/" + roomId + "/commands", body, true);
  }

  List<JsonNode> decisions(UUID matchId, int limit) throws Exception {
    var items = new ArrayList<JsonNode>();
    String cursor = null;
    var seen = new java.util.HashSet<String>();
    int pages = 0;
    do {
      assertTrue(++pages <= MAX_PAGES, "decision pagination exceeded page limit");
      JsonNode page = request("GET", "/api/v1/matches/" + matchId + "/decisions?limit=" + limit
          + (cursor == null ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8)), "", false);
      page.path("items").forEach(items::add);
      cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText(null);
      assertTrue(cursor == null || !cursor.isBlank() && seen.add(cursor), "decision cursor must advance");
    } while (cursor != null);
    return items;
  }

  List<JsonNode> events(UUID matchId, int limit) throws Exception {
    var items = new ArrayList<JsonNode>();
    long after = 0;
    boolean more;
    int pages = 0;
    do {
      assertTrue(++pages <= MAX_PAGES, "event pagination exceeded page limit");
      long previous = after;
      JsonNode page = request("GET", "/api/v1/matches/" + matchId + "/events?limit=" + limit + "&after=" + after, "", false);
      for (JsonNode event : page.path("items")) {
        assertTrue(event.path("sequence").asLong() > after);
        after = event.path("sequence").asLong();
        items.add(event);
      }
      org.junit.jupiter.api.Assertions.assertEquals(after, page.path("nextAfter").asLong());
      more = page.path("hasMore").asBoolean();
      assertTrue(!more || after > previous, "event cursor must advance");
    } while (more);
    return items;
  }

  JsonNode request(String method, String path, String body, boolean mutation) throws Exception {
    Duration remaining = remaining();
    boolean limitedByDeadline = deadline != null && remaining.compareTo(requestTimeout) <= 0;
    var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .timeout(limitedByDeadline ? remaining : requestTimeout);
    if (mutation) builder.header("X-Frayground-Client", "laboratory").header("Content-Type", "application/json");
    var request = method.equals("GET") ? builder.GET().build()
        : builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
    HttpResponse<String> response;
    try {
      response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (HttpTimeoutException e) {
      if (limitedByDeadline) throw new DeadlineExceeded();
      throw e;
    }
    if (response.statusCode() < 200 || response.statusCode() >= 300)
      throw new HttpStatusFailure(response.statusCode());
    return JSON.readTree(response.body());
  }

  static final class DeadlineExceeded extends RuntimeException {}
  static final class HttpStatusFailure extends IOException {
    final int status;
    HttpStatusFailure(int status) { super("HTTP status " + status); this.status = status; }
  }
}
