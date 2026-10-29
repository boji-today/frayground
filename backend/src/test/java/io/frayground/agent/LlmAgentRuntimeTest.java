package io.frayground.agent;

import static org.junit.jupiter.api.Assertions.*;

import io.frayground.agent.context.StatelessContextBuilder;
import io.frayground.agent.input.DecisionInputReader;
import io.frayground.agent.runtime.LlmAgentRuntime;
import io.frayground.domain.strategy.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LlmAgentRuntimeTest {
  @Test void auditsInputBeforeOneCallAndReturnsModelEvidence() {
    Instant now=Instant.parse("2026-10-29T00:00:00Z"); Clock clock=Clock.fixed(now,ZoneOffset.UTC);
    DecisionRequest request=request(now.plusSeconds(30)); UUID agent=UUID.randomUUID();
    var config=new AgentConfiguration(UUID.randomUUID(),agent,1,RuntimeType.LLM,
        new ModelConfiguration("deepseek","deepseek-flash",false,0,128,10000,"system:key","JSON_MODE",false),
        new MemoryPolicy(MemoryMode.STATELESS),now);
    var prompt=new PromptVersion(UUID.randomUUID(),agent,1,1,"choose",now);
    var auditOrder=new ArrayList<String>(); AtomicInteger calls=new AtomicInteger();
    DecisionModelAudit audit=new DecisionModelAudit() {
      public boolean recordInput(UUID id,BuiltContext context,String schema,Map<String,Object> parameters) {
        auditOrder.add("input"); assertTrue(schema.contains("\"enum\":[\"a\"]")); return true;
      }
      public void recordOutput(UUID id,ModelInvocationResult result) { auditOrder.add("output"); }
    };
    ModelAdapter adapter=new ModelAdapter() {
      public ModelCapabilities capabilities(ModelConfiguration ignored) { return new ModelCapabilities(false,false,true,false,true,Set.of("type","properties","required","additionalProperties","enum")); }
      public CompletionStage<ModelInvocationResult> invoke(ModelRequest modelRequest,CancellationSignal cancellation) {
        calls.incrementAndGet(); auditOrder.add("invoke"); assertEquals(request.deadlineAt(),modelRequest.deadlineAt());
        return CompletableFuture.completedFuture(new ModelInvocationResult("req-1","deepseek-flash","{\"selectedActionId\":\"a\"}",List.of(),"stop",null,Duration.ofMillis(4),null));
      }
    };
    var reader=new DecisionInputReader(); var runtime=new LlmAgentRuntime(new StatelessContextBuilder(reader,32768),reader,adapter,audit,clock);
    var input=new RuntimeInput(request,Optional.of(new HostedBindings(prompt,config)));
    RuntimeExecutionResult result=runtime.decideWithEvidence(input,()->false).toCompletableFuture().join();
    assertEquals(new RuntimeOutcome.SelectedAction("a"),result.outcome());
    assertEquals(1,calls.get()); assertEquals(List.of("input","invoke"),auditOrder); assertEquals("req-1",result.modelResult().providerRequestId());
  }
  @Test void inputAuditFailurePreventsModelCall() {
    Instant now=Instant.parse("2026-10-29T00:00:00Z"); Clock clock=Clock.fixed(now,ZoneOffset.UTC);
    var reader=new DecisionInputReader(); AtomicInteger calls=new AtomicInteger();
    var runtime=new LlmAgentRuntime(new StatelessContextBuilder(reader,32768),reader,new ModelAdapter() {
      public ModelCapabilities capabilities(ModelConfiguration c) { return new ModelCapabilities(false,false,true,false,false,Set.of("type","properties","required","additionalProperties","enum")); }
      public CompletionStage<ModelInvocationResult> invoke(ModelRequest r,CancellationSignal c) { calls.incrementAndGet(); return CompletableFuture.completedFuture(null); }
    },new DecisionModelAudit() {
      public boolean recordInput(UUID id,BuiltContext c,String s,Map<String,Object> p) { return false; }
      public void recordOutput(UUID id,ModelInvocationResult r) {}
    },clock);
    var result=runtime.decideWithEvidence(new RuntimeInput(request(now.plusSeconds(10)),Optional.of(bindings(now))),()->false).toCompletableFuture().join();
    assertEquals(0,calls.get()); assertEquals("MODEL_INPUT_AUDIT_FAILED",((RuntimeOutcome.Failed)result.outcome()).error().code());
  }
  private static HostedBindings bindings(Instant now) {
    UUID id=UUID.randomUUID(); return new HostedBindings(new PromptVersion(UUID.randomUUID(),id,1,1,"p",now),new AgentConfiguration(UUID.randomUUID(),id,1,RuntimeType.LLM,
        new ModelConfiguration("deepseek","deepseek-flash",false,0,128,10000,null,"JSON_MODE",false),new MemoryPolicy(MemoryMode.STATELESS),now));
  }
  private static DecisionRequest request(Instant deadline) {
    return new DecisionRequest("1.0",UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),0,0,"game","v1","actions","{}",
        "{\"mode\":\"ACTION_SELECTION\",\"actionSchemaId\":\"actions\",\"options\":[{\"selectedActionId\":\"a\",\"action\":{\"column\":0}}],\"constraints\":{}}",
        "rules","{}",deadline,null);
  }
}
