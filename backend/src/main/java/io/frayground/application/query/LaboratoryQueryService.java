package io.frayground.application.query;

import io.frayground.application.port.*;
import io.frayground.domain.action.*;
import io.frayground.domain.game.*;
import io.frayground.domain.match.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import static io.frayground.application.query.LaboratoryReadModels.*;

/** Gathers one consistent memory snapshot and enforces the laboratory ownership boundary. */
public class LaboratoryQueryService {
  private final UUID roomId;
  private final RoomStore rooms; private final MatchStore matches; private final TurnStore turns;
  private final DecisionStore decisions; private final DecisionRecordStore records;
  private final ActionReceiptStore receipts; private final SnapshotStore snapshots;
  private final GameEventStore gameEvents; private final RoomEventRepository roomEvents;
  private final DecisionHistoryReader history;
  public LaboratoryQueryService(UUID roomId, RoomStore rooms, MatchStore matches, TurnStore turns,
      DecisionStore decisions, DecisionRecordStore records, ActionReceiptStore receipts,
      SnapshotStore snapshots, GameEventStore gameEvents, RoomEventRepository roomEvents,
      DecisionHistoryReader history) {
    this.roomId=roomId; this.rooms=rooms; this.matches=matches; this.turns=turns;
    this.decisions=decisions; this.records=records; this.receipts=receipts;
    this.snapshots=snapshots; this.gameEvents=gameEvents; this.roomEvents=roomEvents; this.history=history;
  }
  public static final class NotFound extends RuntimeException {
    private final String code;
    public NotFound(String code) { this.code=code; }
    public String code() { return code; }
  }
  public static final class Unavailable extends RuntimeException {}
  @Transactional(transactionManager="gameTransactionManager",readOnly=true)
  public RoomData room(UUID id) {
    if (!roomId.equals(id)) throw new NotFound("ROOM_NOT_FOUND");
    Room room=rooms.find(id).orElseThrow(()->new NotFound("ROOM_NOT_FOUND"));
    return new RoomData(room, room.activeMatchId()==null ? null : readMatch(room.activeMatchId()));
  }
  @Transactional(transactionManager="gameTransactionManager",readOnly=true)
  public MatchData match(UUID id) { return readMatch(id); }
  @Transactional(transactionManager="gameTransactionManager",readOnly=true)
  public DecisionData decision(UUID id) { return readDecision(decisions.find(id).orElseThrow(()->new NotFound("DECISION_NOT_FOUND"))); }
  @Transactional(transactionManager="gameTransactionManager",readOnly=true)
  public Page<DecisionData> decisions(UUID matchId, String cursor, int limit) {
    Match match=owned(matchId); validateLimit(limit);
    DecisionHistoryReader.Position pos=decode(cursor,matchId);
    var found=history.readAfter(matchId,pos,limit+1);
    var items=found.stream().limit(limit).map(d -> checkedDecision(d,match)).toList();
    String next=found.size()>limit ? encode(matchId,found.get(limit-1)) : null;
    return new Page<>(items,next);
  }
  @Transactional(transactionManager="gameTransactionManager",readOnly=true)
  public EventPage events(UUID matchId,long after,int limit) {
    owned(matchId); validateLimit(limit); if(after<0 || after>9007199254740991L)throw new IllegalArgumentException("after");
    var all=gameEvents.readAfter(matchId,after,limit+1);
    var items=all.stream().limit(limit).toList();
    return new EventPage(items,items.isEmpty()?after:items.get(items.size()-1).sequence(),all.size()>limit);
  }
  @Transactional(transactionManager="gameTransactionManager",readOnly=true)
  public List<RoomEvent> roomEvents(UUID id,long after,int limit) {
    if (!roomId.equals(id) || rooms.find(id).isEmpty()) throw new NotFound("ROOM_NOT_FOUND");
    if(after<0 || limit<1 || limit>100)throw new IllegalArgumentException("cursor");
    return roomEvents.readAfter(id,after,limit);
  }
  private Match owned(UUID id) {
    Match m=matches.find(id).orElseThrow(()->new NotFound("MATCH_NOT_FOUND"));
    if(!m.roomId().equals(roomId) || rooms.find(roomId).isEmpty())throw new NotFound("MATCH_NOT_FOUND");
    return m;
  }
  private MatchData readMatch(UUID id) {
    Match m=owned(id);
    TurnContext turn=m.currentTurnId()==null?null:turns.find(m.currentTurnId()).orElseThrow(Unavailable::new);
    if(turn!=null && !history.belongsToMatch(turn.turnId(),m.matchId()))throw new Unavailable();
    StateSnapshot snapshot=snapshots.find(id,m.stateVersion()).orElseThrow(Unavailable::new);
    var current=turn==null?List.<DecisionData>of():decisions.findByTurn(turn.turnId()).stream()
        .map(d->checkedDecision(d,m)).toList();
    GameEvent last=null; long after=0;
    while(after<m.gameEventSeq()) {
      var batch=gameEvents.readAfter(id,after,100);
      if(batch.isEmpty())throw new Unavailable();
      for(var e:batch) if(e.type().equals("DiscPlaced") && e.stateVersion()<=m.stateVersion())last=e;
      after=batch.get(batch.size()-1).sequence();
    }
    return new MatchData(m,turn,snapshot,last,current);
  }
  private DecisionData readDecision(Decision d) {
    try { return checkedDecision(d,owned(d.matchId())); }
    catch(NotFound e) { throw new NotFound("DECISION_NOT_FOUND"); }
  }
  private DecisionData checkedDecision(Decision d,Match m) {
    if(!d.matchId().equals(m.matchId()) || m.participants().stream().noneMatch(p->p.participantId().equals(d.participantId()))
        || turns.find(d.turnId()).isEmpty() || !history.belongsToMatch(d.turnId(),m.matchId())) throw new NotFound("DECISION_NOT_FOUND");
    DecisionRecord record=records.find(d.decisionId()).orElse(null);
    ActionEnvelope submitted=record==null || record.submissionResult()==null?null:
        receipts.findEnvelope(record.submissionResult().actionId()).orElse(null);
    return new DecisionData(d,record,submitted);
  }
  private static void validateLimit(int limit) { if(limit<1||limit>100)throw new IllegalArgumentException("limit"); }
  private static String encode(UUID match,Decision d) {
    String raw=match+"|"+(d.startedAt()==null?"":d.startedAt())+"|"+d.decisionId();
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }
  private static DecisionHistoryReader.Position decode(String cursor,UUID match) {
    if(cursor==null)return null;
    try {
      String[] fields=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\\|",-1);
      if(fields.length!=3 || !match.toString().equals(fields[0]))throw new IllegalArgumentException();
      return new DecisionHistoryReader.Position(fields[1].isEmpty()?null:Instant.parse(fields[1]),UUID.fromString(fields[2]));
    } catch(RuntimeException e) { throw new IllegalArgumentException("cursor"); }
  }
}
