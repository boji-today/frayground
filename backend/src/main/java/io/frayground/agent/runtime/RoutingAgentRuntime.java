package io.frayground.agent.runtime;

import io.frayground.agent.*;
import io.frayground.domain.strategy.RuntimeType;
import java.util.concurrent.*;

/** Chooses the bound runtime type for every fixed decision. */
public final class RoutingAgentRuntime implements AgentRuntime {
  private final AgentRuntime mock; private final AgentRuntime llm;
  public RoutingAgentRuntime(AgentRuntime mock,AgentRuntime llm) { this.mock=mock; this.llm=llm; }
  private AgentRuntime select(RuntimeInput in) {
    var type=in.hostedBindings().map(b->b.configuration().runtimeType()).orElse(null);
    if (type==RuntimeType.MOCK) return mock; if (type==RuntimeType.LLM) return llm;
    throw new IllegalArgumentException("RUNTIME_TYPE_UNSUPPORTED");
  }
  public CompletionStage<RuntimeOutcome> decide(RuntimeInput in,CancellationSignal c) {
    try { return select(in).decide(in,c); } catch (RuntimeException e) { return CompletableFuture.completedFuture(new RuntimeOutcome.Failed(new RuntimeError("RUNTIME_TYPE_UNSUPPORTED","RUNTIME_TYPE_UNSUPPORTED"))); }
  }
  public CompletionStage<RuntimeExecutionResult> decideWithEvidence(RuntimeInput in,CancellationSignal c) {
    try { return select(in).decideWithEvidence(in,c); } catch (RuntimeException e) { return CompletableFuture.completedFuture(new RuntimeExecutionResult(new RuntimeOutcome.Failed(new RuntimeError("RUNTIME_TYPE_UNSUPPORTED","RUNTIME_TYPE_UNSUPPORTED")),null)); }
  }
}
