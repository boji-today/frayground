package io.frayground.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.frayground.agent.*;
import io.frayground.application.port.*;
import io.frayground.domain.action.*;
import io.frayground.domain.game.GameKey;
import io.frayground.domain.match.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

class DecisionModelAuditServiceTest {
  private static final UUID ID=UUID.randomUUID(), MATCH=UUID.randomUUID(), ROOM=UUID.randomUUID(), TURN=UUID.randomUUID();
  private static final ModelInvocationResult MODEL_ERROR=new ModelInvocationResult("req-9","deepseek-flash","partial",
      List.of(),"stop",new TokenUsage(12L,3L,null,null),Duration.ofMillis(45),new RuntimeError("MODEL_UNAVAILABLE","offline"));

  @Test void lateModelErrorAfterCompletedPreservesReceiptAndDoesNotSetDecisionError() {
    var receipt=new ActionSubmissionResult(SubmissionStatus.EXECUTED,UUID.randomUUID(),ID,MATCH,1,Map.of("type","place_disc"),null);
    var fixture=fixture(DecisionStatus.COMPLETED,null,receipt);
    fixture.service.recordOutput(ID,MODEL_ERROR);
    DecisionRecord saved=fixture.record.get();
    assertNull(saved.errorJson()); assertSame(receipt,saved.submissionResult());
    assertNotNull(saved.modelOutputJson()); assertTrue(saved.modelOutputJson().contains("MODEL_UNAVAILABLE"));
    assertEquals(MODEL_ERROR.usage(),saved.usage()); assertEquals(MODEL_ERROR.latency(),saved.latency());
  }

  @ParameterizedTest @MethodSource("terminalStatuses")
  void terminalDecisionKeepsItsOriginalError(DecisionStatus status,String error) {
    var fixture=fixture(status,error,null);
    fixture.service.recordOutput(ID,MODEL_ERROR);
    assertEquals(error,fixture.record.get().errorJson());
    assertTrue(fixture.record.get().modelOutputJson().contains("MODEL_UNAVAILABLE"));
  }
  static Stream<Object[]> terminalStatuses() {
    return Stream.of(new Object[]{DecisionStatus.CANCELLED,"{\"code\":\"DECISION_CANCELLED\"}"},
        new Object[]{DecisionStatus.EXPIRED,"{\"code\":\"DECISION_EXPIRED\"}"},
        new Object[]{DecisionStatus.FAILED,"{\"code\":\"MODEL_OUTPUT_INVALID\"}"});
  }

  @Test void identicalOutputRetryIsIdempotentAndConflictingEvidenceCannotOverwrite() {
    var fixture=fixture(DecisionStatus.COMPLETED,null,null);
    fixture.service.recordOutput(ID,MODEL_ERROR); DecisionRecord once=fixture.record.get();
    fixture.service.recordOutput(ID,MODEL_ERROR);
    assertEquals(once,fixture.record.get()); verify(fixture.records,times(1)).replace(any(),any());
    var conflicting=new ModelInvocationResult("req-other","deepseek-flash","different",List.of(),"stop",MODEL_ERROR.usage(),MODEL_ERROR.latency(),MODEL_ERROR.error());
    assertThrows(StorageConflictException.class,()->fixture.service.recordOutput(ID,conflicting));
    assertEquals(once,fixture.record.get()); verify(fixture.records,times(1)).replace(any(),any());
  }

  private static Fixture fixture(DecisionStatus status,String error,ActionSubmissionResult receipt) {
    DecisionRecordStore records=mock(DecisionRecordStore.class); DecisionStore decisions=mock(DecisionStore.class);
    MatchStore matches=mock(MatchStore.class); RoomStore rooms=mock(RoomStore.class);
    var decision=new Decision(ID,MATCH,TURN,UUID.randomUUID(),status,0,0,UUID.randomUUID(),UUID.randomUUID(),null,
        Instant.EPOCH,Instant.MAX,status==DecisionStatus.RUNNING?null:Instant.EPOCH,error);
    var match=new Match(MATCH,ROOM,new GameKey("test","1"),MatchStatus.ACTIVE,0,0,TURN,List.of(),null);
    var room=new Room(ROOM,0,0,MATCH,ExecutionMode.STEP,ExecutionStatus.PAUSED,RoomHealth.OK,null);
    var initial=new DecisionRecord(ID,"stateless-v1","{\"input\":1}",null,null,null,receipt,error,null,null,null);
    AtomicReference<DecisionRecord> saved=new AtomicReference<>(initial);
    when(decisions.find(ID)).thenReturn(Optional.of(decision)); when(matches.find(MATCH)).thenReturn(Optional.of(match));
    when(rooms.find(ROOM)).thenReturn(Optional.of(room)); when(records.find(ID)).thenAnswer(invocation->Optional.of(saved.get()));
    when(records.replace(any(),any())).thenAnswer(invocation->{ saved.set(invocation.getArgument(1)); return true; });
    return new Fixture(records,saved,new DecisionModelAuditService(records,decisions,matches,rooms,JsonMapper.builder().build()));
  }
  private record Fixture(DecisionRecordStore records,AtomicReference<DecisionRecord> record,DecisionModelAuditService service) {}
}
