# Bilibili AI Live MVP

可本地运行并接收哔哩哔哩官方开放直播弹幕的 AI 直播互动闭环：真实或模拟弹幕进入后端，经本地规则与 OpenAI-compatible API 生成回复后，自动推送到 OBS Browser Source Overlay。

## 当前能力

- `LiveChatEventIngress` 路径：手动注入模拟弹幕，不依赖哔哩哔哩凭证；认证后官方连接器也会提交相同的标准事件。
- 输入与输出双重审核：命中本地安全规则的弹幕不会调用模型；模型回复还会再次检查安全关键词和 160 字展示上限。输出被拦截、模型失败或空回复时，会在同一用户锁内回滚本轮 LangChain4j 记忆，避免污染后续上下文。
- OpenAI 双协议：通过 LangChain4j `@AiService` 和原生模型 SDK 调用真实模型，同时支持 Responses 与 Chat Completions。`@SystemMessage`、`@UserMessage` 由 LangChain4j 按所选协议映射；未配置模型时主聊天安全降级且不会上屏。
- 双通道结构化回复：模型一次返回完整 `overlayText`、可选短 `danmakuText` 和发送意图。Overlay 是主要输出；弹幕是独立辅助通道，单独审核、限制为 40 字并默认至少间隔 10 秒。
- 用户级短期记忆：记忆按 `平台 + 直播间 + 用户` 隔离，每位用户保留最近 8 条消息；Redis 保存最近 30 分钟的上下文，后端重启后仍可恢复。Java 进程内最多缓存 1000 个活跃记忆对象，超出后按最近最少使用淘汰，但不提前删除 Redis 数据。
- 直播间共享上下文：同一直播间最近 12 条公开对话保存在 Redis 滑动窗口中。LangChain4j Agent 仅在语义上需要理解“刚刚那个人”“用户一的说法”等跨用户指代时，自主调用 `recent_room_conversation` Tool；普通问候不会无条件携带公共上下文。新弹幕通过 Lua 原子增量追加，AI 回复按消息 ID 原地更新，不会全量重写窗口。
- 观众活动估算：接收官方 `LIVE_OPEN_PLATFORM_LIVE_ROOM_ENTER` 事件，并把弹幕也视为活跃行为。控制台显示“近 5 分钟活跃”和“本场累计观众”；由于官方进入事件在高流量时会限流且没有可靠退出事件，这两个数字是开放平台事件观测值，不是精确实时在线人数。
- 智能进场欢迎：官方和模拟进场事件按场次在 Redis 去重，默认聚合 6 秒、最多点名 3 位观众；欢迎使用主播配置中的欢迎语且不调用模型。模型生成、弹幕排队或 Overlay 正在逐字回答时会延后欢迎，等待超过 15 秒则放弃，保证提问观众始终优先。可用 `POST /api/mock/audience/entries` 模拟进场。
- 回复准入与调度：Redis 原子执行用户 3 秒冷却，并为整个应用设置默认每分钟最多 60 次模型调用。单个直播间默认允许 2 条回复并发生成、最多再等待 4 条；持久化、SSE 和 Overlay 发布仍严格按入队顺序提交。排队超过 20 秒或调度过载的消息会留下审计状态并停止生成。
- 紧急暂停：控制台可立即暂停或恢复自动回复；状态与操作时间持久化到 PostgreSQL，重启后仍保持。暂停期间不会调用模型；如果暂停发生在模型请求中，生成结果和本轮记忆也会被丢弃。
- 消息去重与审计：平台、直播间、用户、消息 ID 和事件时间完整入库；重复消息不会重复生成或上屏。回复记录默认保留 7 天并定时清理，避免本地数据库无限增长。
- 自动上屏：合格回复会自动进入 Overlay 事件流。控制台可以在“仅 Overlay”和“Overlay + 弹幕”之间切换，模式持久化；“Overlay + 弹幕”目前只用于本地模拟发送，因为开放直播文档没有提供主播账号发送弹幕的接口。
- 平台输出网关：`LivePlatformGateway` 隔离平台发送能力；发送失败只记录弹幕状态，不影响 Overlay。成功发送的消息会按平台消息 ID 和两分钟文本指纹登记，主播账号回流事件会被标记为 `ECHO_IGNORED`，不会触发模型循环。
- OBS Overlay：服务只绑定本机回环地址，本地 Overlay 无需 token；SSE 心跳维持 OBS 长连接。模型先通过 LangChain4j 流式完成全部工具轮次，最终结构化回复经完整审核后，再通过有界单线程 SSE 队列逐字上屏，因而不会泄露未审核 token，也不会让并发回复互相穿插。旧回复平滑上移并保留最近 6 条；每条回复同时显示官方弹幕中的观众昵称和原弹幕摘要。
- 可观测性：控制台显示实际模型名、最终选中的 OpenAI 协议、最近耗时/错误、熔断状态、Overlay 连接和排队状态；Actuator 分别记录物理模型请求、完整 Agent 生成、Tool、Redis 公共上下文读写、排队、处理和端到端耗时。

