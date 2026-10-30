package io.frayground.application.port;

/** Raised when an immutable storage key is reused with different content. */
public class StorageConflictException extends RuntimeException {
  public StorageConflictException(String message) { super(message); }
}
