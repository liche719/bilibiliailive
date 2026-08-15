import { useEffect, useLayoutEffect, useMemo, useRef, useState, type RefObject } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Live2DPaperMessenger, type PaperMessengerState } from './Live2DPaperMessenger'
import paperMessenger from './assets/paper-messenger.png'
import paperRoomBackground from './assets/paper-room-background-watercolor.png'

type ReplyStatus = 'AUTO_PUBLISHED' | 'ECHO_IGNORED' | 'BLOCKED' | 'PAUSED' | 'RATE_LIMITED' | 'OVERLOADED' | 'MODEL_FAILED' | 'PROCESSING_FAILED'
type LiveOutputMode = 'OVERLAY_ONLY' | 'OVERLAY_AND_DANMAKU'
type DanmakuDeliveryStatus = 'NOT_REQUESTED' | 'PENDING' | 'SKIPPED' | 'SENT' | 'FAILED'
type BilibiliConnectionState = 'DISABLED' | 'NOT_CONFIGURED' | 'DISCONNECTED' | 'STARTING' | 'AUTHENTICATING' | 'CONNECTED' | 'RECONNECTING' | 'STOPPING' | 'FAILED'

type Candidate = {
  id: string
  platform: string
  roomId: string
  senderId: string
  senderName: string
  messageId: string
  sourceText: string
  candidateText: string | null
  danmakuText: string | null
  danmakuStatus: DanmakuDeliveryStatus
  danmakuPlatformMessageId: string | null
  danmakuDecisionReason: string | null
  status: ReplyStatus
  decisionReason: string | null
  occurredAt: string
  createdAt: string
}

type OverlayStreamStart = {
  candidateId: string
  messageId: string
  senderName: string
  sourceText: string
  audioUrl: string | null
  volume: number
}

type TtsSettings = { enabled: boolean; muted: boolean; voice: string; rate: number; volume: number }

type OverlayReplyStart = {
  messageId: string
  senderName: string
  sourceText: string
}

type OverlayReplyReceived = OverlayReplyStart

type OverlayStreamUpdate = {
  candidateId: string
  text: string
  completed: boolean
}

type OverlayWelcome = {
  id: string
  roomId: string
  viewerNames: string[]
  totalViewers: number
  text: string
  displayDurationMs: number
  occurredAt: string
}

type OverlayMessage = {
  id: string
  senderName: string
  sourceText: string
  candidateText: string
  streaming: boolean
}

const splitToolAttribution = (value: string) => {
  const marker = '\n调用工具：搜索'
  const markerIndex = value.indexOf(marker)
  return markerIndex < 0
    ? { text: value, tool: null as string | null }
    : { text: value.slice(0, markerIndex), tool: '调用工具：搜索' }
}

type OverlayReplyPhase = 'received' | 'thinking'

type OverlayReplyLifecycle = {
  messageId: string
  senderName: string
  sourceText: string
  phase: OverlayReplyPhase
  receivedAt: number
}

type OverlayConnectionState = 'CONNECTING' | 'CONNECTED' | 'RECONNECTING'
type OverlayStageMode = PaperMessengerState

type RuntimeStatus = {
  modelConfigured: boolean
  modelName: string | null
  configuredApiMode: 'AUTO' | 'RESPONSES' | 'CHAT_COMPLETIONS'
  activeApiMode: 'UNKNOWN' | 'RESPONSES' | 'CHAT_COMPLETIONS'
  lastModelCallAt: string | null
  lastModelDurationMs: number | null
  lastModelError: string | null
  consecutiveModelFailures: number
  circuitState: 'CLOSED' | 'OPEN' | 'HALF_OPEN'
  circuitOpenUntil: string | null
  bilibiliOpenLiveEnabled: boolean
  paused: boolean
  outputMode: LiveOutputMode
  activeRooms: number
  pendingReplies: number
  maxPendingPerRoom: number
  maxConcurrentPerRoom: number
  maxModelCallsPerWindow: number
  overlaySubscribers: number
  controlSubscribers: number
  recentlyActiveViewers: number
  observedSessionViewers: number
  audienceEstimated: boolean
}

type RuntimeControl = { paused: boolean; outputMode: LiveOutputMode; actor: string; changedAt: string }
type HealthStatus = { status: string }
type BilibiliConnectionStatus = {
  state: BilibiliConnectionState
  roomId: number | null
  gameId: string | null
  connectedAt: string | null
  lastError: string | null
}

const request = async <T,>(path: string, options?: RequestInit): Promise<T> => {
  const response = await fetch(path, {
    headers: { 'Content-Type': 'application/json', ...(options?.headers ?? {}) },
    ...options,
  })
  if (!response.ok) {
    throw new Error('请求未完成，请检查后端服务。')
  }
  return response.status === 204 ? (undefined as T) : response.json() as Promise<T>
}

const statusLabel: Partial<Record<ReplyStatus, string>> = {
  AUTO_PUBLISHED: '自动上屏',
  ECHO_IGNORED: '回流忽略',
  BLOCKED: '安全拦截',
  PAUSED: '紧急暂停',
  RATE_LIMITED: '频率限制',
  OVERLOADED: '过载丢弃',
  MODEL_FAILED: '模型失败',
  PROCESSING_FAILED: '处理失败',
}

const maxOverlayMessages = 7

const retainRecentOverlayMessages = (messages: OverlayMessage[]) => messages
  .slice(-maxOverlayMessages)

const completedOverlayMessage = (candidate: Candidate): OverlayMessage | null => {
  if (!candidate.candidateText) return null
  return {
    id: candidate.id,
    senderName: candidate.senderName,
    sourceText: candidate.sourceText,
    candidateText: candidate.candidateText,
    streaming: false,
  }
}

