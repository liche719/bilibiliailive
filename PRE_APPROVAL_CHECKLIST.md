# 哔哩哔哩 AI 直播完成清单

## 已完成

- [x] 标准化直播事件接口和本地模拟事件入口，不依赖哔哩哔哩凭证。
- [x] LangChain4j `@AiService` 主播模型，支持 Responses、Chat Completions 和自动协议选择。
- [x] Redis 用户短期记忆、用户隔离、窗口限制、TTL、损坏数据恢复和隐私化 Key。
- [x] 输入审核、输出审核、展示长度限制，以及模型失败、空输出、暂停和输出拦截时的记忆回滚。
- [x] 消息去重、用户冷却、全局模型调用上限、每房间顺序执行、有界队列和过期丢弃。
- [x] 自动上屏到 OBS Overlay，不要求逐条人工批准。
- [x] 结构化双通道回复、持久化输出模式开关、本地模拟弹幕网关、40 字限制、10 秒发送间隔和独立发送状态审计。
- [x] 主播账号消息防回声：平台消息 ID 与短期文本指纹匹配，回流消息不会再次调用模型。
- [x] PostgreSQL 回复审计、运行暂停审计和 7 天回复记录自动清理。
- [x] 持久化紧急暂停；暂停操作返回后，不会再发布新的 Overlay 回复。
- [x] 一次性 Overlay 引导令牌、只读 Cookie 会话、会话过期/数量上限和 SSE 心跳。
- [x] Overlay 按观众消息顺序从底部新增回复、旧消息上滑，并展示 B 站观众昵称和原弹幕摘要。
- [x] Actuator 健康检查，以及模型、准入、结果、队列、连接和清理指标。
- [x] 本机安全边界：没有操作员认证前禁止绑定非回环地址。
- [x] 控制台显示模型、基础设施、Overlay、队列和暂停状态。
- [x] 单元测试、可选 Testcontainers 全量接线测试、前端生产构建和 Compose 配置检查。
- [x] 官方 `/v2/app/start`、`/v2/app/heartbeat`、`/v2/app/end` 请求签名与响应校验。
- [x] 官方 WebSocket 鉴权、心跳、版本 0/1 数据包、版本 2 zlib 嵌套包、断线重连与多地址回退。
- [x] `LIVE_OPEN_PLATFORM_DM` 转换为统一直播事件，使用 `open_id` 隔离用户记忆并识别主播账号回流。
- [x] 控制台展示官方连接状态、真实直播间号，并提供手动连接和断开操作。

## 本地验收

1. 启动 Docker Desktop，在项目根目录执行 `docker compose up -d`。
2. 在 `backend` 执行 `mvn spring-boot:run`，确认 `/actuator/health` 返回 `UP`。
3. 在 `frontend` 执行 `npm run dev`，打开控制台。
4. 生成一次性 Overlay 链接并在新标签页或 OBS 浏览器源打开，确认控制台显示 Overlay 已连接。
5. 使用同一模拟用户连续发送两条弹幕，确认第二条能够使用上文。
6. 切换模拟用户，确认不会读取前一个用户的上下文。
7. 连续快速发送，确认出现 `RATE_LIMITED` 或 `OVERLOADED`，且不会调用模型或上屏。
8. 发送触发安全规则的内容，确认状态为 `BLOCKED` 且 Overlay 不变化。
9. 点击紧急暂停，再发送弹幕，确认状态为 `PAUSED`；重启后端后仍保持暂停。
10. 点击恢复，发送安全弹幕，确认重新自动上屏。
11. 切换为“Overlay + 弹幕”，发送一条明确问答或欢迎类消息，确认 Overlay 展示完整回复，记录中显示独立的模拟弹幕短句与发送状态。
12. 在 10 秒内触发第二条 AI 弹幕，确认 Overlay 仍正常，而弹幕状态为 `SKIPPED`；切回“仅 Overlay”并重启后端，确认模式持久化。

## 官方联调

- [ ] 在本地 `.env` 填写 Access Key ID、Access Key Secret、App ID、主播身份码，并设置 `BILIBILI_OPEN_LIVE_ENABLED=true`。
- [ ] 确认开放平台项目已申请或获批 `LIVE_OPEN_PLATFORM_DM` 消息类型。
- [ ] 启动直播后从控制台连接，确认状态依次为 `STARTING`、`AUTHENTICATING`、`CONNECTED`，且显示的直播间号正确。
- [ ] 使用另一个账号发送真实弹幕，确认事件只处理一次、按发送者保留独立记忆并自动发布到 Overlay。
- [ ] 手动断网后恢复，确认状态进入 `RECONNECTING` 后回到 `CONNECTED`，且不会建立重复场次。
- [ ] 点击断开连接，确认状态回到 `DISCONNECTED`；检查脱敏日志中没有 Access Key Secret、身份码或 `auth_body`。
- [ ] 官方文档未提供主播账号发送弹幕 API，真实运行保持 `OVERLAY_ONLY`；只有未来获得明确官方能力和文档后才实现 B 站输出网关。

## 当前不做

虚拟人动画、TTS、语音识别、RAG、工具调用、礼物自动化、多平台、多租户、微服务、Kafka 和 Kubernetes 不属于认证前基础闭环。它们应在真实弹幕接入稳定后按实际需求逐项评估。
