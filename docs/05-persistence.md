# MySQL 持久化与数据库维护

> 已采纳设计，待实现。MySQL 8.4 LTS / InnoDB / utf8mb4，MyBatis-Plus 3.5.17 Boot 4 starter；不使用 Flyway、Liquibase、PostgreSQL 方言或 H2 兼容替代。

## 1. 映射与约定

Entity、Mapper、XML SQL、Jackson 3 JSON TypeHandler 属 `adapter.persistence`；Repository port 和纯领域对象不带 MyBatis 注解。JSON TypeHandler 显式配置 Jackson 3，不误用仍依赖 Jackson 2 的内置处理器。普通 CRUD 用 BaseMapper，关键事务用显式 SELECT FOR UPDATE 和条件 UPDATE。

应用会话时区设为 UTC，时间列 DATETIME(6)，接口输出 UTC ISO-8601。ID 为 CHAR(36) CHARACTER SET ascii COLLATE ascii_bin 的 UUID；文本为 utf8mb4；版本和序号使用 BIGINT，限制 0..9007199254740991，避免前端精度损失。状态用 VARCHAR + CHECK，不用数据库 ENUM。规则与配置 JSON 用 MySQL JSON，索引关系字段不埋在 JSON 中。

下面 SQL 是完整逻辑建表草案，后续阶段一将它定稿为 `backend/src/main/resources/db/schema.sql`；不是已经执行的数据库。数据库本身由 Compose 的 MYSQL_DATABASE=frayground 创建，脚本在选定数据库中运行，不含 DROP/TRUNCATE 或自动 ALTER。由于 Room/Agent 存在环形指针，末尾统一添加外键。

## 2. schema.sql 草案

```sql
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
  CONSTRAINT ck_execution CHECK (execution_status IN ('PAUSED','RUNNING','PAUSING','STOPPED')),
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
```

这些 ALTER 是完整初始 schema 的组成部分，用于创建外键，不是版本升级脚本。外键不设级联删除。关联 ID 的跨 Match/Agent 一致性仍需事务内关联查询校验；不能只检查某个 ID 存在。action_submissions.decision_id 不设外键，便于保留已知房间内引用未知 Decision 的拒绝回执；它不是可信关系，不能据此驱动结算。

`active_slot` 利用 MySQL 唯一键允许多个 NULL 保留终态历史，阻止同一席位阶段有两个活动 Decision；不是 PostgreSQL 部分索引。未来同时回合需新增 phase resolution 与 action collection，不把当前逐动作 settlements 的世界版本推进用于批结算。

## 3. data.sql 草案

```sql
-- 仅首次空库初始化。失败立即停止；不可对已有库当“刷新种子”执行。
SET @agent_a='00000000-0000-4000-8000-000000000001';
SET @agent_b='00000000-0000-4000-8000-000000000002';
SET @prompt_a='00000000-0000-4000-8000-000000000011';
SET @prompt_b='00000000-0000-4000-8000-000000000012';
SET @config_a='00000000-0000-4000-8000-000000000021';
SET @config_b='00000000-0000-4000-8000-000000000022';
SET @room='00000000-0000-4000-8000-000000000031';
SET @prompt='根据当前观察和合法动作选择行动；只返回要求的 JSON。';
START TRANSACTION;
INSERT INTO agents(id,name,created_at) VALUES
  (@agent_a,'Agent A',UTC_TIMESTAMP(6)),(@agent_b,'Agent B',UTC_TIMESTAMP(6));
INSERT INTO prompt_drafts VALUES
  (@agent_a,1,@prompt,UTC_TIMESTAMP(6)),(@agent_b,1,@prompt,UTC_TIMESTAMP(6));
INSERT INTO prompt_versions VALUES
  (@prompt_a,@agent_a,1,1,@prompt,UNHEX(SHA2(@prompt,256)),UTC_TIMESTAMP(6)),
  (@prompt_b,@agent_b,1,1,@prompt,UNHEX(SHA2(@prompt,256)),UTC_TIMESTAMP(6));
SET @configuration=JSON_OBJECT(
  'model',JSON_OBJECT('provider','deepseek','modelId','deepseek-flash',
    'thinking',CAST('false' AS JSON),'temperature',0,'maxOutputTokens',256,
    'decisionTimeoutMs',30000,'outputStrategy','JSON_MODE',
    'allowBeta',CAST('false' AS JSON)),
  'credentialRef','env:DEEPSEEK_API_KEY',
  'memoryPolicy',JSON_OBJECT('mode','STATELESS'));
INSERT INTO agent_configs VALUES
  (@config_a,@agent_a,1,'MOCK',@configuration,UTC_TIMESTAMP(6)),
  (@config_b,@agent_b,1,'MOCK',@configuration,UTC_TIMESTAMP(6));
UPDATE agents SET published_prompt_id=@prompt_a,current_config_id=@config_a WHERE id=@agent_a;
UPDATE agents SET published_prompt_id=@prompt_b,current_config_id=@config_b WHERE id=@agent_b;
INSERT INTO rooms(id,singleton_key,revision,event_seq,execution_mode,execution_status,health,created_at)
  VALUES(@room,1,0,0,'STEP','PAUSED','OK',UTC_TIMESTAMP(6));
COMMIT;
```

