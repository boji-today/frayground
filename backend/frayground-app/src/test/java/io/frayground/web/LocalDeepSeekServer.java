package io.frayground.web;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Controllable loopback DeepSeek endpoint for offline acceptance tests. */
final class LocalDeepSeekServer implements AutoCloseable {
  private final boolean winningSequence;

  LocalDeepSeekServer() { this(true); }
  LocalDeepSeekServer(boolean winningSequence) {
    this.winningSequence = winningSequence;
    modelServer = startModelServer();
  }

  int port() { return modelServer.getAddress().getPort(); }
  @Override public void close() { modelServer.stop(0); }

  void reset() {
    requests.set(0); traces.clear(); nextStatus.set(200); nextContent.set(null);
    blockNext.set(false); responseReceived.set(null); releaseResponse.set(null); responseSent.set(null);
  }
  final AtomicInteger requests = new AtomicInteger();
  final List<RequestTrace> traces = new CopyOnWriteArrayList<>();
  final AtomicInteger nextStatus = new AtomicInteger(200);
  final AtomicReference<String> nextContent = new AtomicReference<>();
  final AtomicBoolean blockNext = new AtomicBoolean();
  final AtomicReference<CountDownLatch> responseReceived = new AtomicReference<>();
  final AtomicReference<CountDownLatch> releaseResponse = new AtomicReference<>();
  final AtomicReference<CountDownLatch> responseSent = new AtomicReference<>();
  final HttpServer modelServer;

  private HttpServer startModelServer() {
    try {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/chat/completions", exchange -> {
        requests.incrementAndGet();
        JsonMapper mapper = JsonMapper.builder().build();
        JsonNode request = mapper.readTree(exchange.getRequestBody().readAllBytes());
        String prompt = null;
        JsonNode candidates = null;
        int moveCount = 0;
        for (JsonNode message : request.path("messages")) {
          String content = message.path("content").asText();
          if (content.contains("playerPrompt")) prompt = mapper.readTree(content).path("playerPrompt").asText();
          if (content.contains("\"options\"")) {
            JsonNode state = mapper.readTree(content);
            candidates = state.path("options");
            moveCount = state.path("observation").path("moveCount").asInt();
          }
        }
        if (candidates == null || candidates.isEmpty()) throw new IllegalStateException("request has no candidates");
        int[] winningColumns = {0, 6, 1, 6, 2, 5, 3};
        String selected = candidates.get(0).path("selectedActionId").asText();
        if (winningSequence && moveCount < winningColumns.length) for (JsonNode option : candidates) {
          if (option.path("action").path("column").asInt(-1) == winningColumns[moveCount])
            selected = option.path("selectedActionId").asText();
        }
        List<String> offeredIds = new java.util.ArrayList<>();
        candidates.forEach(option -> offeredIds.add(option.path("selectedActionId").asText()));
        traces.add(new RequestTrace(prompt, offeredIds, selected));
        CountDownLatch received = responseReceived.get(), release = releaseResponse.get();
        if (blockNext.compareAndSet(true, false) && received != null && release != null) {
          received.countDown();
          try {
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("fake response release timed out");
          } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        }
        int status = nextStatus.getAndSet(200);
        if (status != 200) {
          byte[] failure = "{\"error\":{\"message\":\"loopback failure\"}}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, failure.length);
          exchange.getResponseBody().write(failure);
          exchange.close();
          return;
        }
        String content = nextContent.getAndSet(null);
        if (content == null) content = "{\"selectedActionId\":\"" + selected + "\"}";
        String encodedContent = mapper.writeValueAsString(content);
        byte[] body = ("{\"id\":\"loopback\",\"model\":\"deepseek-flash\",\"choices\":[{" +
            "\"message\":{\"content\":" + encodedContent + "},\"finish_reason\":\"stop\"}]," +
            "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2}}")
            .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
        CountDownLatch sent = responseSent.get(); if (sent != null) sent.countDown();
      });
      server.start();
      return server;
    } catch (Exception e) { throw new ExceptionInInitializerError(e); }
  }
  record RequestTrace(String prompt, List<String> candidates, String selectedActionId) {}
}
