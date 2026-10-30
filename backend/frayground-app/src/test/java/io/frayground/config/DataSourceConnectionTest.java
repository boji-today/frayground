package io.frayground.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("dev")
class DataSourceConnectionTest {

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Test
  void selectOne() {
    Integer one = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
    assertThat(one).isEqualTo(1);
  }
}
