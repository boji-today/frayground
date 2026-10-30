package io.frayground.application;

import io.frayground.application.port.CommandReceiptStore;
import io.frayground.application.port.LaboratoryPromptStore;
import io.frayground.application.port.MatchStore;
import io.frayground.application.port.RoomEventRepository;
import io.frayground.application.port.RoomStore;
import io.frayground.application.query.LaboratoryQueryService.NotFound;
import io.frayground.domain.match.CommandResult;
import io.frayground.domain.match.Room;
import io.frayground.domain.match.RoomEvent;
import io.frayground.domain.match.RoomHealth;
import io.frayground.domain.strategy.PromptVersion;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Publishes the stored draft into the laboratory prompt pointer. */
public class MemoryPromptPublisher implements PromptPublisher {
  private static final long MAX_SAFE = 9007199254740991L;
  private final RoomStore rooms;
  private final MatchStore matches;
  private final CommandReceiptStore receipts;
  private final RoomEventRepository events;
  private final LaboratoryPromptStore prompts;
  private final Clock clock;
  private final Supplier<UUID> ids;

  public MemoryPromptPublisher(RoomStore rooms, MatchStore matches, CommandReceiptStore receipts,
      RoomEventRepository events, LaboratoryPromptStore prompts, Clock clock, Supplier<UUID> ids) {
    this.rooms = rooms;
    this.matches = matches;
    this.receipts = receipts;
    this.events = events;
    this.prompts = prompts;
    this.clock = clock;
    this.ids = ids;
  }

  @Override
  @Transactional(transactionManager = "gameTransactionManager", noRollbackFor = RejectedCommand.class)
  public PromptVersion publish(UUID roomId, UUID agentId, PublishPrompt command) {
    rooms.lock(roomId);
    Room room = rooms.find(roomId).orElseThrow(() -> new NotFound("ROOM_NOT_FOUND"));
    byte[] hash = hash(roomId, agentId, command);
    var prior = receipts.find(roomId, command.commandId());
    if (prior.isPresent()) {
      byte[] stored = receipts.findRequestHash(roomId, command.commandId()).orElseThrow();
      if (!MessageDigest.isEqual(hash, stored)) throw new RejectedCommand("COMMAND_ID_CONFLICT", command.commandId());
      if (prior.get().errorCode() != null) throw new RejectedCommand(prior.get().errorCode(), command.commandId());
      return prompts.publishedForCommand(roomId, command.commandId()).orElseThrow();
    }
    var state = prompts.find(agentId).orElseThrow(() -> new NotFound("AGENT_NOT_FOUND"));
    if (room.activeMatchId() == null) throw new NotFound("MATCH_NOT_FOUND");
    matches.find(room.activeMatchId()).orElseThrow(() -> new NotFound("MATCH_NOT_FOUND"));
    String error = null;
    if (!command.expectedMatchId().equals(room.activeMatchId())) error = "STALE_MATCH";
    else if (command.expectedRoomRevision() != room.revision()) error = "ROOM_REVISION_CONFLICT";
    else if (room.health() != RoomHealth.OK) error = "ROOM_ERROR";
    else if (command.expectedDraftRevision() != state.draft().revision()) error = "DRAFT_REVISION_CONFLICT";
    if (error != null) {
      receipts.save(roomId, command.commandId(), hash, result(room, command, error));
      throw new RejectedCommand(error, command.commandId());
    }
    if (state.published().versionNo() >= MAX_SAFE) throw new IllegalArgumentException("revision");
    var version = new PromptVersion(ids.get(), agentId, state.published().versionNo() + 1,
        state.draft().revision(), state.draft().content(), clock.instant());
    Room next = new Room(roomId, room.revision() + 1, room.eventSeq() + 1, room.activeMatchId(),
        room.executionMode(), room.executionStatus(), room.health(), room.healthError());
    DecisionExecutionService.replaced(rooms.replace(room, next));
    events.append(new RoomEvent(roomId, next.eventSeq(), next.activeMatchId(), next.revision(), "PROMPT_PUBLISHED",
        Map.of("agentId", agentId.toString(), "promptVersionId", version.promptVersionId().toString(), "versionNo", version.versionNo()),
        version.createdAt()));
    receipts.save(roomId, command.commandId(), hash, result(next, command, null));
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void afterCommit() { prompts.commitPublished(roomId, command.commandId(), version); }
      });
    } else prompts.commitPublished(roomId, command.commandId(), version);
    return version;
  }

  private static CommandResult result(Room room, PublishPrompt command, String error) {
    return new CommandResult(command.commandId(), error, room.revision(), room.activeMatchId(),
        room.executionMode(), room.executionStatus());
  }

  private static byte[] hash(UUID roomId, UUID agentId, PublishPrompt command) {
    String body = "publish\n" + roomId + "\n" + agentId + "\n" + command.expectedRoomRevision()
        + "\n" + command.expectedMatchId() + "\n" + command.expectedDraftRevision();
    try {
      return MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
