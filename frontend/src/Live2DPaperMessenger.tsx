import { useEffect, useRef, useState, type RefObject } from 'react'
import type { Application } from 'pixi.js'
import type { Live2DModel } from 'untitled-pixi-live2d-engine/cubism'

export type PaperMessengerState = 'idle' | 'received' | 'welcoming' | 'thinking' | 'speaking' | 'error' | 'reconnecting'

type StateAction = {
  expression: string
  motionGroup: string
  loop: boolean
}

type StateMap = {
  states: Record<PaperMessengerState, StateAction>
}

type Runtime = {
  app: Application
  model: Live2DModel
  stateMap: StateMap
  reducedMotion: boolean
  currentState: PaperMessengerState
  elapsedSeconds: number
  stateStartedAtSeconds: number
  mouthLevelRef: RefObject<number>
  drawableController?: DrawableController
  layout: {
    x: number
    y: number
    scale: number
  }
}

type Props = {
  state: PaperMessengerState
  mouthLevelRef: RefObject<number>
  onReadyChange: (ready: boolean) => void
}

type PixiTextureCompatibilitySource = {
  _gpuData?: Record<number, unknown>
}

type PixiTextureCompatibilityRenderer = {
  uid: number
  texture: {
    initSource: (source: unknown) => unknown
  }
}

type MutableCubismDrawables = {
  ids: string[]
  vertexPositions: Float32Array[]
  opacities: Float32Array
}

type MutableCubismInternalModel = {
  update: (deltaTime: number, elapsedTime: number) => void
  coreModel?: {
    _model?: {
      drawables?: MutableCubismDrawables
    }
  }
}

type DrawableController = {
  restore: () => void
}

type DrawableTransform = {
  scaleX?: number
  scaleY?: number
  translateX?: number
  translateY?: number
  rotation?: number
}

const modelUrl = '/live2d/paper-messenger/paper-messenger.model3.json'
const stateMapUrl = '/live2d/paper-messenger/model-state-map.json'
const cubismCoreUrl = '/live2d/live2dcubismcore.min.js'

let pluginRegistered = false

