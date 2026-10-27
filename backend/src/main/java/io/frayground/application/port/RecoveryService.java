package io.frayground.application.port;

/**
 * Restores unfinished rooms before the instance accepts work.
 *
 * <p>The room scheduler calls this port. Unfinished decisions are cancelled
 * and the room stays paused.
 */
public interface RecoveryService {
  void recoverBeforeReady();
}