官方开放直播接入已经实现：后端通过签名 REST API 创建/心跳/结束场次，通过官方 WebSocket 协议鉴权、保活、解压并接收 `LIVE_OPEN_PLATFORM_DM` 与 `LIVE_OPEN_PLATFORM_LIVE_ROOM_ENTER`。后端启动后自动连接；普通断线、鉴权超时、长链半开、项目心跳连续失败和互动场次结束都会持续自动恢复，重试间隔采用最高一分钟的封顶退避。官方弹幕和本地模拟事件最终进入同一个 `LiveChatEventIngress`，因此去重、审核、用户记忆、模型回复和 Overlay 上屏行为一致。

## 哔哩哔哩官方接入

只把真实凭证填写到仓库根目录的 `.env`，不要填写到 `application.yml`、源码或聊天中：

```dotenv
BILIBILI_OPEN_LIVE_ENABLED=true
BILIBILI_AUTO_CONNECT=true
BILIBILI_ACCESS_KEY_ID=
BILIBILI_ACCESS_KEY_SECRET=
BILIBILI_APP_ID=0
BILIBILI_IDENTITY_CODE=
```

- `BILIBILI_ACCESS_KEY_ID`、`BILIBILI_ACCESS_KEY_SECRET`、`BILIBILI_APP_ID` 来自开放平台项目。
- `BILIBILI_IDENTITY_CODE` 是主播身份码，可从 [哔哩哔哩直播开放平台身份码页面](https://play-live.bilibili.com/) 获取；不要把身份码当作直播间号。
- 确认项目已申请或获批 `LIVE_OPEN_PLATFORM_DM` 消息类型，否则建立场次后也不会收到弹幕事件。
- 默认 `BILIBILI_AUTO_CONNECT=true`。后端启动后会在后台自动连接，状态依次进入 `STARTING`、`AUTHENTICATING`、`CONNECTED`；连接故障会保持 `RECONNECTING` 并自行恢复，无需重启 Java 进程。
- 控制接口为 `GET /api/bilibili/status`、`POST /api/bilibili/connect`、`POST /api/bilibili/disconnect`。正常停止后端时也会尽力调用官方 `/v2/app/end` 结束场次。
- 当前官方文档只定义事件接收，没有定义主播账号向直播间发送弹幕的 API。因此真实回复自动发布到 Overlay；B 站弹幕发送网关继续保持禁用，避免调用未公开接口。

## 模型配置

后端会从仓库根目录的 `.env` 读取模型配置。以 `.env.example` 为模板填写 `OPENAI_API_KEY`、`OPENAI_BASE_URL`、`OPENAI_MODEL` 与 `OPENAI_API_MODE`；`OPENAI_BASE_URL` 通常以 `/v1` 结尾。

主聊天保留 LangChain4j 的 `@AiService` 与消息注解。`RESPONSES` 使用原生 `OpenAiResponsesChatModel` 调用 `/v1/responses`，`CHAT_COMPLETIONS` 使用原生 `OpenAiChatModel` 调用 `/v1/chat/completions`。`AUTO` 会在首次真实请求时优先尝试 Responses；只有服务明确返回端点不存在或不支持时才切换到 Chat Completions，并在当前进程内缓存选择。空输出、鉴权失败、限流、超时或服务端错误不会触发协议切换。429、502、503、504 最多抖动重试一次；超时和永久错误不重试。连续失败 3 次后熔断 25 秒，随后自动半开探测，无需重启后端。

```dotenv
OPENAI_API_MODE=AUTO
# 也可以强制指定 RESPONSES 或 CHAT_COMPLETIONS
```

当前接入地址已验证 `grok-4.5` 支持 Responses。`OPENAI_BASE_URL`、`OPENAI_API_KEY` 或 `OPENAI_MODEL` 任意一个为空时，应用会安全禁用主聊天，且不会把回复发布到 Overlay。

### 可选联网搜索

模型本身不会因为使用了同一个名称就自动获得联网能力：Codex 对话运行在带有搜索工具的宿主环境中，而后端目前只调用了模型文本接口。项目现将联网能力作为一个受控的 LangChain4j Tool 接入；模型仅在问题需要近期或实时公开信息时调用它，最多返回 3 条结果，搜索内容也会被当成不可信文本处理。

默认关闭。`docker compose up -d` 会在本机启动免费的 SearXNG 元搜索服务；然后在仓库根目录 `.env` 配置并重启后端：

```dotenv
AI_WEB_SEARCH_ENABLED=true
```

该方案不需要搜索 API Key 或按次付费；它会使用公开搜索引擎，实际覆盖范围取决于引擎可用性与网络环境。它也不依赖当前 OpenAI-compatible 代理是否实现原生 `web_search`，所以继续兼容现有 `grok-4.5`、Responses 与 Chat Completions 自动降级。可通过 Actuator 指标 `ai.live.tools.web-search` 查看搜索调用耗时。

## 本地启动

1. 启动 Docker Desktop，然后在仓库根目录运行：

   ```powershell
   docker compose up -d
   ```

   该命令会同时启动 PostgreSQL 和 Redis；后端依赖二者。Redis 准入不可用时会记录为 `PROCESSING_FAILED`，记忆读写或模型调用失败时会记录为 `MODEL_FAILED`；两种情况都不会上屏或切换到另一份本地记忆。

2. 启动后端：

   ```powershell
   cd backend
   mvn spring-boot:run
   ```

3. 新开终端，安装并启动控制台：

   ```powershell
   cd frontend
   npm install
   npm run dev
   ```

4. 在浏览器打开 Vite 显示的地址，默认是 `http://localhost:5173`。配置好官方变量后，后端会自动连接 B 站；连接成功会显示真实直播间号。也可以继续使用模拟输入验证不同用户的独立记忆与直播间共享上下文。
5. 点击“生成 Overlay 链接”，再将链接添加为 OBS 的浏览器源。真实或模拟弹幕产生的合格回复都会自动上屏。切换到“Overlay + 弹幕”目前只验证本地模拟发送、长度限制和限频；紧急暂停会停止模型回复和两个输出通道。

完整验收步骤和官方接入检查项见 [`PRE_APPROVAL_CHECKLIST.md`](PRE_APPROVAL_CHECKLIST.md)。

## 验证

```powershell
cd backend
mvn test
```

Docker Desktop 运行时，测试会额外启动隔离的 PostgreSQL 与 Redis 容器，验证 Spring 全量接线、Flyway 迁移和 Redis 连通性；Docker 不可用时该项自动跳过，其余单元测试继续执行。

需要手动消耗远程模型额度验证 Responses 结构化输出与 Tool Calling 时运行：

```powershell
mvn -Dremote-ai-test=true -Dtest=RemoteResponsesToolCallingTest test
```

验收链路：`健康状态正常 → Overlay 已连接 → 同一用户连续弹幕可引用上下文 → 切换用户后上下文隔离 → 双通道模式显示模拟弹幕发送结果 → 高频消息与 AI 弹幕分别被限流 → 不安全输出不会发布 → 紧急暂停后两个输出通道都停止 → 恢复后重新自动发布`。

## 安全边界

- `.env` 与任何真实凭证均不提交；请从 `.env.example` 创建本地配置。
- 后端强制绑定回环地址；在尚未实现操作员认证时，配置为 `0.0.0.0` 或局域网地址会拒绝启动。
- Overlay 仅供本机使用，不带 token；不要把后端监听地址改成局域网或公网地址。
- 当前控制台仅适用于本机开发；在局域网或公网部署前必须补充操作员认证。
