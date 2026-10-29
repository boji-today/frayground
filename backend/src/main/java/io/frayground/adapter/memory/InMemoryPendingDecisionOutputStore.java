package io.frayground.adapter.memory;

import io.frayground.application.port.PendingDecisionOutputStore;
import io.frayground.domain.action.ActionEnvelope;
import java.util.*;

/** Shared volatile handoff; this is intentionally outside the transactional database image. */
public final class InMemoryPendingDecisionOutputStore implements PendingDecisionOutputStore {
  private final Map<UUID,Output> outputs=new HashMap<>();
  private final Set<UUID> acquired=new HashSet<>();
  public synchronized Output stageAndAcquire(Output output) {
    var stored=copy(output);
    var old=outputs.putIfAbsent(output.decisionId(),stored);
    if (old!=null) throw new IllegalStateException("Decision output already staged");
    acquired.add(output.decisionId());
    return stored;
  }
  public synchronized Output replaceAcquired(Output output) {
    if (!acquired.contains(output.decisionId()) || !outputs.containsKey(output.decisionId())) throw new IllegalStateException("Decision output not acquired");
    var stored=copy(output); outputs.put(output.decisionId(),stored); return stored;
  }
  private static Output copy(Output output) {
    if (output instanceof Failure) return output;
    var candidate=(Candidate)output; var e=candidate.envelope();
    if (!candidate.decisionId().equals(e.decisionId())) throw new IllegalArgumentException("Decision identity mismatch");
    var copy=new ActionEnvelope(e.protocolVersion(),e.matchId(),e.participantId(),e.turnId(),e.decisionId(),e.actionId(),e.stateVersion(),MemoryValueCopy.map(e.action()));
    return new Candidate(candidate.decisionId(),candidate.candidate(),copy,candidate.actionJson());
  }
  public synchronized Optional<Output> acquire(UUID id) {
    var output=outputs.get(id);
    if (output==null || !acquired.add(id)) return Optional.empty();
    return Optional.of(output);
  }
  public synchronized boolean contains(UUID id) { return outputs.containsKey(id); }
  public synchronized void release(UUID id,boolean completed) {
    if (!acquired.remove(id)) throw new IllegalStateException("Decision output not acquired");
    if (completed) outputs.remove(id);
  }
}