const upsertOverlayMessage = (current: OverlayMessage[], next: OverlayMessage) => retainRecentOverlayMessages([
  ...current.filter((message) => message.id !== next.id),
  next,
])

const upsertReplyLifecycle = (
  current: OverlayReplyLifecycle[],
  next: Omit<OverlayReplyLifecycle, 'receivedAt'>,
) => {
  const existing = current.find((reply) => reply.messageId === next.messageId)
  return [
    ...current.filter((reply) => reply.messageId !== next.messageId),
    { ...next, receivedAt: existing?.receivedAt ?? Date.now() },
  ].sort((left, right) => left.receivedAt - right.receivedAt)
}

const parseSseEvent = <T,>(event: MessageEvent<string>): T | null => {
  try {
    return JSON.parse(event.data) as T
  } catch {
    return null
  }
}

const overlayLengthClass = (text: string) => {
  const length = Array.from(text).length
  if (length > 80) return 'long'
  if (length > 42) return 'medium'
  return 'short'
}

const overlayOutcomeMessage = (outcome: Candidate) => {
  const sender = `@${outcome.senderName}`
  switch (outcome.status) {
    case 'BLOCKED':
      return `${sender}，这条内容不方便回答，我们换个话题吧。`
    case 'PAUSED':
      return `${sender}，主播现在暂停回复，稍后再来找我吧。`
    case 'RATE_LIMITED':
      if (isViewerAlreadyWaitingOutcome(outcome)) {
        return `${sender}，你的上一条还在排队或思考，这条先不重复占位。`
      }
      return `${sender}，弹幕来得有点快，稍等一下再问我吧。`
    case 'OVERLOADED':
      return `${sender}，刚才消息太多，这条没赶上回复，可以再发一次。`
    case 'MODEL_FAILED':
      return `${sender}，刚刚没有想好答案，可以再问我一次。`
    case 'PROCESSING_FAILED':
      return `${sender}，回复服务刚刚开了一下小差，请稍后再试。`
    default:
      return `${sender}，这条弹幕暂时没能回复。`
  }
}

const isViewerAlreadyWaitingOutcome = (outcome: Candidate | null) => outcome?.status === 'RATE_LIMITED'
  && Boolean(outcome.decisionReason?.includes('未重复占用位置'))

const bilibiliStateLabel: Record<BilibiliConnectionState, string> = {
  DISABLED: '未启用',
  NOT_CONFIGURED: '待配置',
  DISCONNECTED: '未连接',
  STARTING: '开启场次中',
  AUTHENTICATING: '长链鉴权中',
  CONNECTED: '真实弹幕已连接',
  RECONNECTING: '断线重连中',
  STOPPING: '关闭场次中',
  FAILED: '连接失败',
}

const apiModeLabel: Record<RuntimeStatus['activeApiMode'], string> = {
  UNKNOWN: '等待首次调用',
  RESPONSES: 'Responses API',
  CHAT_COMPLETIONS: 'Chat Completions',
}

const circuitLabel: Record<RuntimeStatus['circuitState'], string> = {
  CLOSED: '正常',
  OPEN: '自动恢复等待中',
  HALF_OPEN: '正在探测恢复',
}

