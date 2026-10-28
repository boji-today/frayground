package io.frayground.adapter.memory;
import io.frayground.application.port.*;
import io.frayground.domain.match.CommandResult;
import java.util.*;
public final class InMemoryCommandReceiptRepository implements CommandReceiptStore {
  private final InMemoryStorage storage;
  public InMemoryCommandReceiptRepository(InMemoryStorage storage) { this.storage=storage; }
  public Optional<CommandResult> find(UUID room,UUID command) { var r=storage.readState().commandReceipts.get(room+":"+command); return Optional.ofNullable(r==null?null:r.result()); }
  public Optional<byte[]> findRequestHash(UUID room,UUID command) { var r=storage.readState().commandReceipts.get(room+":"+command); return r==null?Optional.empty():Optional.of(r.hash().clone()); }
  public void save(UUID room,UUID command,byte[] hash,CommandResult result) { MemoryState s=storage.writeState(); if(!s.rooms.containsKey(room))throw new StorageConflictException("Room does not exist"); String k=room+":"+command; var v=new MemoryState.CommandReceipt(hash.clone(),result); var old=s.commandReceipts.putIfAbsent(k,v); if(old!=null&&(!Arrays.equals(old.hash(),hash)||!old.result().equals(result)))throw new StorageConflictException("Receipt key conflict"); }
}
