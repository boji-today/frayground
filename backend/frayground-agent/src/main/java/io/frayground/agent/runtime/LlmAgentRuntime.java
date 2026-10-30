package io.frayground.agent.runtime;

import io.frayground.agent.*;
import io.frayground.agent.input.DecisionInputReader;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;

/** Stateless hosted LLM decision flow. It only returns a selected candidate. */
public final class LlmAgentRuntime implements AgentRuntime {
  private final AgentContextBuilder builder; private final DecisionInputReader reader; private final ModelAdapter adapter;
  private final DecisionModelAudit audit; private final SelectionOutputCodec codec; private final Clock clock;
  public LlmAgentRuntime(AgentContextBuilder builder, DecisionInputReader reader, ModelAdapter adapter, DecisionModelAudit audit, Clock clock) {
    this.builder=builder; this.reader=reader; this.adapter=adapter; this.audit=audit; this.clock=clock; this.codec=new SelectionOutputCodec();
  }
  @Override public CompletionStage<RuntimeOutcome> decide(RuntimeInput input,CancellationSignal cancellation) {
    return decideWithEvidence(input,cancellation).thenApply(RuntimeExecutionResult::outcome);
  }
  @Override public CompletionStage<RuntimeExecutionResult> decideWithEvidence(RuntimeInput input,CancellationSignal cancellation) {
    var bound=input.hostedBindings().orElse(null);
    if (bound==null) return completed("BINDINGS_NOT_FOUND");
    var config=bound.configuration(); var model=config.model(); var req=input.publicRequest();
    if (config.runtimeType()!=io.frayground.domain.strategy.RuntimeType.LLM) return completed("RUNTIME_TYPE_MISMATCH");
    if (!"JSON_MODE".equals(model.outputStrategy()) && !("STRICT_TOOL".equals(model.outputStrategy())&&model.allowBeta())) return completed("MODEL_CONFIGURATION_INVALID");
    if (cancellation.isCancelled()) return completed("DECISION_CANCELLED");
    if (!clock.instant().isBefore(req.deadlineAt())) return completed("DECISION_EXPIRED");
    DecisionInputReader.ParsedSpecification spec;
    BuiltContext context; String schema; ModelCapabilities caps;
    try {
      spec=reader.readSpecification(req);
      if (!"ACTION_SELECTION".equals(spec.mode())) return completed("ACTION_MODE_NOT_SUPPORTED");
      var ids=new ArrayList<String>(); spec.options().forEach(o->ids.add(o.get("selectedActionId").asString()));
      schema=codec.schema(ids); context=builder.build(req,bound.prompt(),config); caps=adapter.capabilities(model);
      if ("JSON_MODE".equals(model.outputStrategy())&&!caps.jsonMode() || "STRICT_TOOL".equals(model.outputStrategy())&&(!caps.strictTools()||caps.betaRequired()&&!model.allowBeta())) return completed("MODEL_OUTPUT_MODE_UNSUPPORTED");
      if (!caps.supportedSchemaKeywords().containsAll(Set.of("type","properties","required","additionalProperties","enum"))) return completed("MODEL_SCHEMA_UNSUPPORTED");
    } catch (RuntimeException e) { return completed(code(e,"INVALID_DECISION_INPUT")); }
    Map<String,Object> params=Map.of("provider",model.provider(),"modelId",model.modelId(),"temperature",model.temperature(),"maxOutputTokens",model.maxOutputTokens(),"outputStrategy",model.outputStrategy());
    try { if (!audit.recordInput(req.decisionId(),context,schema,params)) return completed("MODEL_INPUT_AUDIT_FAILED"); }
    catch (RuntimeException e) { return completed("MODEL_INPUT_AUDIT_FAILED"); }
    if (cancellation.isCancelled()||!clock.instant().isBefore(req.deadlineAt())) return completed(cancellation.isCancelled()?"DECISION_CANCELLED":"DECISION_EXPIRED");
    ModelRequest request=new ModelRequest(context.messages(),schema,model,req.deadlineAt());
    try {
      return adapter.invoke(request,cancellation).handle((result,error)->{
        if (error!=null) result=new ModelInvocationResult(null,null,null,List.of(),null,null,null,new RuntimeError("MODEL_UNAVAILABLE","MODEL_UNAVAILABLE"));
        Set<String> allowed=new HashSet<>(); spec.options().forEach(o->allowed.add(o.get("selectedActionId").asString()));
        return new RuntimeExecutionResult(codec.parse(result,model.outputStrategy(),allowed),result);
      });
    } catch (RuntimeException e) { return completed("MODEL_UNAVAILABLE"); }
  }
  private static CompletionStage<RuntimeExecutionResult> completed(String code) { var error=new RuntimeError(code,code); return CompletableFuture.completedFuture(new RuntimeExecutionResult(new RuntimeOutcome.Failed(error),null)); }
  private static String code(RuntimeException e,String fallback) { return e.getMessage()!=null&&e.getMessage().matches("[A-Z0-9_]+")?e.getMessage():fallback; }
}
