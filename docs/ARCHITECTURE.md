# Music Video Maker — Architecture

Personal Android app, built with APKMaker 2.0 (Capacitor 7.6.9 + Kotlin 1.9.24, Java 21).
Priorities: stability > performance > features. Target: ~4 GB RAM device.

## Layers

```
Web UI (webapp_src → webapp/app.zip)      controls + state only, no rendering
   │  js/core/bridge.js   (only place that knows about native)
   ▼
MvmBridgePlugin (Kotlin, Capacitor)       thin API: plain methods, JSON in/out
   ▼
Engines (Kotlin packages)                 one responsibility each
```

## Single renderer rule

```
Project ──► TimelineEngine ──► RenderState(t) ──► FrameRenderer (Kotlin, android.graphics.Canvas)
                                                      ├─ Preview: native TextureView placed over #preview
                                                      └─ Export : MediaCodec input Surface
```

Preview and Export use the SAME `FrameRenderer` and the SAME `RenderState`; the web layer never
draws the video. Audio analysis (volume/bass/mid/treble) is computed once into a compact track
(30 values/s) and read by both — so Preview == Export.

## Kotlin packages (created when the phase needs them, not before)

| Package | Phase | Role |
|---|---|---|
| `bridge` | 1 | `MvmBridgePlugin` — the only Web ↔ Kotlin entry point |
| `media` | 2 ✅ | `MediaManager` (import = copy → validate → swap), `ProjectFiles`, `AudioInspector`, `ImageInspector` |
| `audio` | 3 | `AudioEngine` playback; `AudioAnalyzer` (DSP, no AI) |
| `timeline` | 3 | `TimelineEngine`, `RenderState` — the single time source |
| `render` | 4–6 | `FrameRenderer`, wave styles, background motion |
| `export` | 7 | `ExportRenderer` (MediaCodec H.264 + AAC → MP4) |
| later | — | `lyrics`, `effects`, `analysis` (image), `ai` (`AIDirector` interface) |

## Project JSON

`webapp_src/js/core/schema.js` — versioned (`schemaVersion`), additive, unknown keys preserved,
`extensions` object reserved for future engines. Media is stored as references (uri/name), never bytes.

## APKMaker facts this project relies on

- Native files live in `native/android/app/src/main/java/__APP_ID_PATH__/…`; Kotlin uses `package __APP_ID__[.sub]`.
- `MainActivity.kt` must be in the appId root package; it replaces the default `MainActivity.java`.
- `__APP_ID_PATH__` is replaced in PATHS only; inside file text only `__APP_ID__` / `__APP_NAME__` are replaced.
- Permissions / dependencies only via `config.json` (`native.permissions`, `native.extraDependencies`); both empty now.
- Capacitor's template already declares `INTERNET` and a FileProvider (`${applicationId}.fileprovider`,
  with `cache-path`) — sharing the exported video needs no extra permission.
- `capacitorVersion` is pinned (7.6.9). "latest" resolves to Capacitor 8 which needs Node ≥ 22, but the workflow uses Node 20.
- The workflow builds `assembleDebug` only.
- Keep exactly one zip in `webapp/` (newest wins). Rebuild it with `./make-webapp-zip.sh`.

## Phase 2 decisions (media)

- Picker: `ACTION_OPEN_DOCUMENT` (system file picker, no storage permission). The chosen file is **streamed**
  (64 KB buffer) into `<filesDir>/projects/<id>/` and the content URI is never stored.
- Import is copy → validate → swap: a bad pick never destroys the previous file. Interrupted imports leave
  `tmp_*` files that are cleaned on the next import.
- Audio is validated with `MediaExtractor` / `MediaMetadataRetriever` (nothing decoded). Image: header read only,
  plus a 480 px JPEG thumbnail decoded with `inSampleSize` (EXIF orientation applied). Images over ~200 MP are refused.
- Errors cross the bridge as stable codes (`UNSUPPORTED_AUDIO`, `NO_SPACE`, …); wording lives in `js/ui/messages.js` (Arabic).
- The preview picture in Phase 2 is only the thumbnail as a static stand-in; the real renderer arrives in Phase 6.

## Phase 3 decisions (playback + timeline)