种子不预造比赛历史或决策。应用启动在 Room 锁下发现 active_match_id 为 null 时，才通过 GameDefinition 创建初始 READY Match、两席 Participant、Turn、版本 0 快照和事件；重复启动不会再创建。种子默认 MOCK 保障离线启动，切换 LLM 后默认 DeepSeek；credentialRef 是引用，不是 Key。configuration JSON 包含 model、memoryPolicy、credentialRef，管理面 DTO 只投影前两项；JSON 布尔值显式 CAST，避免 MySQL 把 SQL false 写成数值 0。

## 4. 事务边界与锁顺序

所有变更先进入房间串行入口，事务统一锁顺序 Room → Match → Agent（按 ID 排序）→ Turn/Decision → 回执/结算及其他子表。隔离级别 READ COMMITTED，按主键锁行；死锁或连接失败只重试整个短事务，最多一次，不重发模型调用。若重试仍失败，返回服务故障，调度停止等待恢复。

**启动 Decision：**锁 Room 和当前 Match → 检查 RUNNING、模式许可、无活动 Decision、Match ACTIVE/Turn OPEN → 读取并固定 Agent 发布/配置版本 → 生成 Observation/合法映射 → 写 RUNNING 与记录占位、deadline → 递增 roomRevision，写 DECISION_STARTED 事件 → 提交后 Runner 在事务外构造/持久化输入并调用模型。跨 Room 复用 Agent 的配置变更也必须保持同一锁顺序；MVP 唯一 Room，不提供任意共享修改接口。

**发布 Prompt：**锁 Room、Agent，检查命令回执和预期 Room/草稿版本 → 插入新 PromptVersion → 更新 published 指针 → 写回执和 PROMPT_PUBLISHED。编号用锁下 max+1 或当前版本+1，不用无锁计数。重复 commandId 不新建版本；相同内容的新命令仍发布新版本。

**提交 Action：**

1. 完成 JSON 大小/格式与管理面或未来身份授权检查，解析 Envelope。
2. 锁目标 Room，读取 actionId 原回执。相同 Room、相同规范化请求返回原 HTTP 状态与 body，即使 Match 已重置；不同内容或不同授权范围返回冲突，不泄漏其他房间回执。
3. 校验当前 Match、Turn、Participant 关系，Decision 为 RUNNING、属于该席位且版本一致，now < deadline，Match ACTIVE。PAUSING 不禁止当前决策结算。
4. 读取注册项，执行 Schema、强类型转换和权威规则校验；合法则计算新状态。非法可信当前动作使 Decision FAILED 并暂停；身份/关联不匹配不得破坏合法 Decision。
5. 插入 action_submissions 最终回执（本事务不可对外见中间态），插入 settlements；条件更新 Decision 为 COMPLETED，条件更新 Match 的 state_version 为 v+1；影响行数均为 1，否则全部回滚。
6. 写版本 v+1 快照、DiscPlaced/MatchFinished 事件，关闭 Turn；非终局创建下一 OPEN Turn，终局清空 current_turn_id 并置 FINISHED/STOPPED。
7. 写 DecisionRecord 校验/执行结果和 RoomEvent；根据模式/暂停状态设置执行状态。递增 Room revision/event_seq，提交。
8. 提交后返回回执，再排队 tick；tick 再查许可，不能直接在结算事务递归调用模型。

规范化哈希覆盖完整 Envelope：字段顺序排序、保留数组顺序、ID 使用标准 UUID 字符串、stateVersion 仅整数、拒绝重复 JSON key/非有限数；用同一个规范化器计算 SHA-256。字符串内容不随意 trim。相同 ID 不同 Payload 为 ACTION_ID_CONFLICT，不产生第二条回执。

