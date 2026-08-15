# 纸艺信使 Live2D 制作规范

## 1. 制作目标

将现有纸艺信使设计制作成真正的 Live2D Cubism 模型，并由 Overlay 已有的六种状态驱动：

- `idle`
- `received`
- `thinking`
- `speaking`
- `error`
- `reconnecting`

角色只负责传达主播的注意力和情绪，不遮挡右侧回复文字，也不使用 CSS 假装骨骼动画。

## 2. 原画与画布

- 源文件：分层 PSD，透明背景，RGB/sRGB。
- 建议画布：4096 × 4096 px；角色完整站姿居中，四周保留至少 12% 运动空间。
- 可见范围：以头部、上半身和信封为视觉重点，尾巴、耳朵、翅膀完整保留。
- 画风：延续现有水彩晨光房间，纸张主体为奶油白，折角使用薄荷绿和蜜桃色。
- 线条：避免纯黑描边，使用低饱和暖灰或纸张阴影色。
- 阴影：各可动部件下方必须补全被遮挡区域，不能只切割当前 PNG 的可见像素。
- 禁止：背景、文字、桌面阴影烘焙进角色贴图。

现有 `frontend/src/assets/paper-messenger.png` 只作为造型参考，不能直接作为最终建模源文件。

## 3. PSD 图层结构

建议按下面顺序从后向前组织。左右方向均以角色自身视角命名。

```text
paper_messenger
├─ back
│  ├─ tail_back
│  ├─ wing_back_l
│  ├─ wing_back_r
│  └─ cape_back
├─ body
│  ├─ body_back
│  ├─ torso
│  ├─ body_fold_shadow
│  ├─ arm_l
│  ├─ arm_r
│  ├─ cape_front
│  └─ scarf
├─ head
│  ├─ head_base
│  ├─ face_shadow
│  ├─ ear_l_outer
│  ├─ ear_l_inner
│  ├─ ear_r_outer
│  ├─ ear_r_inner
│  ├─ brow_l
│  ├─ brow_r
│  ├─ eye_l_white
│  ├─ eye_l_iris
│  ├─ eye_l_highlight
│  ├─ eye_l_lid
│  ├─ eye_r_white
│  ├─ eye_r_iris
│  ├─ eye_r_highlight
│  ├─ eye_r_lid
│  ├─ muzzle
│  ├─ mouth_closed
│  └─ mouth_inner
├─ prop
│  ├─ envelope_base
│  ├─ envelope_flap
│  └─ envelope_mark
├─ front
│  ├─ wing_front_l
│  └─ wing_front_r
└─ effects_optional
   ├─ paper_note_01
   ├─ paper_note_02
   └─ paper_note_03
```

### 分层硬性要求

1. 左右眼白、虹膜、高光和眼皮必须独立。
2. 闭嘴与口腔必须独立，口腔后方补全颜色。
3. 耳朵内外层独立，耳根处补足旋转需要的纹理。
4. 翅膀、手臂、尾巴和信封必须完整绘制被遮挡部分。
5. 信封翻盖独立，以便在 `received` 和 `thinking` 状态轻微开合。
6. 纸张折痕可保留在对应部件中，但跨关节折痕需要拆分，避免变形时断裂。

## 4. Cubism 参数

优先使用 Cubism 标准参数 ID，项目自定义参数统一使用 `ParamPaper` 前缀。

| 参数 | 建议范围 | 用途 |
| --- | ---: | --- |
| `ParamAngleX` | -30…30 | 头部左右转动 |
| `ParamAngleY` | -30…30 | 头部抬头、低头 |
| `ParamAngleZ` | -30…30 | 头部侧倾 |
| `ParamBodyAngleX` | -10…10 | 身体左右朝向 |
| `ParamBodyAngleY` | -10…10 | 身体前后倾 |
| `ParamBodyAngleZ` | -10…10 | 身体轻摆 |
| `ParamEyeLOpen` | 0…1 | 左眼开合 |
| `ParamEyeROpen` | 0…1 | 右眼开合 |
| `ParamEyeBallX` | -1…1 | 视线左右 |
| `ParamEyeBallY` | -1…1 | 视线上下 |
| `ParamMouthOpenY` | 0…1 | 说话口型 |
| `ParamMouthForm` | -1…1 | 困惑到微笑 |
| `ParamBreath` | 0…1 | 呼吸和纸张起伏 |
| `ParamPaperEarL` | -1…1 | 左耳动作 |
| `ParamPaperEarR` | -1…1 | 右耳动作 |
| `ParamPaperWingL` | -1…1 | 左翅膀动作 |
| `ParamPaperWingR` | -1…1 | 右翅膀动作 |
| `ParamPaperTail` | -1…1 | 尾巴摆动 |
| `ParamPaperEnvelope` | 0…1 | 信封翻盖开合 |
| `ParamPaperFloat` | -1…1 | 全身轻微漂浮 |
| `ParamPaperFocus` | 0…1 | 专注神态强度 |
| `ParamPaperOffline` | 0…1 | 断线时失色、收拢 |

