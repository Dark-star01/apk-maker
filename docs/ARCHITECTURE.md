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

## Known limitations

- If Android kills the app while the file picker is open, the pending pick is lost; just pick again.
- Project JSON lives in the WebView's localStorage (Phase 1 decision). Clearing app data wipes it and the media files together.
  The `store` interface lets us move it to a native file later without touching the UI.
- The web code avoids newer JS syntax (`?.`, `??`) and has CSS fallbacks for `dvh`/`inset`, so it should also run on older WebViews.