function PaperHost({ state, mouthLevelRef }: { state: OverlayStageMode; mouthLevelRef: RefObject<number> }) {
  const [live2dReady, setLive2dReady] = useState(false)
  return (
    <div className={`paper-host ${live2dReady ? 'has-live2d' : ''}`} aria-hidden="true">
      <span className="host-floor-shadow" />
      <span className="floating-note note-one" />
      <span className="floating-note note-two" />
      <span className="floating-note note-three" />
      <Live2DPaperMessenger state={state} mouthLevelRef={mouthLevelRef} onReadyChange={setLive2dReady} />
      <img
        className="paper-host-art"
        src={paperMessenger}
        alt=""
        onError={(event) => {
          event.currentTarget.hidden = true
          event.currentTarget.nextElementSibling?.classList.add('is-visible')
        }}
      />
      <svg className="paper-host-fallback" viewBox="0 0 420 520" role="presentation">
        <defs>
          <linearGradient id="white-paper" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0" stopColor="#fffdf6" />
            <stop offset=".6" stopColor="#f7f3e8" />
            <stop offset="1" stopColor="#ddd9ca" />
          </linearGradient>
          <linearGradient id="mint-paper" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0" stopColor="#b8ead1" />
            <stop offset="1" stopColor="#69b49a" />
          </linearGradient>
          <linearGradient id="peach-paper" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0" stopColor="#ffe1ac" />
            <stop offset="1" stopColor="#f4b57f" />
          </linearGradient>
          <filter id="paper-shadow" x="-30%" y="-30%" width="160%" height="180%">
            <feDropShadow dx="0" dy="14" stdDeviation="13" floodColor="#755c3a" floodOpacity=".2" />
          </filter>
        </defs>
        <g className="host-figure" filter="url(#paper-shadow)">
          <g className="host-tail">
            <path d="M128 345 L45 383 L82 429 L31 451 L108 465 L170 395 Z" fill="url(#mint-paper)" stroke="#5fa28c" strokeWidth="3" strokeLinejoin="round" />
            <path d="M45 383 L108 465 L82 429 Z" fill="#f7f4e9" opacity=".88" />
          </g>
          <g className="host-body">
            <path d="M128 281 L212 259 L301 293 L344 406 L277 390 L235 472 L177 411 L91 429 Z" fill="url(#white-paper)" stroke="#d4d0c1" strokeWidth="4" strokeLinejoin="round" />
            <path d="M128 281 L212 259 L177 411 L91 429 Z" fill="#e7efe4" opacity=".86" />
            <path d="M212 259 L301 293 L277 390 L235 472 L177 411 Z" fill="#fffdf6" />
            <path d="M301 293 L344 406 L277 390 Z" fill="#d8e8de" />
            <path d="M177 411 L235 472 L277 390 L212 402 Z" fill="#efecdf" />
          </g>
          <g className="host-wing host-wing-left">
            <path d="M133 304 L48 337 L91 385 L162 354 Z" fill="#f8f5ea" stroke="#d4d0c1" strokeWidth="3" strokeLinejoin="round" />
            <path d="M48 337 L91 385 L97 345 Z" fill="url(#mint-paper)" opacity=".84" />
          </g>
          <g className="host-wing host-wing-right">
            <path d="M290 307 L378 337 L335 388 L262 352 Z" fill="#f8f5ea" stroke="#d4d0c1" strokeWidth="3" strokeLinejoin="round" />
            <path d="M378 337 L335 388 L330 344 Z" fill="url(#peach-paper)" opacity=".84" />
          </g>
          <g className="host-head">
            <path d="M108 173 L108 75 L177 137 L218 117 L263 139 L333 82 L323 187 L349 239 L295 310 L213 335 L127 306 L78 238 Z" fill="url(#white-paper)" stroke="#d4d0c1" strokeWidth="4" strokeLinejoin="round" />
            <path d="M108 75 L177 137 L108 173 Z" fill="url(#mint-paper)" stroke="#65a98f" strokeWidth="3" strokeLinejoin="round" />
            <path d="M333 82 L323 187 L263 139 Z" fill="url(#peach-paper)" stroke="#d69e6d" strokeWidth="3" strokeLinejoin="round" />
            <path d="M78 238 L177 137 L213 335 L127 306 Z" fill="#f1f0e7" opacity=".78" />
            <path d="M177 137 L218 117 L263 139 L213 335 Z" fill="#fffdf7" />
            <path d="M263 139 L349 239 L295 310 L213 335 Z" fill="#ebe9dd" opacity=".72" />
            <path d="M108 173 L177 137 L78 238 Z" fill="#d9eee2" opacity=".68" />
            <path d="M323 187 L263 139 L349 239 Z" fill="#f8dfbd" opacity=".68" />
            <g className="host-face">
              <ellipse className="host-eye host-eye-left" cx="178" cy="236" rx="8" ry="13" fill="#73b39d" />
              <ellipse className="host-eye host-eye-right" cx="252" cy="236" rx="8" ry="13" fill="#73b39d" />
              <circle cx="180" cy="232" r="2.5" fill="#fff" />
              <circle cx="254" cy="232" r="2.5" fill="#fff" />
              <path className="host-mouth" d="M207 264 Q214 270 221 264" fill="none" stroke="#6f6357" strokeWidth="3" strokeLinecap="round" />
            </g>
          </g>
          <g className="host-chest-fold">
            <path d="M128 281 L212 259 L301 293 L211 352 Z" fill="#f9f7ed" stroke="#d4d0c1" strokeWidth="3" />
            <path d="M128 281 L211 352 L177 411 Z" fill="#b7ddca" opacity=".7" />
          </g>
        </g>
      </svg>
    </div>
  )
}

function MorningRoom() {
  return (
    <div className="morning-room" aria-hidden="true">
      <div className="room-window">
        <span className="sky-cloud cloud-a" />
        <span className="sky-cloud cloud-b" />
        <span className="window-frame frame-vertical" />
        <span className="window-frame frame-horizontal" />
        <span className="curtain curtain-left" />
        <span className="curtain curtain-right" />
      </div>
      <div className="wall-shelf">
        <span className="shelf-book book-one" />
        <span className="shelf-book book-two" />
        <span className="shelf-book book-three" />
        <span className="shelf-frame" />
        <span className="shelf-plant"><i /><i /><i /></span>
      </div>
      <div className="desk-lamp"><span className="lamp-shade" /><span className="lamp-neck" /><span className="lamp-base" /></div>
      <div className="desk-monitor"><span className="monitor-screen" /><span className="monitor-neck" /><span className="monitor-base" /></div>
      <div className="room-desk"><span className="desk-edge" /><span className="desk-leg leg-left" /><span className="desk-leg leg-right" /></div>
      <div className="desk-plant"><span className="plant-pot" /><i /><i /><i /><i /></div>
      <span className="sunbeam" />
    </div>
  )
}

