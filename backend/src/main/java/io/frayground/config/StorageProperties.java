package io.frayground.config;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("frayground.storage")
public record StorageProperties(Mode mode) {
  public StorageProperties { if(mode==null) throw new IllegalArgumentException("frayground.storage.mode must be memory or database"); }
  public enum Mode { memory, database }
}