合法 Envelope 的确定性领域拒绝也持久化 REJECTED 回执；非法 JSON/缺字段无法形成可信 actionId 时仅返回 400。基础设施故障回滚，不持久化一个伪“已处理”结果；客户端可用原 ID 重试。提交失败到成功之间不会重选动作。

**命令事务：**command_receipts 覆盖控制、配置发布。hash 包含命令种类、目标和 body。busy、revision 不匹配等可确定拒绝也保存原回执，防止同一旧 step 请求稍后重试被执行。读取回执早于检查 expectedRoomRevision。只要记录回执而无状态变更，不增加 roomRevision。

## 5. 快照、恢复与审计

四子棋每次成功状态结算保存一个完整快照，最多初始加 42 个。恢复从 Match.state_version 对应的已提交快照开始，不重新请求 LLM；事件可逐步重建棋盘并与快照核对，不要求引入完整 Event Sourcing 框架。

| 故障点 | 恢复行为 |
| --- | --- |
| 模型调用前崩溃 | RUNNING 取消为 SERVER_RESTARTED，已固定输入保留，人工继续创建新 Decision |
| 调用发出后崩溃 | 费用/返回可能未知；不自动再发；同样取消并暂停 |
| 结算事务提交前崩溃 | 全部回滚，棋盘无半步；恢复取消遗留 Decision |
| 事务提交后响应丢失 | COMPLETED/回执/快照同在；重试原 actionId 得原结果，不重复落子 |
| 写 RoomEvent 后进程崩溃 | RoomEvent 与世界一起提交，重连由数据库补发 |
| 快照不存在/规则版本未知 | Room health=ERROR，拒绝推进并报告原因；保留数据，不猜测覆盖 |

重启恢复对所有非终局 Room 置 PAUSED，取消 PENDING/RUNNING，写恢复事件，然后才开放就绪。健康故障单独标记，不作为游戏输赢。

DecisionRecord 的 model_output 保存原始动作 content/tool arguments、模型实际标识、finish reason 等允许字段，不存 Authorization、完整 HTTP 头、reasoning_content 或厂商私有链路推理。输入可能包含双方各自私有 Prompt，属于管理面私有审计，不通过 GameEvent/外部 Agent 查询返回。迟到结果仅追加 diagnostics，不改终态和原回执。

## 6. 初始化、更新与备份

后续 Compose 挂载配置草案：

```yaml
services:
  mysql:
    image: mysql:8.4 # 阶段一锁定经核验的具体补丁和摘要
    environment:
      MYSQL_DATABASE: frayground
      MYSQL_USER: frayground
      MYSQL_PASSWORD: ${MYSQL_PASSWORD:?set locally}
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD:?set locally}
    ports: ["127.0.0.1:3306:3306"]
    volumes:
      - mysql-data:/var/lib/mysql
      - ./backend/src/main/resources/db/schema.sql:/docker-entrypoint-initdb.d/01-schema.sql:ro
      - ./backend/src/main/resources/db/data.sql:/docker-entrypoint-initdb.d/02-data.sql:ro
volumes:
  mysql-data:
```

应用配置 `spring.sql.init.mode=never`。不配置 Flyway/Liquibase，不引入自动建表；MyBatis-Plus 不负责生成或升级 Schema。脚本仅由容器空卷初始化执行一次；已有数据库目录不重新应用初始化配置，依据[MySQL 官方容器说明](https://hub.docker.com/_/mysql/)。若首次 DDL 部分成功后失败，MySQL DDL 不能当作跨脚本可回滚事务：应用拒绝带缺表的库，报告初始化失败，人工确认目标后修复，禁止自动删除数据卷重试。

后续结构变更同时更新完整 schema/data 和文档变更说明；既有库备份后人工执行审阅过的精确 SQL，再验证结构及数据。`CREATE TABLE IF NOT EXISTS` 不能升级旧表；完整 schema.sql 不可直接当已有库增量脚本。没有现存业务库，因此本轮不提供虚构升级迁移。备份/恢复命令只在具备实际数据库和明确目标后执行，不自动删除、重建或清理历史。

集成测试在临时容器的空库执行同一份 schema.sql、data.sql，随后启动应用创建初始 Match；测试夹具通过 Repository/API 生成，不污染初始化数据。验证容器数据卷复用时不会再次执行脚本，应用重启不覆盖已发布 Prompt。
