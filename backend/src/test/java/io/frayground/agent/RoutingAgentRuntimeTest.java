package io.frayground.agent;

import static org.junit.jupiter.api.Assertions.*;

import io.frayground.agent.runtime.RoutingAgentRuntime;
import io.frayground.domain.strategy.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RoutingAgentRuntimeTest {
  @Test void routesByBoundTypeAndForwardsEvidence() {
    AtomicInteger mockCalls=new AtomicInteger(),llmCalls=new AtomicInteger();
    AgentRuntime mock=(i,c)->{mockCalls.incrementAndGet();return CompletableFuture.completedFuture(new RuntimeOutcome.SelectedAction("mock"));};
    ModelInvocationResult evidence=new ModelInvocationResult("request",null,"{}",List.of(),"stop",null,null,null);
    AgentRuntime llm=new AgentRuntime() {
      public CompletionStage<RuntimeOutcome> decide(RuntimeInput i,CancellationSignal c) { llmCalls.incrementAndGet();return CompletableFuture.completedFuture(new RuntimeOutcome.SelectedAction("llm")); }
      public CompletionStage<RuntimeExecutionResult> decideWithEvidence(RuntimeInput i,CancellationSignal c) { llmCalls.incrementAndGet();return CompletableFuture.completedFuture(new RuntimeExecutionResult(new RuntimeOutcome.SelectedAction("llm"),evidence)); }
    };
    var router=new RoutingAgentRuntime(mock,llm); Instant now=Instant.parse("2026-10-29T00:00:00Z");
    RuntimeInput llmInput=input(now,RuntimeType.LLM); var llmResult=router.decideWithEvidence(llmInput,()->false).toCompletableFuture().join();
    assertEquals(evidence,llmResult.modelResult()); assertEquals("llm",((RuntimeOutcome.SelectedAction)llmResult.outcome()).selectedActionId());
    assertEquals("mock",((RuntimeOutcome.SelectedAction)router.decide(input(now,RuntimeType.MOCK),()->false).toCompletableFuture().join()).selectedActionId());
    assertEquals(1,llmCalls.get()); assertEquals(1,mockCalls.get());
    assertInstanceOf(RuntimeOutcome.Failed.class,router.decide(input(now,null),()->false).toCompletableFuture().join());
  }
  private static RuntimeInput input(Instant now,RuntimeType type) {
    var request=new DecisionRequest("1.0",UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),0,0,"game","v1","schema","{}","{}","{}","{}",now.plusSeconds(5),null);
    if (type==null) return new RuntimeInput(request,Optional.empty());
    UUID agent=UUID.randomUUID(); var prompt=new PromptVersion(UUID.randomUUID(),agent,1,1,"p",now);
    var configuration=new AgentConfiguration(UUID.randomUUID(),agent,1,type,new ModelConfiguration("provider","model",false,0,10,1000,null,"JSON_MODE",false),new MemoryPolicy(MemoryMode.STATELESS),now);
    return new RuntimeInput(request,Optional.of(new HostedBindings(prompt,configuration)));
  }
}