function Overlay() {
  const [messages, setMessages] = useState<OverlayMessage[]>([])
  const [ready, setReady] = useState(false)
  const [connectionState, setConnectionState] = useState<OverlayConnectionState>('CONNECTING')
  const [replyLifecycles, setReplyLifecycles] = useState<OverlayReplyLifecycle[]>([])
  const [replyOutcomes, setReplyOutcomes] = useState<Candidate[]>([])
  const [welcomeNotice, setWelcomeNotice] = useState<OverlayWelcome | null>(null)
  const feedElement = useRef<HTMLElement>(null)
  const messageElements = useRef(new Map<string, HTMLElement>())
  const previousMessageIds = useRef(new Set<string>())
  const streamRevision = useRef(0)
  const mouthLevelRef = useRef(0)
  const latestMessage = messages.at(-1)
  const hasActiveStream = Boolean(latestMessage?.streaming)
  const replyOutcome = replyOutcomes[0] ?? null
  const viewerAlreadyWaiting = isViewerAlreadyWaitingOutcome(replyOutcome)
  const activeLifecycle = replyLifecycles.find((reply) => reply.phase === 'thinking')
    ?? replyLifecycles[0]
    ?? null
  const showOutcome = Boolean(replyOutcome && !hasActiveStream)
  const showPendingReply = Boolean(activeLifecycle?.phase === 'thinking' && !hasActiveStream && !showOutcome)
  const showReceivedReply = Boolean(activeLifecycle?.phase === 'received' && !hasActiveStream && !showOutcome)
  const showWelcome = Boolean(welcomeNotice
    && !hasActiveStream
    && !showOutcome
    && !showPendingReply
    && !showReceivedReply)
  const showTransientReply = showOutcome || showPendingReply || showReceivedReply
  const stageMode: OverlayStageMode = connectionState === 'RECONNECTING'
    ? 'reconnecting'
    : hasActiveStream
      ? latestMessage?.candidateText
        ? 'speaking'
        : 'thinking'
      : showOutcome
        ? viewerAlreadyWaiting ? 'received' : 'error'
      : showPendingReply
        ? 'thinking'
      : showReceivedReply
        ? 'received'
      : showWelcome
        ? 'welcoming'
        : 'idle'
  const activeSenderName = latestMessage?.streaming
    ? latestMessage.senderName
    : replyOutcome?.senderName ?? activeLifecycle?.senderName
  const activeSourceText = latestMessage?.streaming
    ? latestMessage.sourceText
    : replyOutcome?.sourceText ?? activeLifecycle?.sourceText ?? latestMessage?.sourceText
  const queuedReplies = replyLifecycles
    .filter((reply) => reply.messageId !== activeLifecycle?.messageId || hasActiveStream || showOutcome)
    .slice(0, 3)
  const historyMessages = showTransientReply ? messages : messages.slice(0, -1)
  const transientMessage = showOutcome && replyOutcome
    ? overlayOutcomeMessage(replyOutcome)
    : showPendingReply
      ? '弹幕已经收到，正在认真想一个合适的回答…'
      : showReceivedReply
        ? `@${activeLifecycle?.senderName ?? '观众'}，你的弹幕收到啦，马上轮到我。`
        : null
  const currentMessageText = transientMessage
    ?? latestMessage?.candidateText
    ?? (ready ? '发一条弹幕，纸信使会把回复送到这里。' : '正在整理桌面，马上回来。')
  const stageStatus = stageMode === 'reconnecting'
    ? '正在找回直播信号'
    : viewerAlreadyWaiting
      ? `已保留 @${activeSenderName ?? '观众'} 的上一条，这条未重复排队`
    : stageMode === 'received'
      ? `已收到 @${activeSenderName ?? '观众'} 的弹幕`
    : stageMode === 'thinking'
      ? `正在思考 @${activeSenderName ?? '观众'} 的问题`
    : stageMode === 'speaking'
      ? `正在回应 @${latestMessage?.senderName ?? '观众'}`
    : stageMode === 'welcoming'
      ? '正在欢迎刚进来的朋友'
    : stageMode === 'error'
      ? `@${activeSenderName ?? '观众'} 的这条暂时没能回复`
      : messages.length > 0
          ? '和大家一起聊天中'
          : ready
            ? '等你发来第一条弹幕'
            : '正在进入直播间'

  useLayoutEffect(() => {
    document.documentElement.classList.add('overlay-document')
    document.body.classList.add('overlay-document')
    return () => {
      document.documentElement.classList.remove('overlay-document')
      document.body.classList.remove('overlay-document')
    }
  }, [])

  useLayoutEffect(() => {
    let addedMessage = false
    const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    messages.forEach((candidate) => {
      const element = messageElements.current.get(candidate.id)
      if (!element) return
      if (!previousMessageIds.current.has(candidate.id)) {
        addedMessage = true
        if (!reducedMotion) {
          element.animate(
            [
              { opacity: 0, transform: 'translate3d(0, 22px, 0) scale(.96)' },
              { opacity: 1, transform: 'translate3d(0, 0, 0) scale(1)' },
            ],
            { duration: 460, easing: 'cubic-bezier(.16, 1, .3, 1)' },
          )
        }
      }
    })
    previousMessageIds.current = new Set(messages.map((candidate) => candidate.id))
    const feed = feedElement.current
    if (feed) {
      feed.scrollTo({
        top: feed.scrollHeight,
        behavior: addedMessage && !reducedMotion
          ? 'smooth'
          : 'auto',
      })
    }
  }, [messages])

  useEffect(() => {
    if (!showOutcome || !replyOutcome) return
    const timeout = window.setTimeout(() => {
      setReplyOutcomes((current) => current.filter((outcome) => outcome.id !== replyOutcome.id))
    }, 6500)
    return () => window.clearTimeout(timeout)
  }, [replyOutcome, showOutcome])

  useEffect(() => {
    if (!welcomeNotice) return
    const timeout = window.setTimeout(() => {
      setWelcomeNotice((current) => current?.id === welcomeNotice.id ? null : current)
    }, welcomeNotice.displayDurationMs)
    return () => window.clearTimeout(timeout)
  }, [welcomeNotice])

  useEffect(() => {
    const append = (candidate: Candidate) => {
      const message = completedOverlayMessage(candidate)
      if (!message) return
      setMessages((current) => upsertOverlayMessage(current, message))
    }
    let cancelled = false
    let connectedOnce = false
    let audioContext: AudioContext | null = null
    let analyser: AnalyserNode | null = null
    let audio: HTMLAudioElement | null = null
    let animationFrame = 0
    let currentSettings: TtsSettings | null = null

    const stopSpeech = () => {
      if (audio) {
        audio.pause()
        audio.removeAttribute('src')
        audio.load()
      }
      audio = null
      mouthLevelRef.current = 0
    }

    const sampleMouthLevel = () => {
      if (!analyser || !audio || audio.paused) {
        mouthLevelRef.current = 0
      } else {
        const samples = new Uint8Array(analyser.fftSize)
        analyser.getByteTimeDomainData(samples)
        let sumSquares = 0
        for (const sample of samples) {
          const centered = (sample - 128) / 128
          sumSquares += centered * centered
        }
        const rms = Math.sqrt(sumSquares / samples.length)
        mouthLevelRef.current = Math.min(1, rms * 5.5)
      }
      animationFrame = window.requestAnimationFrame(sampleMouthLevel)
    }

    const playSpeech = async (start: OverlayStreamStart) => {
      stopSpeech()
      if (!start.audioUrl || currentSettings?.muted) return
      audio = new Audio(start.audioUrl)
      audio.volume = Math.max(0, Math.min((currentSettings?.volume ?? start.volume) / 100, 1))
      try {
        audioContext ??= new AudioContext()
        if (audioContext.state === 'suspended') await audioContext.resume()
        analyser = audioContext.createAnalyser()
        analyser.fftSize = 256
        const source = audioContext.createMediaElementSource(audio)
        source.connect(analyser)
        analyser.connect(audioContext.destination)
        await audio.play()
      } catch {
        mouthLevelRef.current = 0
      }
    }

    request<TtsSettings>('/api/tts/settings')
      .then((settings) => { currentSettings = settings })
      .catch(() => undefined)
    animationFrame = window.requestAnimationFrame(sampleMouthLevel)
    const events = new EventSource('/api/overlay/events')
    const synchronizeRecent = (replaceStaleStreams: boolean) => {
      const revisionBeforeRequest = streamRevision.current
      request<Candidate[]>('/api/overlay/recent')
        .then((recent) => {
          if (cancelled) return
          const restored = recent
            .map(completedOverlayMessage)
            .filter((message): message is OverlayMessage => message !== null)
          setMessages((current) => {
            if (replaceStaleStreams && revisionBeforeRequest === streamRevision.current) {
              return retainRecentOverlayMessages(restored)
            }
            if (revisionBeforeRequest === streamRevision.current
                && !current.some((message) => message.streaming)) {
              return retainRecentOverlayMessages(restored)
            }
            const currentIds = new Set(current.map((message) => message.id))
            return retainRecentOverlayMessages([
              ...restored.filter((message) => !currentIds.has(message.id)),
              ...current,
            ])
          })
          setReady(true)
        })
        .catch(() => undefined)
    }
    events.addEventListener('open', () => {
      const reconnecting = connectedOnce
      connectedOnce = true
      setConnectionState('CONNECTED')
      if (reconnecting) {
        setReplyLifecycles([])
        setReplyOutcomes([])
      }
      synchronizeRecent(reconnecting)
    })
    events.addEventListener('error', () => setConnectionState('RECONNECTING'))
    events.addEventListener('overlay-reply-received', (event) => {
      const received = parseSseEvent<OverlayReplyReceived>(event)
      if (!received) return
      setReplyLifecycles((current) => upsertReplyLifecycle(current, {
        ...received,
        phase: 'received',
      }))
      setReady(true)
    })
    events.addEventListener('overlay-reply-start', (event) => {
      const pending = parseSseEvent<OverlayReplyStart>(event)
      if (!pending) return
      setReplyLifecycles((current) => upsertReplyLifecycle(current, {
        ...pending,
        phase: 'thinking',
      }))
    })
    events.addEventListener('overlay-reply-finish', (event) => {
      const messageId = parseSseEvent<string>(event) ?? event.data
      if (!messageId) return
      setReplyLifecycles((current) => current.filter((reply) => reply.messageId !== messageId))
    })
    events.addEventListener('overlay-reply-outcome', (event) => {
      const outcome = parseSseEvent<Candidate>(event)
      if (!outcome) return
      setReplyLifecycles((current) => current.filter((reply) => reply.messageId !== outcome.messageId))
      setReplyOutcomes((current) => [
        ...current.filter((candidate) => candidate.id !== outcome.id),
        outcome,
      ])
      setReady(true)
    })
    events.addEventListener('overlay-welcome', (event) => {
      const welcome = parseSseEvent<OverlayWelcome>(event)
      if (!welcome) return
      setWelcomeNotice(welcome)
      setReady(true)
    })
    events.addEventListener('overlay-stream-start', (event) => {
      const start = parseSseEvent<OverlayStreamStart>(event)
      if (!start) return
      void playSpeech(start)
      setReplyLifecycles((current) => current.filter((reply) => reply.messageId !== start.messageId))
      streamRevision.current += 1
      setMessages((current) => upsertOverlayMessage(current, {
        id: start.candidateId,
        senderName: start.senderName,
        sourceText: start.sourceText,
        candidateText: '',
        streaming: true,
      }))
      setReady(true)
    })
    events.addEventListener('overlay-stream', (event) => {
      const update = parseSseEvent<OverlayStreamUpdate>(event)
      if (!update) return
      streamRevision.current += 1
      setMessages((current) => current.map((message) => (
        message.id === update.candidateId && message.streaming
          ? { ...message, candidateText: update.text, streaming: !update.completed }
          : message
      )))
    })
    events.addEventListener('overlay', (event) => {
      const candidate = parseSseEvent<Candidate>(event)
      if (!candidate) return
      setReplyLifecycles((current) => current.filter((reply) => reply.messageId !== candidate.messageId))
      streamRevision.current += 1
      append(candidate)
      setReady(true)
    })
    events.addEventListener('overlay-clear', () => {
      stopSpeech()
      streamRevision.current += 1
      setMessages([])
      setReplyLifecycles([])
      setReplyOutcomes([])
    })
    events.addEventListener('overlay-tts-settings', (event) => {
      const settings = parseSseEvent<TtsSettings>(event)
      if (!settings) return
      currentSettings = settings
      if (settings.muted) stopSpeech()
      else if (audio) audio.volume = settings.volume / 100
    })
    const reconciliationInterval = window.setInterval(() => {
      synchronizeRecent(false)
    }, 3000)
    return () => {
      cancelled = true
      window.clearInterval(reconciliationInterval)
      events.close()
      stopSpeech()
      window.cancelAnimationFrame(animationFrame)
      void audioContext?.close()
    }
  }, [])

  return (
    <main
      className={`paper-room-overlay mode-${stageMode} ${messages.length > 0 ? 'active' : ''}`}
      data-host-state={stageMode}
      aria-live="polite"
    >
      <img
        className="room-background"
        src={paperRoomBackground}
        alt=""
        onError={(event) => {
          event.currentTarget.hidden = true
          event.currentTarget.nextElementSibling?.classList.add('is-visible')
        }}
      />
      <MorningRoom />
      <div className="room-grain" aria-hidden="true" />
      <div className="paper-room-layout">
        <section className="host-stage" aria-label="AI 主播状态">
          <PaperHost state={stageMode} mouthLevelRef={mouthLevelRef} />
          {showWelcome && welcomeNotice && (
            <aside className="welcome-ribbon" role="status" aria-label="欢迎新观众">
              <span aria-hidden="true">✦</span>
              <div>
                <small>新朋友到了</small>
                <p>{welcomeNotice.text}</p>
              </div>
            </aside>
          )}
          <div className={`host-status ${queuedReplies.length > 0 ? 'has-queue' : ''}`} role="status">
            <span className="status-pin" aria-hidden="true" />
            <p>{stageStatus}</p>
            <span className="status-stitch" aria-hidden="true" />
            {queuedReplies.length > 0 && (
              <div className="viewer-queue" aria-label="接下来等待回复的观众">
                <span>接下来</span>
                {queuedReplies.map((reply) => (
                  <strong key={reply.messageId}>
                    <b>@{reply.senderName}</b>
                    <small>{reply.phase === 'thinking' ? '思考中' : '已收到'}</small>
                  </strong>
                ))}
              </div>
            )}
          </div>
        </section>

        <section ref={feedElement} className="letter-feed" aria-label="AI 直播回复">
          <div className="letter-history" aria-label="历史回复">
            {historyMessages.slice(-6).map((candidate, index) => (
              <article
                className={`paper-strip strip-tone-${index % 3}`}
                key={candidate.id}
                ref={(element) => {
                  if (element) messageElements.current.set(candidate.id, element)
                  else messageElements.current.delete(candidate.id)
                }}
              >
                <strong>@{candidate.senderName}</strong>
                {(() => {
                  const reply = splitToolAttribution(candidate.candidateText)
                  return <><span>{reply.text}</span>{reply.tool && <small className="tool-attribution">{reply.tool}</small>}</>
                })()}
              </article>
            ))}
          </div>

          <article
            className={`current-letter ${showPendingReply || showReceivedReply || viewerAlreadyWaiting ? 'is-pending' : ''} ${showOutcome && !viewerAlreadyWaiting ? 'is-error' : ''} ${overlayLengthClass(currentMessageText)}`}
            ref={(element) => {
              if (!latestMessage) return
              if (element) messageElements.current.set(latestMessage.id, element)
              else messageElements.current.delete(latestMessage.id)
            }}
          >
            <header className="letter-heading">
              <span className="letter-kicker">回复</span>
              <strong>@{activeSenderName ?? latestMessage?.senderName ?? '正在看直播的你'}</strong>
            </header>
            {activeSourceText && <p className="letter-source">你刚才说：“{activeSourceText}”</p>}
            <div className="letter-rule" aria-hidden="true" />
            {(() => {
              const reply = splitToolAttribution(currentMessageText)
              return <><p className={`letter-message ${latestMessage?.streaming ? 'streaming' : ''}`}>{reply.text}</p>{reply.tool && !latestMessage?.streaming && <small className="tool-attribution overlay-tool-attribution">{reply.tool}</small>}</>
            })()}
          </article>
        </section>
      </div>
    </main>
  )
}

