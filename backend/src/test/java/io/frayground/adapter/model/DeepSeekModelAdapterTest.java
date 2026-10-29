package io.frayground.adapter.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.frayground.agent.CancellationSignal;
import io.frayground.agent.ModelCredentialProvider;
import io.frayground.agent.ModelInvocationResult;
import io.frayground.agent.ModelMessage;
import io.frayground.agent.ModelRequest;
import io.frayground.domain.strategy.ModelConfiguration;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class DeepSeekModelAdapterTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final CancellationSignal OPEN = () -> false;

  private final List<VendorHttpPool> pools = new ArrayList<>();
  private final List<HttpServer> servers = new ArrayList<>();
  private final List<ExecutorService> executors = new ArrayList<>();

  @AfterEach
  void closeResources() {
    servers.forEach(server -> server.stop(0));
    executors.forEach(ExecutorService::shutdownNow);
    pools.forEach(VendorHttpPool::close);
  }

  @Test
  void postsJsonModeUsingTheConfiguredRequest() throws Exception {
    Captured captured = new Captured();
    DeepSeekModelAdapter adapter = adapter(server(500, "test-key", captured), key("test-key"), 2);
    ModelConfiguration configuration = configuration("JSON_MODE", false, 1_000, "system:deepseek-flash", 64, 0.25);

    ModelInvocationResult result = adapter.invoke(request(configuration, "pick a column"), OPEN)
        .toCompletableFuture().join();

    assertThat(captured.count.get()).isEqualTo(1);
    assertThat(captured.path).isEqualTo("/chat/completions");
    assertThat(captured.authorization).isEqualTo("Bearer test-key");
    assertThat(captured.body).doesNotContain("test-key");
    JsonNode json = JSON.readTree(captured.body);
    assertThat(json.path("model").asString()).isEqualTo("deepseek-flash");
    assertThat(json.path("temperature").asDouble()).isEqualTo(0.25);
    assertThat(json.path("max_tokens").asInt()).isEqualTo(64);
    assertThat(json.path("stream").asBoolean()).isFalse();
    assertThat(json.path("thinking").path("type").asString()).isEqualTo("disabled");
    assertThat(json.path("response_format").path("type").asString()).isEqualTo("json_object");
    assertThat(json.path("tools").isMissingNode()).isTrue();
    assertThat(result.error()).isNotNull();
    assertThat(result.error().message()).doesNotContain("test-key");
  }

  @Test
  void strictToolUsesTheBetaPath() throws Exception {
    Captured captured = new Captured();
    AtomicInteger resolves = new AtomicInteger();
    DeepSeekModelAdapter adapter = adapter(server(500, "no", captured), key("test-key", resolves), 2);
    ModelConfiguration configuration = configuration("STRICT_TOOL", true, 1_000, "system:deepseek-flash", 32, 0);
    String schema = """
        {"type":"object","additionalProperties":false,"properties":{"column":{"type":"integer","minimum":0,"description":"col"}},"required":["column"],"minLength":1}""";

    adapter.invoke(new ModelRequest(
        List.of(new ModelMessage("user", "pick")), schema, configuration), OPEN)
        .toCompletableFuture().join();

    assertThat(resolves.get()).isEqualTo(1);
    assertThat(captured.count.get()).isEqualTo(1);
    assertThat(captured.path).isEqualTo("/beta/chat/completions");
    JsonNode json = JSON.readTree(captured.body);
    assertThat(json.path("response_format").isMissingNode()).isTrue();
    JsonNode parameters = json.path("tools").get(0).path("function").path("parameters");
    assertThat(json.path("tools").get(0).path("function").path("name").asString()).isEqualTo("submit_action");
    assertThat(json.path("tools").get(0).path("function").path("strict").asBoolean()).isTrue();
    assertThat(json.path("tool_choice").path("function").path("name").asString()).isEqualTo("submit_action");
    assertThat(parameters.path("type").asString()).isEqualTo("object");
    assertThat(parameters.path("additionalProperties").asBoolean()).isFalse();
    assertThat(parameters.path("properties").path("column").path("type").asString()).isEqualTo("integer");
    assertThat(parameters.path("properties").path("column").path("description").asString()).isEqualTo("col");
    assertThat(parameters.path("properties").path("column").path("minimum").isMissingNode()).isTrue();
    assertThat(parameters.path("required").get(0).asString()).isEqualTo("column");
    assertThat(parameters.path("minLength").isMissingNode()).isTrue();
  }

  @Test
  void authorizesOnlyTheMatchingSystemKey() throws Exception {
    Captured captured = new Captured();
    SystemModelCredentialProvider system = new SystemModelCredentialProvider(
        Map.of("deepseek-flash", "flash-key", "other-model", "other-key"));
    CompositeModelCredentialProvider credentials = new CompositeModelCredentialProvider(List.of(system));
    DeepSeekModelAdapter adapter = adapter(server(500, "no", captured), credentials, 2);

    ModelInvocationResult mismatched = adapter.invoke(
        request(configuration("JSON_MODE", false, 1_000, "system:other-model", 16, 0), "a"), OPEN)
        .toCompletableFuture().join();

    assertThat(mismatched.error()).isNotNull();
    assertThat(mismatched.error().code()).isEqualTo("MODEL_CONFIGURATION_INVALID");
    assertThat(mismatched.error().message()).doesNotContain("flash-key").doesNotContain("other-key");
    assertThat(captured.count.get()).isZero();

    ModelInvocationResult sent = adapter.invoke(
        request(configuration("JSON_MODE", false, 1_000, "system:deepseek-flash", 16, 0), "a"), OPEN)
        .toCompletableFuture().join();

    assertThat(captured.count.get()).isEqualTo(1);
    assertThat(captured.authorization).isEqualTo("Bearer flash-key");
    assertThat(captured.body).doesNotContain("flash-key").doesNotContain("other-key");
    assertThat(sent.error()).isNotNull();
    assertThat(sent.error().message()).doesNotContain("flash-key").doesNotContain("other-key");
  }

  @Test
  void rejectsInvalidCallsBeforeHttp() throws Exception {
    Captured captured = new Captured();
    DeepSeekModelAdapter adapter = adapter(server(500, "no", captured), key(""), 2);
    List<ModelRequest> rejected = List.of(
        request(configuration("openai", "deepseek-flash", false, "JSON_MODE", false, "system:deepseek-flash"), "a"),
        request(configuration("deepseek", "other", false, "JSON_MODE", false, "system:deepseek-flash"), "a"),
        request(configuration("deepseek", "deepseek-flash", true, "JSON_MODE", false, "system:deepseek-flash"), "a"),
        request(configuration("TEXT", false, 1_000, "system:deepseek-flash", 16, 0), "a"),
        new ModelRequest(List.of(new ModelMessage("user", "a")), "{",
            configuration("JSON_MODE", false, 1_000, "system:deepseek-flash", 16, 0)),
        request(configuration("JSON_MODE", false, 1_000, "user:account", 16, 0), "a"),
        request(configuration("JSON_MODE", false, 1_000, null, 16, 0), "a"),
        request(configuration("JSON_MODE", false, 1_000, "system:deepseek-flash", 16, 0), "a"),
        request(configuration("JSON_MODE", false, 1_000, "system:deepseek-flash", 16, 0),
            "a".repeat(DeepSeekModelAdapter.MAX_INPUT_BYTES + 1)));

    for (ModelRequest call : rejected) {
      ModelInvocationResult result = adapter.invoke(call, OPEN).toCompletableFuture().join();
      assertThat(result.error()).isNotNull();
    }
    ModelInvocationResult cancelled = adapter.invoke(
        request(configuration("JSON_MODE", false, 1_000, "system:deepseek-flash", 16, 0), "a"),
        () -> true).toCompletableFuture().join();

    assertThat(cancelled.error()).isNotNull();
    assertThat(captured.count.get()).isZero();
  }

  @Test
  void httpFailureIsReturnedOnce() throws Exception {
    Captured captured = new Captured();
    DeepSeekModelAdapter adapter = adapter(server(500, "test-key", captured), key("test-key"), 2);

    ModelInvocationResult result = adapter.invoke(
        request(configuration("JSON_MODE", false, 1_000, "system:deepseek-flash", 16, 0), "a"), OPEN)
        .toCompletableFuture().join();

    assertThat(captured.count.get()).isEqualTo(1);
    assertThat(result.error()).isNotNull();
    assertThat(result.error().message()).doesNotContain("test-key");
  }

  @Test
  void waitsForAConnectionInsideTheDecisionBudget() throws Exception {
    CountDownLatch received = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger accepted = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    server.setExecutor(executor);
    server.createContext("/chat/completions", exchange -> {
      try {
        exchange.getRequestBody().readAllBytes();
        accepted.incrementAndGet();
        received.countDown();
        release.await(5, TimeUnit.SECONDS);
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(500, body.length);
        exchange.getResponseBody().write(body);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
      } finally {
        exchange.close();
      }
    });
    server.start();
    servers.add(server);
    executors.add(executor);
    DeepSeekModelAdapter adapter = adapter(server.getAddress().getPort(), key("test-key"), 1);
    ModelConfiguration configuration = configuration("JSON_MODE", false, 1_000, "system:deepseek-flash", 16, 0);
    CompletableFuture<ModelInvocationResult> first = adapter.invoke(request(configuration, "hold"), OPEN)
        .toCompletableFuture();
    assertThat(received.await(2, TimeUnit.SECONDS)).isTrue();

    int before = ManagementFactory.getThreadMXBean().getThreadCount();
    List<CompletableFuture<ModelInvocationResult>> waiting = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      waiting.add(adapter.invoke(request(configuration, "wait"), OPEN).toCompletableFuture());
    }
    Thread.sleep(100);
    int created = ManagementFactory.getThreadMXBean().getThreadCount() - before;

    assertThat(created).isLessThan(waiting.size());
    for (CompletableFuture<ModelInvocationResult> future : waiting) {
      assertThat(future.get(3, TimeUnit.SECONDS).error().code()).isEqualTo("MODEL_TIMEOUT");
    }
    release.countDown();
    first.get(3, TimeUnit.SECONDS);
    assertThat(accepted.get()).isLessThan(1 + waiting.size());
  }

  @Test
  void reportsDeepSeekCapabilities() {
    DeepSeekModelAdapter adapter = adapter(9, key("test-key"), 1);
    var capabilities = adapter.capabilities(
        configuration("JSON_MODE", false, 1_000, "system:deepseek-flash", 16, 0));

    assertThat(capabilities.jsonMode()).isTrue();
    assertThat(capabilities.strictTools()).isTrue();
    assertThat(capabilities.betaRequired()).isTrue();
    assertThat(capabilities.nativeJsonSchema()).isFalse();
    assertThat(capabilities.plainJson()).isFalse();
  }

  private DeepSeekModelAdapter adapter(int port, ModelCredentialProvider credentials, int maxConnections) {
    VendorHttpPool pool = new VendorHttpPool("deepseek", maxConnections);
    pools.add(pool);
    return new DeepSeekModelAdapter(pool, credentials, URI.create("http://127.0.0.1:" + port));
  }

  private int server(int status, String body, Captured captured) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    server.setExecutor(executor);
    server.createContext("/", exchange -> {
      captured.count.incrementAndGet();
      captured.path = exchange.getRequestURI().getPath();
      captured.authorization = exchange.getRequestHeaders().getFirst("Authorization");
      captured.body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      byte[] payload = body.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, payload.length);
      exchange.getResponseBody().write(payload);
      exchange.close();
    });
    server.start();
    servers.add(server);
    executors.add(executor);
    return server.getAddress().getPort();
  }

  private static ModelRequest request(ModelConfiguration configuration, String text) {
    return new ModelRequest(
        List.of(new ModelMessage("user", text)), "{\"type\":\"object\"}", configuration);
  }

  private static ModelConfiguration configuration(
      String strategy, boolean allowBeta, int timeoutMs, String credentialRef, int maxTokens,
      double temperature) {
    return configuration("deepseek", "deepseek-flash", false, strategy, allowBeta, credentialRef,
        timeoutMs, maxTokens, temperature);
  }

  private static ModelConfiguration configuration(
      String provider, String modelId, boolean thinking, String strategy, boolean allowBeta,
      String credentialRef) {
    return configuration(provider, modelId, thinking, strategy, allowBeta, credentialRef, 1_000, 16, 0);
  }

  private static ModelConfiguration configuration(
      String provider, String modelId, boolean thinking, String strategy, boolean allowBeta,
      String credentialRef, int timeoutMs, int maxTokens, double temperature) {
    return new ModelConfiguration(
        provider, modelId, thinking, temperature, maxTokens, timeoutMs, credentialRef, strategy, allowBeta);
  }

  private static ModelCredentialProvider key(String apiKey) {
    return key(apiKey, null);
  }

  private static ModelCredentialProvider key(String apiKey, AtomicInteger resolves) {
    return new ModelCredentialProvider() {
      @Override
      public boolean supports(ModelConfiguration configuration) {
        return configuration.credentialRef() != null
            && configuration.credentialRef().startsWith("system:")
            && configuration.credentialRef().substring("system:".length()).equals(configuration.modelId());
      }

      @Override
      public Optional<String> resolve(ModelConfiguration configuration) {
        if (resolves != null) {
          resolves.incrementAndGet();
        }
        return apiKey == null || apiKey.isBlank() ? Optional.empty() : Optional.of(apiKey);
      }
    };
  }

  private static final class Captured {
    private final AtomicInteger count = new AtomicInteger();
    private String path;
    private String authorization;
    private String body = "";
  }
}