- Player: platform `MediaPlayer` (no Media3 → no new dependency). It streams the project file from disk; audio is never loaded into RAM or analysed in JS.
- `audio/AudioEngine.kt` is the timeline clock. All its calls run on the main thread; the plugin wraps every command (`loadAudio`, `play`, `pause`, `seek`, `stop`, `getPlaybackState`, `releaseAudio`) and always answers one snapshot `{state, positionMs, durationMs, error?}`.
- While playing, native pushes the same snapshot as the `playback` event 4x/s. JS extrapolates between pushes and redraws at most 20x/s (text + one `transform`, no DOM growth). A stale-clock watchdog polls `getPlaybackState` if pushes stop.
- `timeline/RenderState.kt` and `js/core/timeline.js renderStateAt()` are the shape the single Kotlin FrameRenderer will consume (Phase 6). Wave data plugs in at `wave.data` (Phase 4).
- Leaving the app pauses audio (no background service). Position is saved in the project (`playhead.positionMs`) and restored paused on reopen.
- Seeking uses `SEEK_CLOSEST` on API 26+; VBR MP3 seeks can be a little off on older Android.

## Phase 4 decisions (audio analysis -> wave data)

Pipeline: `audio file -> Kotlin AudioAnalyzer (streaming decode) -> PcmAnalyzer (DSP) -> analysis.bin (cache) -> getWaveData -> JS typed arrays -> canvas`.
JS never analyses audio.

- **Decoding:** `MediaExtractor` + `MediaCodec` used purely as an audio decoder (platform classes, no dependency, no permission). WAV/PCM skips the codec. One decoder buffer at a time; the song is never in memory.
- **Resolution:** 30 points/s. 3 min = 5 400 points; stored as 4 bytes per point (21 KB), 30 min = 216 KB.
- **Features per point:** `amplitude` (RMS of the 1/30 s window), `bass` (<250 Hz), `mid` (250 Hz-4 kHz), `treble` (>4 kHz). Bands = four 2nd-order Butterworth biquads (12 dB/oct, ~-24 dB leakage between bands). Mono mix of all channels.
- **Normalisation (0..1):** each series is divided by its OWN 99th percentile in this track, clamped to 1, stored as 0..255. So a value means "how loud compared with the loud parts of the same band in this song", not absolute loudness, and series are not comparable with each other. Anything below -70 dBFS stays 0 (silence is never amplified).
- **wave.data shape (JS, `core/wavedata.js`):** `{ sampleRate:30, count, durationMs, amplitude/bass/mid/treble: Uint8Array, point(i) -> {time, amplitude, bass, mid, treble}, indexAt(ms) }`. Compact typed arrays instead of an array of objects (a 30-min song would otherwise be 54 000 objects). `renderStateAt().wave.data` carries it to the future Renderer. The project JSON is unchanged (no wave data inside it).
- **Bridge:** `analyzeAudio({projectId,file,durationMs,force})` (+ `analysisProgress` events, <=1 per 5 % and 300 ms), `getWaveData`, `cancelAnalysis`.
- **Cache:** `projects/<id>/analysis.bin` = header (version, rate, count, duration, audio fingerprint) + bytes. Fingerprint = size + modified time + CRC of the first and last 64 KB of the audio (never the whole file). It is only used if the fingerprint matches the CURRENT audio file; importing/removing audio also deletes it; a cache written for a file that changed during analysis is never stored. JS identity of the audio also includes `rev` (import time) so a replaced file with equal size/duration is still detected.
- **Threads:** analysis runs on its own background-priority thread; playback and UI are untouched. A newer request, a replace or a remove cancels the running analysis.

## Phase 5 decisions (Effect Engine)

Pipeline: `wave.data -> EffectEngine (Kotlin, pure) -> EffectState -> RenderState.effects -> Renderer (Phase 6, Preview AND Export)`.
There is exactly ONE place that computes effect values: `effects/EffectEngine.kt`. JS never computes them; the FX tab only asks native for the state at the playhead (`getEffectState`) and prints it.

