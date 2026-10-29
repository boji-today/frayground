package io.frayground.adapter.model;

import io.frayground.adapter.model.deepseek.DeepSeekChatRequest;
import io.frayground.adapter.model.deepseek.DeepSeekChatResponse;
import io.frayground.adapter.model.deepseek.DeepSeekPayloads;
import io.frayground.agent.CancellationSignal;
import io.frayground.agent.ModelAdapter;
import io.frayground.agent.ModelCapabilities;
import io.frayground.agent.ModelCredentialProvider;
import io.frayground.agent.ModelInvocationResult;
import io.frayground.agent.ModelMessage;
import io.frayground.agent.ModelRequest;
import io.frayground.agent.RuntimeError;
import io.frayground.agent.TokenUsage;
import io.frayground.agent.ToolArguments;
import io.frayground.domain.strategy.ModelConfiguration;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeoutException;
import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.async.methods.SimpleRequestBuilder;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.core5.http.ContentType;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * One DeepSeek chat completion call for {@code deepseek-flash}.
 *
 * <p>The adapter normalizes transport failures. It does not decide whether
 * the returned action is legal.
 */
public final class DeepSeekModelAdapter implements ModelAdapter {
  static final String PROVIDER = "deepseek";
  static final String MODEL = "deepseek-flash";
  static final int MAX_INPUT_BYTES = 32 * 1024;
  static final String TOOL_NAME = "submit_action";

  private static final Set<String> ROLES = Set.of("system", "user", "assistant");

  private final VendorHttpPool pool;
  private final ModelCredentialProvider credentials;
  private final URI baseUrl;
  private final Clock clock;

  public DeepSeekModelAdapter(
      VendorHttpPool pool, ModelCredentialProvider credentials, URI baseUrl) {
    this(pool, credentials, baseUrl, Clock.systemUTC());
  }
  public DeepSeekModelAdapter(VendorHttpPool pool, ModelCredentialProvider credentials, URI baseUrl, Clock clock) {
    this.pool = pool;
    this.credentials = credentials;
    this.baseUrl = baseUrl;
    this.clock = clock;
  }

  @Override
  public ModelCapabilities capabilities(ModelConfiguration configuration) {
    if (!supportedModel(configuration)) {
      return new ModelCapabilities(false, false, false, false, false, Set.of());
    }
    return new ModelCapabilities(
        false, true, true, false, true, DeepSeekPayloads.SCHEMA_KEYWORDS);
  }

  @Override
  public CompletionStage<ModelInvocationResult> invoke(
      ModelRequest request, CancellationSignal cancellation) {
    long started = System.nanoTime();
    RuntimeError rejected = reject(request, cancellation);
    if (rejected != null) {
      return CompletableFuture.completedFuture(failure(rejected, started));
    }
    long budgetMs = budget(request);
    if (budgetMs <= 0 || cancellation.isCancelled()) return CompletableFuture.completedFuture(failure(error(cancellation.isCancelled()?"DECISION_CANCELLED":"DECISION_EXPIRED", "decision is no longer active"), started));
    String apiKey = credentials.resolve(request.configuration()).orElse(null);
    if (apiKey == null) {
      return CompletableFuture.completedFuture(failure(configurationError(), started));
    }
    budgetMs = budget(request);
    if (budgetMs <= 0 || cancellation.isCancelled()) return CompletableFuture.completedFuture(failure(error(cancellation.isCancelled()?"DECISION_CANCELLED":"DECISION_EXPIRED", "decision is no longer active"), started));
    SimpleHttpRequest http = httpRequest(request, apiKey, budgetMs);
    return pool.execute(http, budgetMs)
        .handle((response, error) -> {
          ModelInvocationResult normalized=response==null
              ? toResult(null,error,started)
              : toResult(response,null,started);
          if (cancellation.isCancelled()) return withError(normalized,error("DECISION_CANCELLED","decision was cancelled"));
          if (request.deadlineAt()!=null && !clock.instant().isBefore(request.deadlineAt()))
            return withError(normalized,error("DECISION_EXPIRED","decision expired"));
          return normalized;
        });
  }

  private static ModelInvocationResult withError(ModelInvocationResult result,RuntimeError error) {
    return new ModelInvocationResult(result.providerRequestId(),result.actualModel(),result.content(),result.toolArguments(),
        result.finishReason(),result.usage(),result.latency(),error);
  }

