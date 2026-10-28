package io.frayground.config;

import io.frayground.adapter.memory.InMemoryStorage;
import io.frayground.adapter.memory.InMemoryTransactionManager;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.jdbc.support.JdbcTransactionManager;

@Configuration
@EnableTransactionManagement
@EnableConfigurationProperties(StorageProperties.class)
public class StorageTransactionConfiguration {
  @Bean(name="transactionManager")
  @Primary
  PlatformTransactionManager transactionManager(DataSource dataSource) { return new JdbcTransactionManager(dataSource); }

  @Bean(name="gameTransactionManager")
  @ConditionalOnProperty(prefix="frayground.storage",name="mode",havingValue="database",matchIfMissing=true)
  PlatformTransactionManager databaseGameTransactionManager(PlatformTransactionManager transactionManager) { return transactionManager; }

  @Bean(name="gameTransactionManager")
  @ConditionalOnProperty(prefix="frayground.storage",name="mode",havingValue="memory")
  PlatformTransactionManager memoryGameTransactionManager(InMemoryStorage storage) { return new InMemoryTransactionManager(storage); }
}