- **Files (`effects/`):** `EffectEngine` (public API `getEffectState(timeMs)`), `EffectSettings` (+ `Preset`, `MotionMode`), `EffectState` (+ `AudioLevels`), `AudioEnvelope` (normalize + smooth + interpolate), `BackgroundMotion`, `DeterministicNoise`, `Presets`. No Android classes, no I/O, no DOM: runs on a plain JVM (that is how it is unit-tested).
- **Deterministic and stateless:** the result depends only on (settings, wave data, time). No `random`, no clock, no previous frame. Any time in any order gives the same bits (tested forwards/backwards/fresh instance). Times are `Double` ms (export frames are at n*1000/30 ms) and are clamped to [0, duration].
- **Pipeline per series:** raw byte (0..255) -> *normalization* (track's 15th percentile = 0, 97th = 1, minimum span 0.15 so a flat track is not stretched, silence stays 0) -> *smoothing* (envelope follower, attack/release in seconds per preset, scaled 0.5x..2x by `smoothing`; run ONCE over the 30/s table when the engine is built, so it is stateless at query time) -> *linear interpolation* between the two surrounding points -> mapping.
- **Mapping (at intensity 1):** bass -> scale (+2/6/10/3.5 % for subtle/pulse/beat/cinematic); mid -> sway (translate) and small rotation; amplitude -> opacity (1 - depth .. 1) and `intensity`; treble -> glow; beat only: bass hits above ~0.55 add shake. `settings.intensity` (0..2) multiplies every amount. Preset `none` = neutral state (levels still reported).
- **Background motion (independent of preset, time only):** `static`, `slowZoom` (1 -> 1.08 over the song, or over 60 s if the length is unknown), `float`, `pulseZoom` (the only audio driven one: +5 % x bass), `cinematicDrift` (1 -> 1.10 plus slow pan/rotation with incommensurate periods).
- **Shake:** seeded value noise (integer hash, smoothstep between lattice points, 14 Hz), `noise(seed, t)`; the project's `effects.seed` makes it unique but identical in Preview and Export.
- **Cover guarantee:** `scale` is raised just enough that the image still covers the frame after translate+rotate (`cos+sin*max(aspect,1/aspect) + 2*max|t|`). A renderer never has to handle empty borders. Cost: 1 degree of rotation at 16:9 = ~3 % extra scale, which is why rotations are small.
- **EffectState:** `{timeMs, scale, rotationDeg, translateX, translateY, opacity, glow, intensity, shake, audio:{amplitude,bass,mid,treble}, audioReactive}`. Transform order for a renderer: translate (fraction of frame w/h), rotate, scale, about the frame centre. Phase 5 animates one layer (the background image); later layers add fields (additive).
- **No wave data** (no audio, not analysed yet, empty): `audioReactive=false`, audio-driven parts neutral, time-based motion still runs.
- **Project schema:** `effects: {preset, motion, intensity, smoothing, seed}` (additive, old projects get defaults; unknown future keys are kept). `backgroundMotion` from Phase 1 is a legacy placeholder the engine does not use.
- **Bridge:** `getEffectState({projectId, file, timeMs, durationMs, effects, aspect})`. Native keeps the wave table and the built engine between calls (reloaded when the audio is replaced/removed/re-analysed or settings change); it never analyses or reads the audio. The future native renderer calls `EffectEngine` directly, with no bridge in between. The FX tab asks at most ~8x/s, one call at a time, only while the tab is open.
- **Phase 6 contract:** `RenderState(timeMs, durationMs, playing, effects)`; build it with `engine.getEffectState(timeMs)` for both the live preview clock and every export frame.

## Phase 6 decisions (native Preview renderer)

Pipeline: `AudioEngine clock -> RenderLoop (render thread) -> Renderer.renderFrame(canvas, w, h, timeMs) -> EffectEngine.getEffectState(t) -> FrameGeometry -> Surface`.
ONE renderer. JS has no renderer and no clock: it tells native WHERE the preview is and WHICH project to draw, and prints diagnostics.

- **Surface:** a `TextureView` added to the WebView's parent, above the WebView, at the `#preview` rectangle (CSS px x `devicePixelRatio`). TextureView is an ordinary view (z-order/visibility/clipping behave over a WebView, which SurfaceView does not guarantee), and the Renderer only needs a `Surface`: in Export that will be the MediaCodec input surface, at the export size. The view is non-clickable (touches reach the page). While a dialog is open JS hides it (`visible=false` -> view GONE -> surface destroyed -> loop stops); closing the dialog recreates it.
- **Drawing:** `Surface.lockHardwareCanvas()` (API 26+, `lockCanvas` below) + `Canvas.drawBitmap(bitmap, matrix, paint)`. No GL/EGL code (nothing I could not run), GPU accelerated by the platform. Opacity = `Paint.alpha`. Glow = one of 17 pre-built `ColorMatrixColorFilter`s (brightness lift): an approximation of bloom, not a blur pass (a real glow needs an offscreen blur: later phase).
- **Threads:** `mvm-render` (RenderLoop) exists only while a surface is attached; it sleeps (no CPU) when paused, when not playing and no frame was requested. Main thread: view work + AudioEngine. `io` worker: picture decode. Detaching waits for the draw in progress, so a draw never touches a destroyed surface. 8 consecutive failed frames -> FAILED (no log spam), recovers on the next attach/resume.
- **Time:** `AudioEngine` publishes `{position, playing}` into `PlaybackClock` on every state change and every 250 ms tick; the render thread extrapolates with `SystemClock.elapsedRealtime()`. Differences < 80 ms are ignored (no jitter), seeks snap at once, time is clamped to the duration (the end keeps the last frame). No `Date.now()`, no rAF, no JS timer anywhere in the path.
- **FPS:** starts at 30; raised to 60 only after 90 frames averaging < 3.5 ms each; lowered again above 9 ms. Paused = no drawing.
- **Placement (documented, fixed order):** cover-fit the image (uniform scale, no stretching) then, about the FRAME centre: translate -> rotate -> scale (`p' = C + T + scale*R*s0*(p - imageCentre)`). A geometry safety net checks the four frame corners and zooms just enough if an edge would show (a no-op for EffectEngine states: measured <= x1.002 with rounded pixel sizes), plus 0.05 % margin against float rounding.
- **Picture:** `BackgroundLoader` decodes once per picture with `inSampleSize` so it is >= 1.3x what the frame needs (and <= 6 MP), applies EXIF orientation, retries at half size on OOM; the previous bitmap is recycled under the renderer lock only after the new one replaced it. A new decode happens only if the picture changed or the frame needs a >25 % larger one, never when effect settings change.
- **Bridge:** `attachPreview`, `setPreviewRect`, `detachPreview`, `setPreviewProject` (names + settings, never pixels), `getRendererState`, `rendererSelfTest` (renders t, t+1.2 s, t offscreen with the real Renderer and compares pixel CRCs: "same time -> same frame"). JS sends these only when the rectangle/visibility/project payload changes: zero calls per frame.
- **Lifecycle:** activity pause -> view GONE + loop paused; resume -> visible, loop resumes from the CURRENT audio time; surface destroyed/recreated -> loop detaches/re-attaches; plugin destroy -> loop released, thread joined, bitmap recycled, view removed.
- **Export contract (later phase):** Export builds a `Renderer`, calls `setEffects`/`setBackground` for the export size and, per frame n, `renderFrame(encoderSurfaceCanvas, W, H, n * 1000.0 / fps)`. Nothing in Renderer/FrameGeometry/FrameComposer depends on the TextureView.

## Native build path (learned the hard way)

- `native/` must sit in the repo ROOT next to `config.json`. APKMaker silently skips a missing/misplaced `native/`.
- Guards (keep them): `native.py diagcheck` (workflow, after `files` and after `cap sync`), `hooks/pre-build.sh`,
  `hooks/post-build.sh` (checks the dex inside the APK). `MvmDiag` plugin + `BootDiag` show registration facts in Settings.
- Phase 2 verified on a real APK: MvmDiag/MvmBridge registered, Test bridge = Bridge OK.
- `removeMedia({projectId, kind})` deletes a project's audio or background files (Remove buttons in the Media panel).

## Known limitations

- If Android kills the app while the file picker is open, the pending pick is lost; just pick again.
- Project JSON lives in the WebView's localStorage (Phase 1 decision). Clearing app data wipes it and the media files together.
  The `store` interface lets us move it to a native file later without touching the UI.
- The web code avoids newer JS syntax (`?.`, `??`) and has CSS fallbacks for `dvh`/`inset`, so it should also run on older WebViews.
