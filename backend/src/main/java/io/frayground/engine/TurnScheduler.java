package io.frayground.engine;

import io.frayground.domain.match.CommandResult;
import io.frayground.domain.match.ControlCommand;
import java.util.UUID;

/**
 * Serial entry for one room's ticks and control commands.
 *
 * <p>{@code scheduleTick} queues a later tick only while
 * {@code expectedMatchId} is still current. {@code handleCommand} returns the
 * command receipt. {@code recoverBeforeReady} runs before the instance accepts
 * work: unfinished decisions are cancelled and the room stays paused.
 */
public interface TurnScheduler {
  void scheduleTick(UUID roomId, UUID expectedMatchId, String reason);

  CommandResult handleCommand(UUID roomId, ControlCommand command);

  void recoverBeforeReady();
}