## 5. 表情

导出以下 `.exp3.json` 表情：

- `normal`：自然睁眼、轻微微笑。
- `happy`：眼睛更明亮，嘴角上扬，耳朵抬起。
- `focused`：视线下移到信封，眉毛轻微收拢。
- `confused`：眉毛不对称、嘴形变平、耳朵略垂。
- `offline`：眼神失焦、饱和度降低、身体和翅膀收拢。

表情切换时长建议为 250–450 ms，避免突然跳变。

## 6. 动作文件

导出以下 `.motion3.json` 动作组：

- `Idle`：两段可循环动作，包含呼吸、随机眨眼、轻微漂浮和尾巴摆动。
- `Received`：转向观众、耳朵抬起、信封轻弹一次，约 0.7–1.0 秒。
- `Thinking`：低头看信封、视线缓慢移动、信封翻盖轻开合，可循环。
- `Speaking`：身体与翅膀随节奏轻动，可循环；嘴型由运行时单独控制。
- `Error`：短暂停顿后困惑侧头，约 1.2–1.8 秒。
- `Reconnecting`：抱紧信封、耳朵下垂、动作幅度降低，可循环。

所有动作应保持角色在左侧安全区内，不进入 Overlay 的主要文字区域。

## 7. Overlay 状态映射

| Overlay 状态 | 表情 | 动作 | 观众感知目标 |
| --- | --- | --- | --- |
| `idle` | `normal` | `Idle` | 主播在线且自然等待 |
| `received` | `happy` | `Received` | 立即确认弹幕已收到 |
| `thinking` | `focused` | `Thinking` | 明确告诉观众正在组织答案 |
| `speaking` | `happy` | `Speaking` | 角色与流式文字同步回应 |
| `error` | `confused` | `Error` | 异常可见但不过度打扰 |
| `reconnecting` | `offline` | `Reconnecting` | 明确显示断线和恢复过程 |

当前没有 TTS，`speaking` 的 `ParamMouthOpenY` 先由流式文字到达节奏驱动；接入 TTS 后再切换为音频 Lip Sync。

## 8. Web 导出物

最终模型目录放置在：

```text
frontend/public/live2d/paper-messenger/
├─ paper-messenger.moc3
├─ paper-messenger.model3.json
├─ paper-messenger.physics3.json
├─ paper-messenger.cdi3.json
├─ textures/
├─ motions/
├─ expressions/
└─ model-state-map.json
```

纹理优先使用 2048 或 4096 尺寸，具体取值以 OBS 浏览器源中的清晰度和显存占用实测决定。

## 9. 验收标准

1. 角色轮廓没有关节裂缝、透明缝或明显拉伸。
2. 六种 Overlay 状态都能在 500 ms 内给出可识别的视觉反馈。
3. 直播压缩后仍能看清眼睛、嘴部和耳朵动作。
4. `speaking` 动作不抢夺右侧回复文字的注意力。
5. 断线重连不会重建 WebGL 画布或闪白。
6. `prefers-reduced-motion` 或关闭动画时，模型保持自然静态姿势，回复文字正常工作。
7. Live2D 加载失败时回退到现有 `paper-messenger.png`，Overlay 不出现空白角色区。

## 10. 实施顺序

1. 按本规范完成分层 PSD。
2. 在 Live2D Cubism Editor 中建模、绑定参数、物理和动作。
3. 导出 Cubism SDK for Web 所需模型文件。
4. 在前端加入 Cubism Core 和渲染适配层。
5. 将 `data-host-state` 状态绑定至表情、动作和口型。
6. 在 OBS 横屏浏览器源中验证性能、清晰度和状态切换。

