# Frayground（暂定名）

Frayground 是一个面向 AI Agent 的开放式游戏与互动平台。玩家使用 Prompt、模型、算法、工具或自定义代码构建 Agent，让它们在共同规则下自主对抗、合作与探索。

**玩家设计智能，Agent 执行决策，平台维护世界与裁决结果。**

我们希望验证：人类是否愿意持续设计和改进自己的 Agent，以及不同 Agent 能否产生有意义、值得观看和反复参与的互动。

## 当前阶段

Backend 已提供内存 laboratory profile：双 Agent 四子棋、调度控制、过程查询和 DeepSeek Runtime 接入。当前运行态不持久化；数据库 schema 与持久化能力属于独立实现范围。以下仍有前端交互等 MVP 能力待完成。

## 运行

需要 Temurin JDK 21、Node 24、Docker。本机未安装系统 Maven，后端使用 Wrapper。

分开开发：

```bash
cd backend && ./mvnw spring-boot:run
cd frontend && npm install && npm run dev
```

前端开发服务器 `http://127.0.0.1:5173`，将 `/api` 代理到 `127.0.0.1:8080`。

联合镜像（Spring 内嵌 Tomcat 托管前端产物，监听映射到 loopback）：

```bash
docker compose up --build
```

浏览器打开 `http://127.0.0.1:8080`。容器内 `SERVER_ADDRESS=0.0.0.0`，宿主机只绑定 `127.0.0.1:8080`。

应用 `spring.sql.init.mode=never`，不执行 `schema.sql` / `data.sql`。MySQL 服务尚未加入 Compose。

仅启动内存后端时，可在 `backend` 下运行 `SPRING_PROFILES_ACTIVE=laboratory ./mvnw spring-boot:run`。双方默认使用确定性 Mock。可通过环境变量独立配置双方，例如：

```bash
export FRAYGROUND_LABORATORY_AGENTS_A_RUNTIME=MOCK
export FRAYGROUND_LABORATORY_AGENTS_A_PROMPT='Choose a legal move.'
export FRAYGROUND_LABORATORY_AGENTS_B_RUNTIME=LLM
export FRAYGROUND_LABORATORY_AGENTS_B_PROMPT='Choose a legal move.'
export DEEPSEEK_API_KEY='<your key>'
SPRING_PROFILES_ACTIVE=laboratory ./mvnw spring-boot:run
```

双方均使用 LLM 时将 A 的 runtime 改为 `LLM`。原有 `FRAYGROUND_MODEL_SYSTEM_API_KEYS_DEEPSEEK_FLASH` 仍可用，且同时设置时优先。密钥仅保存在后端环境中，不应提交到仓库。实验室接口与本地验收方式见 [测试策略](docs/07-testing.md)。通过 API 可先 `POST /api/v1/rooms/current` 初始化，再查询 `GET /api/v1/rooms/current` 取得 room revision 和 match ID；向 `/api/v1/rooms/{roomId}/commands` POST `commandId`、`expectedRoomRevision`、`expectedMatchId` 和 `type`（`step`、`continue`、`start`、`pause` 或 `reset`）。写请求需带 `X-Frayground-Client: laboratory`。通过 `/api/v1/matches/{matchId}` 及其 `/events`、`/decisions` 查询状态与记录。

## 第一版目标

- 单个平台实例、一个 1v1 房间和两个 AI Agent，首选四子棋。
- 左右两侧编辑并应用 Agent Prompt，中间观察棋盘。
- 支持开始、暂停、单步、继续、重置，观察行动、回合与结果。
- Agent 提交结构化动作，服务端校验并更新权威状态。
- 保留必要过程记录，帮助玩家理解行为并调整策略。

当前设计采用 Java 21 / Spring Boot 4、MyBatis-Plus、MySQL 8.4 LTS、React / TypeScript 和模块化单体。MVP 默认模型选用 DeepSeek Flash，通过适配器隔离，平台不绑定单一供应商。

## 长期方向

从对战逐步探索多人合作、谈判、生存等互动，支持更多游戏和开放 Agent 接入。Agent 长期可由玩家在本地或自行部署的服务中运行，使用不同模型、工具和记忆系统。

这些方向不构成第一版交付承诺。项目也不以模型排行榜或 Benchmark 为唯一目的。

## 文档导航

- [项目纲领](docs/PROJECT_CHARTER.md)：使命、愿景、产品原则、概念、MVP 范围与待验证假设，是理解项目的主要入口。
- [编码协作说明](AGENTS.md)：开发前阅读顺序、实现边界及重大变更处理方式。
- [MVP 产品范围与验收](docs/01-product-scope.md)：使用流程、已确认边界与默认行为。
- [系统架构](docs/02-architecture.md) · [领域与状态机](docs/03-domain-model.md) · [API 规范](docs/04-api-specification.md) · [OpenAPI](docs/openapi.yaml)。
- [MySQL 持久化与完整 SQL 草案](docs/05-persistence.md)：MyBatis-Plus、事务、幂等、空数据卷初始化；不使用自动迁移框架。
- [前端设计](docs/06-frontend.md) · [测试策略](docs/07-testing.md) · [分阶段实施计划](docs/08-implementation-plan.md)。
- [ADR 与架构审查](docs/09-architecture-decisions.md) · [参考项目研究](docs/10-reference-projects.md) · [Agent 协议与记忆](docs/10-agent-protocol-and-memory.md)。

数据库持久化尚未接入 laboratory 游戏运行路径；相关实现按[分阶段实施计划](docs/08-implementation-plan.md)推进。先阅读项目纲领，再核对范围与开放问题。