const createDrawableController = (
  model: Live2DModel,
  runtimeProvider: () => Runtime | null,
): DrawableController | undefined => {
  const internalModel = model.internalModel as unknown as MutableCubismInternalModel
  const drawables = internalModel.coreModel?._model?.drawables
  if (!drawables?.ids || !drawables.vertexPositions || !drawables.opacities) return undefined

  const drawableIndices = new Map(drawables.ids.map((id, index) => [id, index]))
  const baseVertices = drawables.vertexPositions.map((vertices) => new Float32Array(vertices))
  const baseOpacities = new Float32Array(drawables.opacities)
  const originalUpdate = internalModel.update.bind(internalModel)

  const setOpacity = (id: string, opacity: number) => {
    const index = drawableIndices.get(id)
    if (index === undefined) return
    drawables.opacities[index] = Math.max(0, Math.min(opacity, 1))
  }

  const transform = (id: string, options: DrawableTransform) => {
    const index = drawableIndices.get(id)
    if (index === undefined) return
    const base = baseVertices[index]
    const target = drawables.vertexPositions[index]
    if (!base || !target || base.length !== target.length) return
    let centerX = 0
    let centerY = 0
    for (let vertexIndex = 0; vertexIndex < base.length; vertexIndex += 2) {
      centerX += base[vertexIndex]
      centerY += base[vertexIndex + 1]
    }
    const vertexCount = Math.max(base.length / 2, 1)
    centerX /= vertexCount
    centerY /= vertexCount
    const scaleX = options.scaleX ?? 1
    const scaleY = options.scaleY ?? 1
    const translateX = options.translateX ?? 0
    const translateY = options.translateY ?? 0
    const rotation = options.rotation ?? 0
    const cosine = Math.cos(rotation)
    const sine = Math.sin(rotation)
    for (let vertexIndex = 0; vertexIndex < base.length; vertexIndex += 2) {
      const localX = (base[vertexIndex] - centerX) * scaleX
      const localY = (base[vertexIndex + 1] - centerY) * scaleY
      target[vertexIndex] = centerX + localX * cosine - localY * sine + translateX
      target[vertexIndex + 1] = centerY + localX * sine + localY * cosine + translateY
    }
  }

  internalModel.update = (deltaTime, elapsedTime) => {
    originalUpdate(deltaTime, elapsedTime)
    const runtime = runtimeProvider()
    if (!runtime) return
    baseVertices.forEach((vertices, index) => drawables.vertexPositions[index]?.set(vertices))
    drawables.opacities.set(baseOpacities)
    if (runtime.reducedMotion) return

    const { currentState, elapsedSeconds, stateStartedAtSeconds } = runtime
    const stateElapsed = Math.max(elapsedSeconds - stateStartedAtSeconds, 0)
    const blinkCycle = elapsedSeconds % 5.2
    const idleBlink = blinkCycle > 4.86
      ? Math.max(0.08, Math.abs(blinkCycle - 5.03) / 0.17)
      : 1
    let leftEyeScaleY = currentState === 'idle' ? idleBlink : 1
    let rightEyeScaleY = leftEyeScaleY
    let eyeTranslateX = 0
    let eyeTranslateY = 0
    let mouthOpen = 0
    let mouthScaleY = 1
    let blushOpacity = 0.72

    if (currentState === 'received') {
      leftEyeScaleY = 1.12
      rightEyeScaleY = 1.12
      mouthOpen = 1
      mouthScaleY = 1.18
      blushOpacity = 1
    } else if (currentState === 'welcoming') {
      const welcomePulse = 0.5 + Math.sin(stateElapsed * 7.5) * 0.5
      leftEyeScaleY = 1.08
      rightEyeScaleY = 1.08
      mouthOpen = 0.22 + welcomePulse * 0.5
      mouthScaleY = 0.9 + welcomePulse * 0.35
      blushOpacity = 1
    } else if (currentState === 'thinking') {
      leftEyeScaleY = 0.86
      rightEyeScaleY = 0.86
      eyeTranslateX = 0.009
      eyeTranslateY = 0.008
      blushOpacity = 0.58
    } else if (currentState === 'speaking') {
      const measuredLevel = Math.max(0, Math.min(runtime.mouthLevelRef.current, 1))
      const fallbackPulse = 0.5 + Math.sin(stateElapsed * 11) * 0.5
      const speechLevel = measuredLevel > 0.015 ? measuredLevel : fallbackPulse * 0.22
      mouthOpen = 0.08 + speechLevel * 0.92
      mouthScaleY = 0.72 + speechLevel * 0.68
      blushOpacity = 0.9
    } else if (currentState === 'error') {
      leftEyeScaleY = 0.48
      rightEyeScaleY = 0.9
      eyeTranslateY = -0.003
      blushOpacity = 0.35
    } else if (currentState === 'reconnecting') {
      leftEyeScaleY = 0.28
      rightEyeScaleY = 0.28
      eyeTranslateY = -0.006
      blushOpacity = 0.18
    }

    transform('eye_l', {
      scaleY: leftEyeScaleY,
      translateX: eyeTranslateX,
      translateY: eyeTranslateY,
      rotation: currentState === 'error' ? -0.08 : 0,
    })
    transform('eye_r', {
      scaleY: rightEyeScaleY,
      translateX: eyeTranslateX,
      translateY: eyeTranslateY,
      rotation: currentState === 'error' ? 0.05 : 0,
    })
    transform('mouth_open', { scaleY: mouthScaleY })
    setOpacity('mouth_open', mouthOpen)
    setOpacity('mouth_closed', 1 - mouthOpen)
    setOpacity('blush_l', blushOpacity)
    setOpacity('blush_r', blushOpacity)
  }

  return {
    restore: () => {
      internalModel.update = originalUpdate
    },
  }
}

const loadCubismCore = async () => {
  if ('Live2DCubismCore' in window) return
  const response = await fetch(cubismCoreUrl)
  const contentType = response.headers.get('content-type') ?? ''
  if (!response.ok || contentType.includes('text/html')) {
    throw new Error('Cubism Core runtime is unavailable')
  }
  await new Promise<void>((resolve, reject) => {
    const script = document.createElement('script')
    script.src = cubismCoreUrl
    script.dataset.live2dCore = 'paper-messenger'
    script.onload = () => resolve()
    script.onerror = () => reject(new Error('Cubism Core runtime failed to load'))
    document.head.appendChild(script)
  })
}

