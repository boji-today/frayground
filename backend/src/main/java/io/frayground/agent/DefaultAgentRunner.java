package io.frayground.agent;

import io.frayground.agent.input.DecisionInputReader;
import io.frayground.application.ActionSubmitter;
import io.frayground.application.DecisionExecutionService;
import io.frayground.application.port.PendingDecisionOutputStore;
import io.frayground.domain.action.ActionEnvelope;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Runs the fixed request outside transactions, then delegates audit and settlement. */
public final class DefaultAgentRunner implements AgentRunner {
  private static final TypeReference<Map<String,Object>> MAP=new TypeReference<>() {};
  private final AgentRuntime runtime; private final DecisionExecutionService lifecycle; private final ActionSubmitter submitter;
  private final DecisionInputReader reader; private final JsonMapper json; private final Supplier<UUID> ids;
  public DefaultAgentRunner(AgentRuntime runtime,DecisionExecutionService lifecycle,ActionSubmitter submitter,
      DecisionInputReader reader,JsonMapper json,Supplier<UUID> ids) {
    this.runtime=runtime; this.lifecycle=lifecycle; this.submitter=submitter; this.reader=reader; this.json=json; this.ids=ids;
  }
  @Override public CompletionStage<DecisionOutcome> run(RuntimeInput input,CancellationSignal cancellation) {
    Objects.requireNonNull(input); Objects.requireNonNull(cancellation);
    UUID id=input.publicRequest().decisionId();
    RuntimeInput fixed=lifecycle.runtimeInput(id);
    if (!fixed.equals(input)) throw new IllegalArgumentException("RUNTIME_INPUT_MISMATCH");
    if (lifecycle.pendingOutputs().contains(id)) return recover(id,cancellation);
    var spec=reader.readSpecification(fixed.publicRequest());
    String claim=lifecycle.claimExecution(id,cancellation.isCancelled());
    if (claim!=null) return CompletableFuture.completedFuture(new DecisionOutcome.NotSubmitted(new RuntimeError(claim,claim)));
    if (!spec.mode().equals("ACTION_SELECTION")) return failed(id,"ACTION_MODE_NOT_SUPPORTED");
    if (cancellation.isCancelled()) return failed(id,"DECISION_CANCELLED");
    CompletionStage<RuntimeExecutionResult> pending;
    try { pending=Objects.requireNonNull(runtime.decideWithEvidence(fixed,cancellation)); }
    catch (RuntimeException e) { return failed(id,"RUNTIME_ERROR"); }
    return pending.handle((result,error)-> error==null ? result : new RuntimeExecutionResult(new RuntimeOutcome.Failed(new RuntimeError("RUNTIME_ERROR","Runtime failed")),null))
        .thenCompose(result -> cancellation.isCancelled() ? failed(id,new RuntimeError("DECISION_CANCELLED","DECISION_CANCELLED"),result.modelResult()) : handle(fixed,spec,result,cancellation));
  }
  private CompletionStage<DecisionOutcome> handle(RuntimeInput fixed,DecisionInputReader.ParsedSpecification spec,RuntimeExecutionResult execution,CancellationSignal cancellation) {
    UUID id=fixed.publicRequest().decisionId();
    RuntimeOutcome candidate=execution.outcome();
    if (candidate instanceof RuntimeOutcome.Failed failed) return failed(id,failed.error(),execution.modelResult());
    if (!(candidate instanceof RuntimeOutcome.SelectedAction selected)) return failed(id,"ACTION_MODE_NOT_SUPPORTED");
    for (var option:spec.options()) {
      if (option.get("selectedActionId").asString().equals(selected.selectedActionId())) {
        var action=json.convertValue(option.get("action"),MAP);
        var r=fixed.publicRequest();
        var envelope=new ActionEnvelope("1.0",r.matchId(),r.participantId(),r.turnId(),id,ids.get(),r.stateVersion(),action);
        var pending=lifecycle.pendingOutputs();
        var output=pending.stageAndAcquire(new PendingDecisionOutputStore.Candidate(id,candidate,envelope,json.writeValueAsString(action),execution.modelResult()));
        return recoverAcquired(id,cancellation,pending,output);
      }
    }
    return failed(id,"UNKNOWN_ACTION_OPTION");
  }
  private CompletionStage<DecisionOutcome> recover(UUID id,CancellationSignal cancellation) {
    PendingDecisionOutputStore pending=lifecycle.pendingOutputs();
    var acquired=pending.acquire(id);
    if (acquired.isEmpty()) return CompletableFuture.completedFuture(new DecisionOutcome.NotSubmitted(new RuntimeError("DECISION_RECOVERY_IN_PROGRESS","Decision recovery is already in progress")));
    return recoverAcquired(id,cancellation,pending,acquired.get());
  }
  private CompletionStage<DecisionOutcome> recoverAcquired(UUID id,CancellationSignal cancellation,PendingDecisionOutputStore pending,PendingDecisionOutputStore.Output output) {
    try {
      if (cancellation.isCancelled()) {
        output=pending.replaceAcquired(new PendingDecisionOutputStore.Failure(id,new RuntimeError("DECISION_CANCELLED","DECISION_CANCELLED"),output instanceof PendingDecisionOutputStore.Candidate c ? c.evidence() : ((PendingDecisionOutputStore.Failure)output).evidence()));
      }
      if (output instanceof PendingDecisionOutputStore.Failure failed) {
        if (failed.evidence()!=null) lifecycle.modelAudit().recordOutput(id,failed.evidence());
        RuntimeError result=lifecycle.runtimeFailed(id,failed.error().code());
        pending.release(id,true);
        return CompletableFuture.completedFuture(new DecisionOutcome.NotSubmitted(result));
      }
      var candidate=(PendingDecisionOutputStore.Candidate)output;
      if (candidate.evidence()!=null) lifecycle.modelAudit().recordOutput(id,candidate.evidence());
      if (!lifecycle.recordCandidate(id,candidate.candidate(),candidate.actionJson()) || !lifecycle.saveExecutionEnvelope(candidate.envelope())) {
        pending.release(id,true);
        return CompletableFuture.completedFuture(new DecisionOutcome.NotSubmitted(new RuntimeError("DECISION_NOT_RUNNING","Decision has ended")));
      }
      return submitter.submit(candidate.envelope()).thenApply(receipt->(DecisionOutcome)new DecisionOutcome.Submitted(candidate.candidate(),receipt))
          .whenComplete((result,error)->pending.release(id,error==null));
    } catch (RuntimeException | Error failure) {
      pending.release(id,false);
      throw failure;
    }
  }
  private CompletionStage<DecisionOutcome> failed(UUID id,String code) {
    return failed(id,new RuntimeError(code,code));
  }
  private CompletionStage<DecisionOutcome> failed(UUID id,RuntimeError error) { return failed(id,error,null); }
  private CompletionStage<DecisionOutcome> failed(UUID id,RuntimeError error,ModelInvocationResult evidence) {
    var pending=lifecycle.pendingOutputs(); var output=pending.stageAndAcquire(new PendingDecisionOutputStore.Failure(id,error,evidence));
    return recoverAcquired(id,()->false,pending,output);
  }
}
