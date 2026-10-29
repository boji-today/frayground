package io.frayground.adapter.memory;
import io.frayground.application.port.*;
import io.frayground.domain.action.*;
import java.util.*;
public final class InMemoryActionReceiptRepository implements ActionReceiptStore {
  private final InMemoryStorage storage;
  public InMemoryActionReceiptRepository(InMemoryStorage storage) { this.storage=storage; }
  public Optional<ActionSubmissionResult> findByActionId(UUID id) { var r=storage.readState().actionReceipts.get(id); return r==null?Optional.empty():Optional.of(copy(r.result())); }
  public Optional<ActionEnvelope> findEnvelope(UUID id) { var r=storage.readState().actionReceipts.get(id); return r==null?Optional.empty():Optional.of(copy(r.envelope())); }
  public void saveFinal(ActionEnvelope e,ActionSubmissionResult r) {
    MemoryState s=storage.writeState();
    var match=s.matches.get(e.matchId());
    if(match==null||!s.rooms.containsKey(match.roomId())) throw new StorageConflictException("Action owner does not exist");
    if(!e.actionId().equals(r.actionId())||!e.decisionId().equals(r.decisionId())||!e.matchId().equals(r.matchId())) throw new StorageConflictException("Receipt identity does not match envelope");
    if(r.status()!=SubmissionStatus.REJECTED) {
      Decision decision=s.decisions.get(e.decisionId());
      var turn=s.turns.get(e.turnId());
      if(decision==null||turn==null||!decision.matchId().equals(e.matchId())||!decision.turnId().equals(e.turnId())||!decision.participantId().equals(e.participantId())||e.stateVersion()!=decision.stateVersion()||!Objects.equals(s.turnMatches.get(e.turnId()),e.matchId())||!turn.requiredParticipants().contains(e.participantId())||match.participants().stream().noneMatch(p->p.participantId().equals(e.participantId()))) throw new StorageConflictException("Action owner does not match");
    }
    var v=new MemoryState.ActionReceipt(copy(e),copy(r));
    var old=s.actionReceipts.putIfAbsent(e.actionId(),v);
    if(old!=null&&(!same(old.envelope(),e)||!same(old.result(),r))) throw new StorageConflictException("Receipt key conflict");
  }
  private static ActionEnvelope copy(ActionEnvelope e) { return new ActionEnvelope(e.protocolVersion(),e.matchId(),e.participantId(),e.turnId(),e.decisionId(),e.actionId(),e.stateVersion(),MemoryValueCopy.map(e.action())); }
  private static ActionSubmissionResult copy(ActionSubmissionResult r) { return new ActionSubmissionResult(r.status(),r.actionId(),r.decisionId(),r.matchId(),r.stateVersion(),r.executedAction()==null?null:MemoryValueCopy.map(r.executedAction()),r.errorCode()); }
  private static boolean same(ActionEnvelope a,ActionEnvelope b) { return a.protocolVersion().equals(b.protocolVersion())&&a.matchId().equals(b.matchId())&&a.participantId().equals(b.participantId())&&a.turnId().equals(b.turnId())&&a.decisionId().equals(b.decisionId())&&a.actionId().equals(b.actionId())&&a.stateVersion()==b.stateVersion()&&MemoryValueCopy.equal(a.action(),b.action()); }
  private static boolean same(ActionSubmissionResult a,ActionSubmissionResult b) { return a.status()==b.status()&&a.actionId().equals(b.actionId())&&a.decisionId().equals(b.decisionId())&&a.matchId().equals(b.matchId())&&a.stateVersion()==b.stateVersion()&&Objects.equals(a.errorCode(),b.errorCode())&&MemoryValueCopy.equal(a.executedAction(),b.executedAction()); }
}