  private RuntimeError reject(ModelRequest request, CancellationSignal cancellation) {
    if (cancellation.isCancelled()) {
      return error("DECISION_CANCELLED", "decision was cancelled");
    }
    ModelConfiguration configuration = request.configuration();
    if (!supportedModel(configuration) || configuration.thinking()) {
      return configurationError();
    }
    String strategy = configuration.outputStrategy();
    if (!"JSON_MODE".equals(strategy) && !"STRICT_TOOL".equals(strategy)) {
      return configurationError();
    }
    if (request.messages().isEmpty()) {
      return configurationError();
    }
    for (ModelMessage message : request.messages()) {
      if (!ROLES.contains(message.role())) {
        return configurationError();
      }
    }
    if (utf8Bytes(request.messages()) > MAX_INPUT_BYTES) {
      return error("CONTEXT_TOO_LARGE", "context is too large");
    }
    if (DeepSeekPayloads.schema(request.outputSchemaJson()).isEmpty()) {
      return configurationError();
    }
    if (!credentials.supports(configuration)) {
      return configurationError();
    }
    return null;
  }

  private SimpleHttpRequest httpRequest(ModelRequest request, String apiKey, long budgetMs) {
    ModelConfiguration configuration = request.configuration();
    boolean strictTool = "STRICT_TOOL".equals(configuration.outputStrategy());
    JsonNode schema = DeepSeekPayloads.schema(request.outputSchemaJson()).orElseThrow();
    List<DeepSeekChatRequest.Message> messages = request.messages().stream()
        .map(message -> new DeepSeekChatRequest.Message(message.role(), message.text()))
        .toList();
    DeepSeekChatRequest body = new DeepSeekChatRequest(
        configuration.modelId(),
        messages,
        configuration.temperature(),
        configuration.maxOutputTokens(),
        false,
        new DeepSeekChatRequest.Thinking("disabled"),
        strictTool ? null : new DeepSeekChatRequest.ResponseFormat("json_object"),
        strictTool ? List.of(tool(schema)) : null,
        strictTool ? toolChoice() : null);
    String path = strictTool ? "/beta/chat/completions" : "/chat/completions";
    return SimpleRequestBuilder.post(endpoint(path))
        .setHeader("Authorization", "Bearer " + apiKey)
        .setBody(DeepSeekPayloads.writeRequest(body), ContentType.APPLICATION_JSON)
        .setRequestConfig(RequestConfig.custom()
            .setConnectionRequestTimeout(budgetMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            .setResponseTimeout(budgetMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build())
        .build();
  }

  private long budget(ModelRequest request) {
    long configured=request.configuration().decisionTimeoutMs();
    if (request.deadlineAt()==null) return configured;
    long remaining=java.time.Duration.between(clock.instant(),request.deadlineAt()).toMillis();
    return Math.min(configured,remaining);
  }

  private ModelInvocationResult toResult(
      SimpleHttpResponse response, Throwable error, long started) {
    if (error != null) {
      Throwable cause = unwrap(error);
      if (timedOut(cause)) {
        return failure(error("MODEL_TIMEOUT", "model timed out"), started);
      }
      return failure(error("MODEL_UNAVAILABLE", "model is unavailable"), started);
    }
    int status = response.getCode();
    if (status == 429) {
      return failure(error("MODEL_RATE_LIMITED", "model rate limited"), started);
    }
    if (status < 200 || status >= 300) {
      return failure(error("MODEL_UNAVAILABLE", "model is unavailable"), started);
    }
    String body = response.getBodyText();
    if (body == null || body.isBlank()) {
      return failure(error("MODEL_OUTPUT_INVALID", "model output is invalid"), started);
    }
    try {
      return fromResponse(DeepSeekPayloads.readResponse(body), started);
    } catch (JacksonException exception) {
      return failure(error("MODEL_OUTPUT_INVALID", "model output is invalid"), started);
    }
  }

  private ModelInvocationResult fromResponse(DeepSeekChatResponse response, long started) {
    if (response.choices() == null || response.choices().isEmpty()) {
      return failure(error("MODEL_OUTPUT_EMPTY", "model output is empty"), started);
    }
    DeepSeekChatResponse.Choice choice = response.choices().get(0);
    DeepSeekChatResponse.Message message = choice.message();
    String finishReason = choice.finishReason();
    List<ToolArguments> tools = toolArguments(message);
    RuntimeError outputError = outputError(message, finishReason, tools);
    String content = message == null ? null : message.content();
    return new ModelInvocationResult(
        response.id(),
        response.model(),
        content,
        tools,
        finishReason,
        usage(response.usage()),
        latency(started),
        outputError);
  }

  private static RuntimeError outputError(
      DeepSeekChatResponse.Message message, String finishReason, List<ToolArguments> tools) {
    if ("length".equals(finishReason)) {
      return error("MODEL_OUTPUT_TRUNCATED", "model output was truncated");
    }
    if ("content_filter".equals(finishReason)
        || (message != null && message.refusal() != null && !message.refusal().isBlank())) {
      return error("MODEL_REFUSED", "model refused");
    }
    if (tools.size() > 1) {
      return error("MODEL_OUTPUT_INVALID", "model output is invalid");
    }
    boolean hasContent = message != null && message.content() != null && !message.content().isBlank();
    if (tools.isEmpty() && !hasContent) {
      return error("MODEL_OUTPUT_EMPTY", "model output is empty");
    }
    if (message != null && message.toolCalls() != null && message.toolCalls().size() > tools.size()) {
      return error("MODEL_OUTPUT_INVALID", "model output is invalid");
    }
    return null;
  }

  private static List<ToolArguments> toolArguments(DeepSeekChatResponse.Message message) {
    if (message == null || message.toolCalls() == null) {
      return List.of();
    }
    return message.toolCalls().stream()
        .filter(call -> call.function() != null
            && call.function().arguments() != null
            && !call.function().arguments().isBlank()
            && (call.function().name() == null || !call.function().name().isBlank()))
        .map(call -> new ToolArguments(call.function().name(), call.function().arguments()))
        .toList();
  }

  private static TokenUsage usage(DeepSeekChatResponse.Usage usage) {
    if (usage == null) {
      return null;
    }
    return new TokenUsage(
        usage.promptTokens(),
        usage.completionTokens(),
        usage.promptCacheHitTokens(),
        usage.promptCacheMissTokens());
  }

  private static DeepSeekChatRequest.Tool tool(JsonNode schema) {
    return new DeepSeekChatRequest.Tool(
        "function",
        new DeepSeekChatRequest.Function(
            TOOL_NAME, true, DeepSeekPayloads.supportedSchema(schema)));
  }

  private static DeepSeekChatRequest.ToolChoice toolChoice() {
    return new DeepSeekChatRequest.ToolChoice(
        "function", new DeepSeekChatRequest.NamedFunction(TOOL_NAME));
  }

  private URI endpoint(String path) {
    String base = baseUrl.toString();
    if (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    return URI.create(base + path);
  }

  private static boolean supportedModel(ModelConfiguration configuration) {
    return PROVIDER.equals(configuration.provider()) && MODEL.equals(configuration.modelId());
  }

  private static int utf8Bytes(List<ModelMessage> messages) {
    int total = 0;
    for (ModelMessage message : messages) {
      total += message.text().getBytes(StandardCharsets.UTF_8).length;
    }
    return total;
  }

  private static ModelInvocationResult failure(RuntimeError error, long started) {
    return new ModelInvocationResult(
        null, null, null, List.of(), null, null, latency(started), error);
  }

  private static Duration latency(long started) {
    return Duration.ofNanos(System.nanoTime() - started);
  }

  private static RuntimeError configurationError() {
    return error("MODEL_CONFIGURATION_INVALID", "configuration is invalid");
  }

  private static RuntimeError error(String code, String message) {
    return new RuntimeError(code, message);
  }

  private static Throwable unwrap(Throwable error) {
    Throwable current = error;
    while (current instanceof CompletionException && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private static boolean timedOut(Throwable error) {
    for (Throwable current = error; current != null; current = current.getCause()) {
      if (current instanceof TimeoutException || current instanceof SocketTimeoutException
          || current.getClass().getSimpleName().contains("Timeout")) {
        return true;
      }
    }
    return false;
  }
}
