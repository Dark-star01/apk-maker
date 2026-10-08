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
