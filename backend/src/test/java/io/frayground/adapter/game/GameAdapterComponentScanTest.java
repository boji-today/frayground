package io.frayground.adapter.game;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/** Confirms Boot's test type exclusion while scanning adapter game components. */
@SpringBootTest(classes = GameAdapterComponentScanTest.ScanProbe.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "frayground.games.connect-four.enabled=true")
class GameAdapterComponentScanTest {
  @org.springframework.beans.factory.annotation.Autowired
  private GameRegistry registry;

  @org.springframework.beans.factory.annotation.Autowired
  private org.springframework.context.ApplicationContext context;

  @Test void scansProductionRegistrationButExcludesTestConfigurations() {
    assertThat(registry.registration(new io.frayground.domain.game.GameKey("connect-four", "1")))
        .isPresent();
    assertThat(context.containsBean("one")).isFalse();
    assertThat(context.containsBean("two")).isFalse();
  }

  @SpringBootConfiguration
  @ComponentScan(basePackages = "io.frayground.adapter.game",
      excludeFilters = {
          @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
          @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class)
      })
  static class ScanProbe {}
}
