package io.frayground.config;
import io.frayground.adapter.memory.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
@ConditionalOnProperty(prefix="frayground.storage",name="mode",havingValue="memory")
public class MemoryStorageConfiguration {
  @Bean InMemoryStorage inMemoryStorage() { return new InMemoryStorage(); }
  @Bean InMemoryRoomRepository inMemoryRoomRepository(InMemoryStorage s) { return new InMemoryRoomRepository(s); }
  @Bean InMemoryMatchRepository inMemoryMatchRepository(InMemoryStorage s) { return new InMemoryMatchRepository(s); }
  @Bean InMemoryTurnRepository inMemoryTurnRepository(InMemoryStorage s) { return new InMemoryTurnRepository(s); }
  @Bean InMemoryDecisionRepository inMemoryDecisionRepository(InMemoryStorage s) { return new InMemoryDecisionRepository(s); }
  @Bean InMemoryDecisionRecordRepository inMemoryDecisionRecordRepository(InMemoryStorage s) { return new InMemoryDecisionRecordRepository(s); }
  @Bean InMemoryDecisionInputSnapshotStore inMemoryDecisionInputSnapshotStore(InMemoryStorage s) { return new InMemoryDecisionInputSnapshotStore(s); }
  @Bean InMemoryDecisionExecutionClaimStore inMemoryDecisionExecutionClaimStore(InMemoryStorage s) { return new InMemoryDecisionExecutionClaimStore(s); }
  @Bean InMemorySnapshotStore inMemorySnapshotStore(InMemoryStorage s) { return new InMemorySnapshotStore(s); }
  @Bean InMemoryGameEventRepository inMemoryGameEventRepository(InMemoryStorage s) { return new InMemoryGameEventRepository(s); }
  @Bean InMemoryRoomEventRepository inMemoryRoomEventRepository(InMemoryStorage s) { return new InMemoryRoomEventRepository(s); }
  @Bean InMemorySettlementRepository inMemorySettlementRepository(InMemoryStorage s) { return new InMemorySettlementRepository(s); }
  @Bean InMemoryCommandReceiptRepository inMemoryCommandReceiptRepository(InMemoryStorage s) { return new InMemoryCommandReceiptRepository(s); }
  @Bean InMemoryActionReceiptRepository inMemoryActionReceiptRepository(InMemoryStorage s) { return new InMemoryActionReceiptRepository(s); }
}
