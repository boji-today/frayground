-- Complete initial schema. Not an incremental migration. No DROP/TRUNCATE.
CREATE DATABASE IF NOT EXISTS frayground
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;
USE frayground;

CREATE TABLE agents (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  name VARCHAR(80) NOT NULL,
  published_prompt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  current_config_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  created_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE prompt_drafts (
  agent_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  revision BIGINT NOT NULL,
  content TEXT NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT fk_draft_agent FOREIGN KEY (agent_id) REFERENCES agents(id),
  CONSTRAINT ck_draft_revision CHECK (revision >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE prompt_versions (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  agent_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  version_no BIGINT NOT NULL,
  source_draft_revision BIGINT NOT NULL,
  content TEXT NOT NULL,
  content_sha256 BINARY(32) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  UNIQUE KEY uq_prompt_version (agent_id, version_no),
  CONSTRAINT fk_prompt_agent FOREIGN KEY (agent_id) REFERENCES agents(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_configs (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  agent_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  version_no BIGINT NOT NULL,
  runtime_type VARCHAR(16) NOT NULL,
  configuration JSON NOT NULL,
  created_at DATETIME(6) NOT NULL,
  UNIQUE KEY uq_config_version (agent_id, version_no),
  CONSTRAINT fk_config_agent FOREIGN KEY (agent_id) REFERENCES agents(id),
  CONSTRAINT ck_runtime CHECK (runtime_type IN ('MOCK','LLM','REMOTE_HTTP'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE rooms (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  singleton_key TINYINT NULL,
  active_match_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  revision BIGINT NOT NULL,
  event_seq BIGINT NOT NULL,
  execution_mode VARCHAR(8) NOT NULL,
  execution_status VARCHAR(16) NOT NULL,
  health VARCHAR(8) NOT NULL,
  health_error VARCHAR(80) NULL,
  created_at DATETIME(6) NOT NULL,
  UNIQUE KEY uq_singleton_room (singleton_key),
  CONSTRAINT ck_singleton CHECK (singleton_key IS NULL OR singleton_key=1),
  CONSTRAINT ck_room_revision CHECK (revision >= 0 AND event_seq >= 0),
  CONSTRAINT ck_mode CHECK (execution_mode IN ('AUTO','STEP')),
  CONSTRAINT ck_execution CHECK (execution_status IN ('PAUSED','RUNNING','PAUSING')),
  CONSTRAINT ck_health CHECK (health IN ('OK','ERROR'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE matches (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  room_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  game_type VARCHAR(64) NOT NULL,
  game_rules_version VARCHAR(32) NOT NULL,
  status VARCHAR(16) NOT NULL,
  state_version BIGINT NOT NULL,
  game_event_seq BIGINT NOT NULL,
  current_turn_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  result_json JSON NULL,
  created_at DATETIME(6) NOT NULL,
  ended_at DATETIME(6) NULL,
  KEY ix_match_room_created (room_id, created_at, id),
  CONSTRAINT fk_match_room FOREIGN KEY (room_id) REFERENCES rooms(id),
  CONSTRAINT ck_match_status CHECK (status IN ('READY','ACTIVE','FINISHED','ABORTED')),
  CONSTRAINT ck_match_version CHECK (state_version >= 0 AND game_event_seq >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE participants (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  match_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  agent_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  seat_key VARCHAR(32) NOT NULL,
  UNIQUE KEY uq_participant_seat (match_id, seat_key),
  UNIQUE KEY uq_participant_agent (match_id, agent_id),
  CONSTRAINT fk_participant_match FOREIGN KEY (match_id) REFERENCES matches(id),
  CONSTRAINT fk_participant_agent FOREIGN KEY (agent_id) REFERENCES agents(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE turns (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  match_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  turn_no BIGINT NOT NULL,
  round_no BIGINT NOT NULL,
  base_state_version BIGINT NOT NULL,
  scheduling_mode VARCHAR(40) NOT NULL,
  required_participants JSON NOT NULL,
  status VARCHAR(16) NOT NULL,
  UNIQUE KEY uq_turn_no (match_id, turn_no),
  CONSTRAINT fk_turn_match FOREIGN KEY (match_id) REFERENCES matches(id),
  CONSTRAINT ck_turn_status CHECK (status IN ('OPEN','RESOLVED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE decisions (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  match_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  turn_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  participant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) NOT NULL,
  state_version BIGINT NOT NULL,
  observation_version BIGINT NOT NULL,
  prompt_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  config_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  request_json JSON NULL,
  started_at DATETIME(6) NULL,
  deadline_at DATETIME(6) NULL,
  ended_at DATETIME(6) NULL,
  error_code VARCHAR(80) NULL,
  active_slot TINYINT GENERATED ALWAYS AS
    (CASE WHEN status IN ('PENDING','RUNNING') THEN 1 ELSE NULL END) STORED,
  UNIQUE KEY uq_active_decision (match_id, turn_id, participant_id, active_slot),
  KEY ix_decision_deadline (status, deadline_at),
  KEY ix_decision_history (match_id, started_at, id),
  CONSTRAINT fk_decision_match FOREIGN KEY (match_id) REFERENCES matches(id),
  CONSTRAINT fk_decision_turn FOREIGN KEY (turn_id) REFERENCES turns(id),
  CONSTRAINT fk_decision_participant FOREIGN KEY (participant_id) REFERENCES participants(id),
  CONSTRAINT fk_decision_prompt FOREIGN KEY (prompt_version_id) REFERENCES prompt_versions(id),
  CONSTRAINT fk_decision_config FOREIGN KEY (config_version_id) REFERENCES agent_configs(id),
  CONSTRAINT ck_decision_status CHECK (status IN
    ('PENDING','RUNNING','COMPLETED','FAILED','EXPIRED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE decision_records (
  decision_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  context_builder_version VARCHAR(32) NULL,
  model_input JSON NULL,
  model_output JSON NULL,
  parsed_action JSON NULL,
  validation_result JSON NULL,
  submission_result JSON NULL,
  error_json JSON NULL,
  usage_json JSON NULL,
  latency_ms BIGINT NULL,
  diagnostics JSON NULL,
  CONSTRAINT fk_record_decision FOREIGN KEY (decision_id) REFERENCES decisions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE action_submissions (
  action_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  room_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  decision_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_sha256 BINARY(32) NOT NULL,
  envelope JSON NOT NULL,
  status VARCHAR(16) NOT NULL,
  http_status SMALLINT NOT NULL,
  result_json JSON NOT NULL,
  created_at DATETIME(6) NOT NULL,
  KEY ix_submission_decision (decision_id),
  CONSTRAINT fk_submission_room FOREIGN KEY (room_id) REFERENCES rooms(id),
  CONSTRAINT ck_submission_status CHECK (status IN ('EXECUTED','REJECTED','ACCEPTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE settlements (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  match_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  turn_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  participant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  decision_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  action_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  from_version BIGINT NOT NULL,
  to_version BIGINT NOT NULL,
  action_json JSON NOT NULL,
  UNIQUE KEY uq_settlement_decision (decision_id),
  UNIQUE KEY uq_settlement_action (action_id),
  UNIQUE KEY uq_settlement_turn_seat (match_id, turn_id, participant_id),
  CONSTRAINT fk_settlement_match FOREIGN KEY (match_id) REFERENCES matches(id),
  CONSTRAINT fk_settlement_turn FOREIGN KEY (turn_id) REFERENCES turns(id),
  CONSTRAINT fk_settlement_participant FOREIGN KEY (participant_id) REFERENCES participants(id),
  CONSTRAINT fk_settlement_decision FOREIGN KEY (decision_id) REFERENCES decisions(id),
  CONSTRAINT fk_settlement_action FOREIGN KEY (action_id) REFERENCES action_submissions(action_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE state_snapshots (
  match_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  state_version BIGINT NOT NULL,
  state_json JSON NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (match_id, state_version),
  CONSTRAINT fk_snapshot_match FOREIGN KEY (match_id) REFERENCES matches(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE game_events (
  match_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  sequence_no BIGINT NOT NULL,
  state_version BIGINT NOT NULL,
  decision_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  event_type VARCHAR(80) NOT NULL,
  event_version INT NOT NULL,
  payload JSON NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (match_id, sequence_no),
  CONSTRAINT fk_event_match FOREIGN KEY (match_id) REFERENCES matches(id),
  CONSTRAINT fk_event_decision FOREIGN KEY (decision_id) REFERENCES decisions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE room_events (
  room_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  sequence_no BIGINT NOT NULL,
  match_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  room_revision BIGINT NOT NULL,
  event_type VARCHAR(80) NOT NULL,
  payload JSON NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (room_id, sequence_no),
  CONSTRAINT fk_feed_room FOREIGN KEY (room_id) REFERENCES rooms(id),
  CONSTRAINT fk_feed_match FOREIGN KEY (match_id) REFERENCES matches(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE command_receipts (
  room_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  command_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_sha256 BINARY(32) NOT NULL,
  http_status SMALLINT NOT NULL,
  response_json JSON NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (room_id, command_id),
  CONSTRAINT fk_command_room FOREIGN KEY (room_id) REFERENCES rooms(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE agents
  ADD CONSTRAINT fk_agent_published FOREIGN KEY (published_prompt_id) REFERENCES prompt_versions(id),
  ADD CONSTRAINT fk_agent_config FOREIGN KEY (current_config_id) REFERENCES agent_configs(id);
ALTER TABLE rooms
  ADD CONSTRAINT fk_room_active FOREIGN KEY (active_match_id) REFERENCES matches(id);
ALTER TABLE matches
  ADD CONSTRAINT fk_match_turn FOREIGN KEY (current_turn_id) REFERENCES turns(id);
