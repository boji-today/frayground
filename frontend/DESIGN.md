# 前端架构基线

本文件约束 `frontend/` 整个应用，不单指实验室页。接口字段与枚举以 `docs/openapi.yaml` 为准，且页面只调用其中 `x-implementation-status: implemented-memory`、并被当前任务明确允许的路径。

## 路由

不引入路由库。`src/routing/route.ts` 是路由表：`/` 为现有骨架并链到实验室，`/laboratory` 为实验室页。`useRoute` 读取 `location.pathname`，并在 `popstate` 时更新。站内跳转使用 History API 的 `pushState`。地址可以直接打开；Vite 配置 `appType: "spa"` 与 `base: "/"`，开发服务器和 `vite preview` 都把未知路径回退到 `index.html`。

新页面先加进这张表，再决定是否值得引入路由库。一个或少数几个顶层路径不够成引入路由库的理由。

## 共享 API 与页面 UI

`src/api/` 是以后页面可以复用的浏览器传输层，不依赖 React：

- HTTP：同源 `/api/v1`。变更请求才带 `Content-Type: application/json` 和 `X-Frayground-Client: laboratory`。GET 与 `EventSource` 不带自定义头，不直连 8080，不配置 CORS。
- 命令体与幂等：控制命令和发布带 `commandId`，传输失败时用同一个发送闭包再试一次。草稿更新只构造 body；传输失败后 GET 配置，服务端 `content` 与发出文本一致才采纳修订号。
- 房间事件订阅：按 `after` 打开 `EventSource`。
- 纯逻辑：响应解析、修订号新旧判断、草稿是否脏、事件追加与按序号去重、游标是否该换新订阅。

页面目录放该页的状态编排和控件。实验室的草稿栏、控制条、事件列表留在 `src/laboratory/`。不要把这些控件抽到 `src/components/`，下一页的布局还没出现。

请求函数保持无 React，方便以后单独测试。本阶段不添加测试运行器。

## 状态所有权

`expectedRoomRevision` 只取房间视图或成功 `CommandResult` 的修订号，事件不改这个字段。事件的 `roomRevision` 抬高已见修订号，用来丢掉更旧的响应。`EXECUTION_CHANGED` 的 payload 可以先更新执行模式和执行状态，再 `GET /rooms/current`。`MATCH_FINISHED` 可以先把对局标成 `FINISHED` 以停用推进，`result` 仍等这次 GET。终局、发布成功、`SERVER_RECOVERED`、`ROOM_UNAVAILABLE` 的 GET 是取权威快照。

未保存草稿在实验室页的 `useLaboratory` 席位状态里，席位栏是受控组件。SSE、后续 GET、发布成功都不得覆盖未保存文本。保存是否仍算已保存，以响应到达时文本框里的内容和响应正文比较。请求期间又输入的字留在文本框里；与已保存正文不同就继续算未保存。

没有 Redux、Zustand，也没有 localStorage、sessionStorage 或其他客户端持久化。刷新页面会重新走初始化；关闭页面后草稿不恢复。

连接状态和已处理事件序号属于订阅，不当成棋盘或对局事实。

## 以后的页面如何复用

新页面从 `src/api/` 引入 HTTP、命令构造和 SSE 订阅，自己持有页面状态。需要同一套修订号、草稿脏检查或事件去重时，直接调用现有纯函数，不要复制一份。

页面若要新增接口，先对照 OpenAPI 的实现状态和该页的允许列表。解析器只接受契约里的字段；用不到的对象（例如游戏投影、模型配置）可以校验其存在后丢弃，不要为了展示去发明字段。

## 现在不要抽取的东西

- 席位栏、控制条、事件行。它们是实验室布局，不是通用设计系统。
- 视觉主题、棋盘、决策详情、历史与回放。
- 把 `useLaboratory` 拆成多个共享 hook。编排仍是这一页的流程。
- OpenAPI 代码生成。当前类型手写在 `src/api/types.ts` 与 `src/api/parse.ts`，范围只覆盖本页用到的结构。

## 产品边界

实验室页是单房间、两个席位的操作面。席位 A/B 的 `agentId` 来自 `currentMatch.participants`，不写死 UUID。

页面提供草稿保存与应用，以及开始、暂停、单步、继续、重置。中间展示执行模式、执行状态、对局状态，终局展示 `result.status` 与 `result.reason`。下面的列表只追加 `EXECUTION_CHANGED`、`PROMPT_PUBLISHED`、`DECISION_STARTED`、`DECISION_FINISHED`、`ACTION_RESOLVED`、`MATCH_FINISHED`、`MATCH_RESET`、`ROOM_INITIALIZED`。`CONFIGURATION_PUBLISHED`、`SERVER_RECOVERED`、`ROOM_UNAVAILABLE` 只驱动刷新或重连，不进列表。

不渲染棋盘或 `currentMatch.game`。不提供模型设置、Runtime 切换、API Key、决策详情、历史对局或回放。不调用这些能力对应的排除接口，不调用 `/agent/v1`。不登录。事件列表只显示事件本身，不把事件当成权威棋盘。

命令 200 只表示短事务完成。开始和单步之后等待 SSE，不轮询对局或决策接口。
