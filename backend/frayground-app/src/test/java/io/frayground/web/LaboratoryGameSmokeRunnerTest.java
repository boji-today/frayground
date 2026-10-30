package io.frayground.web;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Failure reporting and time bounds are tested locally without enabling the live test. */
class LaboratoryGameSmokeRunnerTest {
  @Test void finishedGameReportsUsage() throws Exception { exercise(Mode.FINISH, null); }
  @Test void modelFailureReportsUsageWithoutContinuing() throws Exception { exercise(Mode.MODEL_ERROR, "MODEL_UNAVAILABLE"); }
  @Test void httpFailureRetainsLastKnownUsageAndOmitsResponseBody() throws Exception { exercise(Mode.STATUS_ERROR, "HTTP_STATUS_503"); }
  @Test void hungRequestIsBoundedAndReportsLastKnownUsage() throws Exception { exercise(Mode.HANG, "HTTP_TIMEOUT"); }
  @Test void overallDeadlineIsBoundedAndReportsLastKnownUsage() throws Exception { exercise(Mode.WAIT, "GAME_TIMEOUT"); }
  @Test void deadlineAlsoBoundsAnInFlightRequest() throws Exception { exercise(Mode.HANG_DEADLINE, "GAME_TIMEOUT"); }

  private void exercise(Mode mode, String error) throws Exception {
    try (var server = new ApiFixture(mode)) {
      Duration budget = mode == Mode.WAIT || mode == Mode.HANG_DEADLINE ? Duration.ofSeconds(2) : Duration.ofSeconds(8);
      var api = new LaboratoryGameHttpClient(server.server.getAddress().getPort(), server.id,
          Instant.now().plus(budget), mode == Mode.HANG_DEADLINE ? Duration.ofSeconds(5) : Duration.ofMillis(700));
      var bytes = new ByteArrayOutputStream();
      var output = new PrintStream(bytes, true, StandardCharsets.UTF_8);
      Instant started = Instant.now();
      Runnable run = () -> LaboratoryGameSmokeRunner.run(api,
          mode == Mode.WAIT ? Duration.ofSeconds(10) : Duration.ofMillis(10), output);
      if (error == null) run.run();
      else assertTrue(assertThrows(AssertionError.class, run::run).getMessage().contains(error));
      String summary = bytes.toString(StandardCharsets.UTF_8);
      assertTrue(summary.contains("success=" + (error == null)));
      assertTrue(summary.contains("match=" + server.id));
      assertTrue(summary.contains("errorCode=" + (error == null ? "NONE" : error)));
      assertTrue(summary.contains("inputTokens=12 outputTokens=3 usageAvailable=true"));
      assertFalse(summary.contains("PRIVATE_RESPONSE_SENTINEL"));
      assertEquals(1, server.commands.get(), "the smoke must never retry or continue automatically");
      assertTrue(Duration.between(started, Instant.now()).compareTo(budget.plusSeconds(1)) < 0);
      if (mode == Mode.HANG || mode == Mode.HANG_DEADLINE)
        assertEquals(0, server.hung.getCount(), "the timeout must occur on an in-flight request");
    }
  }

  private enum Mode { FINISH, MODEL_ERROR, STATUS_ERROR, HANG, WAIT, HANG_DEADLINE }

  private static final class ApiFixture implements AutoCloseable {
    final UUID id = UUID.randomUUID();
    final HttpServer server;
    final AtomicInteger commands = new AtomicInteger(), polls = new AtomicInteger();
    final AtomicBoolean started = new AtomicBoolean();
    final CountDownLatch hung = new CountDownLatch(1), release = new CountDownLatch(1);

    ApiFixture(Mode mode) throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/api/v1/", exchange -> {
        int status = 200;
        String body;
        String path = exchange.getRequestURI().getPath();
        if (path.endsWith("/commands")) {
          commands.incrementAndGet(); started.set(true); body = "{}";
        } else if (path.endsWith("/decisions")) {
          body = "{\"items\":[{\"status\":\"COMPLETED\",\"usage\":{\"inputTokens\":12,\"outputTokens\":3}}"
              + (mode == Mode.MODEL_ERROR ? ",{\"status\":\"FAILED\",\"errorCode\":\"MODEL_UNAVAILABLE\"}" : "")
              + "],\"nextCursor\":null}";
        } else {
          int poll = started.get() ? polls.incrementAndGet() : 0;
          if (poll == 2 && (mode == Mode.HANG || mode == Mode.HANG_DEADLINE)) {
            hung.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
          }
          if (poll == 2 && mode == Mode.STATUS_ERROR) {
            status = 503; body = "PRIVATE_RESPONSE_SENTINEL";
          } else {
            String matchStatus = poll == 0 ? "READY" : mode == Mode.FINISH ? "FINISHED" : "RUNNING";
            String execution = mode == Mode.MODEL_ERROR ? "PAUSED" : "RUNNING";
            body = "{\"revision\":0,\"executionStatus\":\"" + execution + "\",\"currentMatch\":{\"matchId\":\""
                + id + "\",\"status\":\"" + matchStatus + "\",\"game\":{\"moveCount\":1},\"result\":{\"status\":\"WIN\"}}}";
          }
        }
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        try {
          exchange.sendResponseHeaders(status, data.length);
          exchange.getResponseBody().write(data);
        } finally { exchange.close(); }
      });
      server.start();
    }

    @Override public void close() { release.countDown(); server.stop(0); }
  }
}