export function Live2DPaperMessenger({ state, mouthLevelRef, onReadyChange }: Props) {
  const containerRef = useRef<HTMLDivElement>(null)
  const runtimeRef = useRef<Runtime | null>(null)
  const [ready, setReady] = useState(false)

  useEffect(() => {
    let cancelled = false
    let resizeObserver: ResizeObserver | null = null
    let motionPreference: MediaQueryList | null = null
    let motionPreferenceHandler: ((event: MediaQueryListEvent) => void) | null = null

    const initialize = async () => {
      const container = containerRef.current
      if (!container) return

      const [modelResponse, stateMapResponse] = await Promise.all([
        fetch(modelUrl),
        fetch(stateMapUrl),
      ])
      const modelContentType = modelResponse.headers.get('content-type') ?? ''
      if (!modelResponse.ok || modelContentType.includes('text/html')) return
      const modelSettings = await modelResponse.json() as Record<string, unknown>
      if (!('Version' in modelSettings) || !stateMapResponse.ok) return
      const stateMap = await stateMapResponse.json() as StateMap

      await loadCubismCore()
      const [{ Application, extensions }, live2d] = await Promise.all([
        import('pixi.js'),
        import('untitled-pixi-live2d-engine/cubism'),
      ])
      if (!pluginRegistered) {
        extensions.add(live2d.Live2DPlugin)
        pluginRegistered = true
      }

      const app = new Application()
      await app.init({
        width: Math.max(container.clientWidth, 1),
        height: Math.max(container.clientHeight, 1),
        backgroundAlpha: 0,
        antialias: true,
        autoStart: false,
        autoDensity: true,
        resolution: Math.min(window.devicePixelRatio, 2),
        preference: 'webgl',
      })
      if (cancelled) {
        app.destroy(true)
        return
      }
      app.canvas.className = 'live2d-host-canvas'
      container.appendChild(app.canvas)

      live2d.configureCubismSDK({ memorySizeMB: 32 })
      const model = await live2d.Live2DModel.from(modelUrl, {
        autoFocus: false,
        autoHitTest: false,
        eyeBlink: true,
        ticker: app.ticker,
        textureOptions: {
          lod: false,
          preferCreateImageBitmap: false,
        },
      })
      if (cancelled) {
        model.destroy({ children: true })
        app.destroy(true)
        return
      }
      model.anchor.set(0.5)
      app.stage.addChild(model)

      const naturalWidth = Math.max(model.width, 1)
      const naturalHeight = Math.max(model.height, 1)
      const layout = { x: 0, y: 0, scale: 1 }
      const fitModel = () => {
        app.renderer.resize(
          Math.max(container.clientWidth, 1),
          Math.max(container.clientHeight, 1),
        )
        const width = Math.max(app.screen.width, 1)
        const height = Math.max(app.screen.height, 1)
        layout.scale = Math.min(width / naturalWidth, height / naturalHeight) * 0.98
        layout.x = width / 2
        layout.y = height / 2
        model.scale.set(layout.scale)
        model.position.set(layout.x, layout.y)
      }
      fitModel()

      motionPreference = window.matchMedia('(prefers-reduced-motion: reduce)')
      const runtime: Runtime = {
        app,
        model,
        stateMap,
        reducedMotion: motionPreference.matches,
        currentState: 'idle',
        elapsedSeconds: 0,
        stateStartedAtSeconds: 0,
        mouthLevelRef,
        layout,
      }
      runtimeRef.current = runtime
      runtime.drawableController = createDrawableController(model, () => runtimeRef.current)
      motionPreferenceHandler = (event) => {
        runtime.reducedMotion = event.matches
      }
      motionPreference.addEventListener('change', motionPreferenceHandler)

      let elapsedSeconds = 0
      app.ticker.add((ticker) => {
        elapsedSeconds += ticker.deltaMS / 1000
        const activeRuntime = runtimeRef.current
        if (!activeRuntime || activeRuntime.model !== model) return

        const { currentState, reducedMotion } = activeRuntime
        activeRuntime.elapsedSeconds = elapsedSeconds
        const stateElapsed = Math.max(elapsedSeconds - activeRuntime.stateStartedAtSeconds, 0)
        let offsetX = 0
        let offsetY = 0
        let rotation = 0
        let scaleMultiplier = 1

        if (!reducedMotion) {
          const slowWave = Math.sin(elapsedSeconds * 1.35)
          offsetY = slowWave * 2
          rotation = Math.sin(elapsedSeconds * 0.72) * 0.004

          if (currentState === 'received') {
            const arrivalProgress = Math.min(stateElapsed / 0.72, 1)
            const arrivalPulse = Math.sin(arrivalProgress * Math.PI)
            offsetY -= 5 + arrivalPulse * 18
            scaleMultiplier = 1.015 + arrivalPulse * 0.055
          } else if (currentState === 'welcoming') {
            offsetX = Math.sin(stateElapsed * 4.2) * 5
            offsetY -= 7 + Math.abs(Math.sin(stateElapsed * 3.4)) * 7
            rotation = Math.sin(stateElapsed * 3.1) * 0.018
            scaleMultiplier = 1.025 + Math.sin(stateElapsed * 3.4) * 0.012
          } else if (currentState === 'thinking') {
            offsetX = Math.sin(elapsedSeconds * 1.45) * 5
            offsetY = Math.sin(elapsedSeconds * 1.05) * 2
            rotation = -0.026 + Math.sin(elapsedSeconds * 1.25) * 0.008
          } else if (currentState === 'speaking') {
            offsetY += Math.sin(elapsedSeconds * 5.2) * 3.5
            scaleMultiplier = 1 + Math.sin(elapsedSeconds * 5.2) * 0.01
          } else if (currentState === 'error') {
            const shake = Math.sin(stateElapsed * 18) * Math.exp(-stateElapsed * 1.8)
            offsetX = shake * 9
            rotation = 0.026 + shake * 0.022
          } else if (currentState === 'reconnecting') {
            offsetX = Math.sin(elapsedSeconds * 0.62) * 4
            offsetY = Math.sin(elapsedSeconds * 0.7) * 2 + 7
            rotation = Math.sin(elapsedSeconds * 0.55) * 0.015
          }
        }

        model.position.set(layout.x + offsetX, layout.y + offsetY)
        model.rotation = rotation
        model.scale.set(layout.scale * scaleMultiplier)
      })

      try {
        const renderer = app.renderer as unknown as PixiTextureCompatibilityRenderer
        model.textures.forEach((texture) => {
          renderer.texture.initSource(texture.source)
          const source = texture.source as PixiTextureCompatibilitySource
          source._gpuData ??= {}
          source._gpuData[renderer.uid] = true
        })
        app.render()
      } catch (error) {
        runtimeRef.current = null
        runtime.drawableController?.restore()
        model.destroy({ children: true })
        app.destroy(true)
        throw error
      }
      app.start()
      resizeObserver = new ResizeObserver(fitModel)
      resizeObserver.observe(container)
      setReady(true)
      onReadyChange(true)
    }

    initialize().catch((error) => {
      console.warn('[PaperMessenger] Live2D initialization failed; keeping PNG fallback.', error)
      resizeObserver?.disconnect()
      resizeObserver = null
      runtimeRef.current = null
      setReady(false)
      onReadyChange(false)
    })

    return () => {
      cancelled = true
      resizeObserver?.disconnect()
      if (motionPreference && motionPreferenceHandler) {
        motionPreference.removeEventListener('change', motionPreferenceHandler)
      }
      motionPreference = null
      motionPreferenceHandler = null
      const runtime = runtimeRef.current
      runtimeRef.current = null
      if (runtime) {
        runtime.drawableController?.restore()
        runtime.model.destroy({ children: true })
        runtime.app.destroy(true)
      }
      setReady(false)
      onReadyChange(false)
    }
  }, [mouthLevelRef, onReadyChange])

  useEffect(() => {
    const runtime = runtimeRef.current
    if (!ready || !runtime) return
    const action = runtime.stateMap.states[state] ?? runtime.stateMap.states.idle
    runtime.currentState = state
    runtime.stateStartedAtSeconds = runtime.elapsedSeconds
    runtime.model.stopMotions()
    void runtime.model.expression(action.expression).catch(() => false)
    if (!runtime.reducedMotion) {
      void runtime.model
        .motion(action.motionGroup, 0, state === 'idle' ? 1 : 3, { loop: action.loop })
        .catch(() => false)
    }
  }, [ready, state])

  return <div ref={containerRef} className={`live2d-host ${ready ? 'is-ready' : ''}`} aria-hidden="true" />
}