export function App() {
  if (window.location.pathname === '/overlay') {
    return <Overlay />
  }
  return <ControlRoom />
}

function ControlRoom() {
  const queryClient = useQueryClient()
  const [roomId, setRoomId] = useState('1000')
  const [senderId, setSenderId] = useState('viewer-1')
  const [senderName, setSenderName] = useState('测试观众')
  const [messageText, setMessageText] = useState('主播晚上好，今天会聊什么？')
  const overlayUrl = `${window.location.origin}/overlay`
  const candidatesQuery = useQuery({
    queryKey: ['candidates'],
    queryFn: () => request<Candidate[]>('/api/replies'),
    refetchInterval: 60_000,
  })
  const runtimeStatusQuery = useQuery({
    queryKey: ['runtime-status'],
    queryFn: () => request<RuntimeStatus>('/api/runtime'),
    refetchInterval: 5_000,
  })
  const healthQuery = useQuery({
    queryKey: ['health'],
    queryFn: () => request<HealthStatus>('/actuator/health'),
    refetchInterval: 5_000,
    retry: false,
  })
  const bilibiliStatusQuery = useQuery({
    queryKey: ['bilibili-status'],
    queryFn: () => request<BilibiliConnectionStatus>('/api/bilibili/status'),
    refetchInterval: 5_000,
  })

  useEffect(() => {
    const events = new EventSource('/api/events')
    events.addEventListener('candidate', (event) => {
      try {
        const next = JSON.parse(event.data) as Candidate
        queryClient.setQueryData<Candidate[]>(['candidates'], (current = []) => [next, ...current.filter((item) => item.id !== next.id)])
        queryClient.invalidateQueries({ queryKey: ['runtime-status'] })
      } catch {
        queryClient.invalidateQueries({ queryKey: ['candidates'] })
      }
    })
    return () => events.close()
  }, [queryClient])

  const publishMock = useMutation({
    mutationFn: () => request<Candidate>('/api/mock/messages', {
      method: 'POST',
      body: JSON.stringify({ roomId, senderId, senderName, messageText }),
    }),
    onSuccess: () => setMessageText(''),
  })

  const pauseMutation = useMutation({
    mutationFn: (paused: boolean) => request<RuntimeControl>(`/api/runtime/${paused ? 'pause' : 'resume'}`, { method: 'POST' }),
    onSuccess: (control) => queryClient.setQueryData<RuntimeStatus>(['runtime-status'], (current) => (
      current ? { ...current, paused: control.paused, outputMode: control.outputMode } : current
    )),
  })

  const outputModeMutation = useMutation({
    mutationFn: (outputMode: LiveOutputMode) => request<RuntimeControl>('/api/runtime/output-mode', {
      method: 'POST',
      body: JSON.stringify({ outputMode }),
    }),
    onSuccess: (control) => queryClient.setQueryData<RuntimeStatus>(['runtime-status'], (current) => (
      current ? { ...current, outputMode: control.outputMode } : current
    )),
  })

  const bilibiliConnectionMutation = useMutation({
    mutationFn: (connected: boolean) => request<BilibiliConnectionStatus>(
      `/api/bilibili/${connected ? 'disconnect' : 'connect'}`,
      { method: 'POST' },
    ),
    onSuccess: (status) => queryClient.setQueryData(['bilibili-status'], status),
  })

  const openOverlay = () => window.open(overlayUrl, '_blank', 'noopener,noreferrer')

  const published = useMemo(
    () => (candidatesQuery.data ?? []).filter((candidate) => candidate.status === 'AUTO_PUBLISHED'),
    [candidatesQuery.data],
  )

  const runtime = runtimeStatusQuery.data
  const bilibiliStatus = bilibiliStatusQuery.data
  const bilibiliConnected = bilibiliStatus?.state === 'CONNECTED'
  const bilibiliActive = bilibiliStatus
    ? ['STARTING', 'AUTHENTICATING', 'CONNECTED', 'RECONNECTING'].includes(bilibiliStatus.state)
    : false
  const bilibiliControlDisabled = !bilibiliStatus
    || ['DISABLED', 'NOT_CONFIGURED', 'STOPPING'].includes(bilibiliStatus.state)
    || bilibiliConnectionMutation.isPending
  const infrastructureReady = healthQuery.data?.status === 'UP'
  const liveReady = infrastructureReady && runtime?.modelConfigured && !runtime.paused

  return (
    <main className="control-room">
      <header className="masthead">
        <div>
          <p className="eyebrow">BILIBILI · AI LIVE CONTROL</p>
          <h1>夜航导播台</h1>
        </div>
        <div className="runtime-actions">
          <div className={`system-state ${liveReady ? '' : 'warning'}`}><span /> {runtime?.paused ? '自动回复已暂停' : liveReady ? '本地闭环已就绪' : '系统尚未就绪'}</div>
          <button
            className="mode-control"
            type="button"
            disabled={!runtime || outputModeMutation.isPending}
            onClick={() => outputModeMutation.mutate(
              runtime?.outputMode === 'OVERLAY_AND_DANMAKU' ? 'OVERLAY_ONLY' : 'OVERLAY_AND_DANMAKU',
            )}
          >
            {outputModeMutation.isPending
              ? '切换中…'
              : runtime?.outputMode === 'OVERLAY_AND_DANMAKU'
                ? '切换为仅 Overlay'
                : '启用模拟双通道'}
          </button>
          <button
            className={bilibiliActive ? 'bilibili-disconnect' : 'bilibili-connect'}
            type="button"
            disabled={bilibiliControlDisabled}
            onClick={() => bilibiliConnectionMutation.mutate(bilibiliActive)}
          >
            {bilibiliConnectionMutation.isPending
              ? '处理中…'
              : bilibiliActive
                ? '断开 B 站弹幕'
                : '连接 B 站弹幕'}
          </button>
          <button
            className={runtime?.paused ? 'resume-control' : 'pause-control'}
            type="button"
            disabled={!runtime || pauseMutation.isPending}
            onClick={() => pauseMutation.mutate(!runtime?.paused)}
          >
            {pauseMutation.isPending ? '切换中…' : runtime?.paused ? '恢复自动回复' : '紧急暂停'}
          </button>
        </div>
      </header>

      <section className="runtime-strip" aria-label="运行状态">
        <div><span>基础设施</span><strong>{infrastructureReady ? '正常' : '异常'}</strong></div>
        <div><span>模型</span><strong>{runtime?.modelConfigured ? runtime.modelName ?? '已配置' : '未配置'}</strong></div>
        <div><span>实际协议</span><strong>{runtime ? apiModeLabel[runtime.activeApiMode] : '读取中'}</strong></div>
        <div><span>最近模型耗时</span><strong>{runtime?.lastModelDurationMs == null ? '暂无' : `${(runtime.lastModelDurationMs / 1000).toFixed(2)} 秒`}</strong></div>
        <div><span>模型保护</span><strong>{runtime ? circuitLabel[runtime.circuitState] : '读取中'}</strong></div>
        <div><span>Overlay</span><strong>{runtime?.overlaySubscribers ? `${runtime.overlaySubscribers} 个连接` : '未连接'}</strong></div>
        <div><span>B 站长链</span><strong>{bilibiliStatus ? bilibiliStateLabel[bilibiliStatus.state] : '读取中'}</strong></div>
        <div title="根据官方进入直播间事件和弹幕活动估算，不代表精确在线人数"><span>近 5 分钟活跃</span><strong>{runtime ? `约 ${runtime.recentlyActiveViewers} 人` : '读取中'}</strong></div>
        <div title="本次开放平台互动场次中被官方事件记录到的唯一观众数"><span>本场累计观众</span><strong>{runtime ? `${runtime.observedSessionViewers} 人` : '读取中'}</strong></div>
        <div><span>输出模式</span><strong>{runtime?.outputMode === 'OVERLAY_AND_DANMAKU' ? 'Overlay + 模拟弹幕' : '仅 Overlay'}</strong></div>
        <div><span>回复队列</span><strong>{runtime ? `${runtime.pendingReplies} 等待 / 每房间 ${runtime.maxConcurrentPerRoom} 并发` : '读取中'}</strong></div>
        <div><span>全局上限</span><strong>{runtime ? `${runtime.maxModelCallsPerWindow} 次/分钟` : '读取中'}</strong></div>
      </section>
      {runtime?.lastModelError && (
        <p className="error model-error">
          最近模型异常：{runtime.lastModelError}（连续 {runtime.consecutiveModelFailures} 次）
        </p>
      )}

      <section className="hero-grid">
        <article className="signal-card">
          <p className="eyebrow">INPUT SIGNAL</p>
          <h2>模拟直播间事件</h2>
          <label>直播间 ID<input value={roomId} onChange={(event) => setRoomId(event.target.value)} maxLength={64} /></label>
          <label>模拟用户 ID<input value={senderId} onChange={(event) => setSenderId(event.target.value)} maxLength={128} /></label>
          <label>模拟用户昵称<input value={senderName} onChange={(event) => setSenderName(event.target.value)} maxLength={128} /></label>
          <label>模拟弹幕<textarea value={messageText} onChange={(event) => setMessageText(event.target.value)} maxLength={280} /></label>
          <button className="primary" type="button" onClick={() => publishMock.mutate()} disabled={!roomId || !senderId || !messageText || publishMock.isPending}>
            {publishMock.isPending ? '处理中…' : '注入模拟弹幕'}
          </button>
          {publishMock.isError && <p className="error">{publishMock.error.message}</p>}
        </article>

        <article className="queue-card">
          <div className="card-heading"><div><p className="eyebrow">AUTO PUBLISH</p><h2>自动上屏记录</h2></div><strong>{published.length.toString().padStart(2, '0')}</strong></div>
          {published.length === 0 ? <p className="empty-state">等待一条合格弹幕自动上屏。</p> : published.slice(0, 6).map((candidate) => (
            <div className="candidate" key={candidate.id}>
              <p className="source">@{candidate.senderName}：“{candidate.sourceText}”</p>
              {candidate.candidateText && (() => {
                const reply = splitToolAttribution(candidate.candidateText)
                return <><p className="answer">{reply.text}</p>{reply.tool && <small className="tool-attribution">{reply.tool}</small>}</>
              })()}
              <div className="actions">
                <span className="badge approved">已自动发送至 Overlay</span>
                {candidate.danmakuStatus === 'SENT' && <span className="badge danmaku-sent">弹幕已发送</span>}
                {candidate.danmakuStatus === 'SKIPPED' && <span className="badge danmaku-skipped">弹幕已跳过</span>}
                {candidate.danmakuStatus === 'FAILED' && <span className="badge danmaku-failed">弹幕发送失败</span>}
              </div>
              {candidate.danmakuText && <p className="danmaku-copy">弹幕短句：{candidate.danmakuText}</p>}
              {candidate.danmakuDecisionReason && <p className="danmaku-reason">{candidate.danmakuDecisionReason}</p>}
            </div>
          ))}
        </article>
      </section>
      {outputModeMutation.isError && <p className="error">输出模式切换失败，请检查后端状态。</p>}
      {bilibiliConnectionMutation.isError && <p className="error">B 站连接操作失败，请检查身份码、项目 ID、Access Key 和后端日志。</p>}
      {bilibiliStatus?.lastError && <p className="error">B 站状态：{bilibiliStatus.lastError}</p>}
      {bilibiliConnected && bilibiliStatus.roomId && <p className="connection-note">正在接收直播间 {bilibiliStatus.roomId} 的官方弹幕事件。</p>}

      <section className="lower-grid">
        <article className="overlay-card">
          <p className="eyebrow">OBS BROWSER SOURCE</p>
          <h2>Overlay 接入</h2>
          <p>本地 Overlay 无需 token，可直接在浏览器或 OBS 浏览器源中打开。</p>
          <button className="secondary" type="button" onClick={openOverlay}>
            在新页面打开 Overlay
          </button>
          <textarea className="overlay-url" value={overlayUrl} readOnly aria-label="OBS Overlay URL" />
        </article>
        <article className="timeline-card">
          <p className="eyebrow">AUDIT TRAIL</p>
          <h2>处理记录</h2>
          <div className="timeline">
            {(candidatesQuery.data ?? []).slice(0, 6).map((candidate) => <div key={candidate.id}><time>{new Date(candidate.createdAt).toLocaleTimeString('zh-CN')}</time><span className={`badge ${candidate.status.toLowerCase()}`}>{statusLabel[candidate.status]}</span><p>{candidate.candidateText ?? candidate.decisionReason}</p></div>)}
          </div>
        </article>
      </section>
    </main>
  )
}
