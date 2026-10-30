package io.frayground.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Public laboratory wire types: no domain records or private model data. */
public final class LaboratoryDtos {
  private LaboratoryDtos() {}
  public record ParticipantView(UUID participantId, UUID agentId, String seatKey) {}
  public record StandingView(UUID participantId, int rank, Double score) {}
  public record ResultView(String status, List<StandingView> standings, String reason, List<UUID> winnerParticipantIds) {}
  public record CellView(int row, int column) {}
  public record MoveView(int row, int column, String seat, UUID participantId) {}
  public record BoardView(List<List<String>> board, Map<String, UUID> seats, UUID currentParticipantId,
      int moveCount, List<CellView> winningCells, ResultView result, MoveView lastMove) {}
  public record TurnView(UUID turnId, Long turnNo, Long roundNo, List<UUID> requiredParticipants,
      List<DecisionSummaryView> decisions) {}
  public record MatchView(UUID matchId, UUID roomId, String gameType, String gameRulesVersion,
      String status, long stateVersion, List<ParticipantView> participants, TurnView turn,
      ResultView result, Object game) {}
  public record RoomView(UUID roomId, long revision, long eventCursor, String executionMode,
      String executionStatus, String health, String healthError, MatchView currentMatch) {}
  public record CommandAckView(UUID commandId, long roomRevision, UUID matchId,
      String executionMode, String executionStatus) {}
  public record ReceiptView(String status, UUID actionId, long stateVersion, Map<String,Object> executedAction,
      String errorCode) {}
  public record UsageView(Long inputTokens, Long outputTokens, Long cacheHitTokens, Long cacheMissTokens) {}
  public record DecisionSummaryView(UUID decisionId, UUID matchId, UUID turnId, UUID participantId,
      String status, long stateVersion, long observationVersion, UUID promptVersionId, UUID configVersionId,
      Instant startedAt, Instant deadlineAt, Instant endedAt, String errorCode, UsageView usage, Long latencyMs) {}
  public record DecisionView(UUID decisionId, UUID matchId, UUID turnId, UUID participantId,
      String status, long stateVersion, long observationVersion, UUID promptVersionId,
      UUID configVersionId, Instant startedAt, Instant deadlineAt, Instant endedAt,
      String errorCode, Map<String,Object> submittedAction, ReceiptView receipt, UsageView usage, Long latencyMs) {}
  public record GameEventView(UUID matchId, long sequence, long stateVersion, UUID decisionId, String type,
      int eventVersion, Map<String,Object> payload, Instant occurredAt) {}
  public record DecisionPage(List<DecisionSummaryView> items, String nextCursor) {}
  public record EventPage(List<GameEventView> items, long nextAfter, boolean hasMore) {}
  public record ErrorView(String code, String message, boolean retryable, String traceId, Map<String,Object> details) {}
  public record DraftUpdate(long expectedDraftRevision, String content) {}
  public record PromptDraftView(UUID agentId, long revision, String content, Instant updatedAt) {}
  public record PromptVersionView(UUID promptVersionId, UUID agentId, long versionNo, long sourceDraftRevision,
      String content, Instant createdAt) {}
  public record ModelView(String provider, String modelId, boolean thinking, double temperature, int maxOutputTokens,
      int decisionTimeoutMs, String outputStrategy, boolean allowBeta) {}
  public record MemoryPolicyView(String mode) {}
  public record AgentConfigurationView(UUID configVersionId, UUID agentId, long versionNo, String runtimeType,
      ModelView model, MemoryPolicyView memoryPolicy, Instant createdAt) {}
  public record AgentView(UUID agentId, String name, PromptDraftView draft, PromptVersionView publishedPrompt,
      AgentConfigurationView configuration) {}
  public record ObservationView(UUID matchId, UUID participantId, long observationVersion, Map<String,Object> observation) {}
}
