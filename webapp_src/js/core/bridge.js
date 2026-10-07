// NativeBridge — the ONLY place the web UI talks to Kotlin.
// The UI calls these plain async functions; it never sees Capacitor or plugin names.
// When running in a normal browser (no native side) calls degrade gracefully or reject
// with code NO_NATIVE.

const PLUGIN = 'MvmBridge';

function plugin() {
  const cap = globalThis.Capacitor;
  return cap && cap.Plugins ? cap.Plugins[PLUGIN] || null : null;
}

// Every failure reaches the UI as an Error with a stable `code` (see ui/messages.js).
function toError(e) {
  const err = new Error((e && e.message) || 'Bridge error');
  err.code = (e && e.code) || 'UNEXPECTED';
  return err;
}

function need() {
  const p = plugin();
  if (!p) {
    const err = new Error('Native engine not available');
    err.code = 'NO_NATIVE';
    throw err;
  }
  return p;
}

async function call(method, args) {
  const p = need();
  try {
    return await p[method](args);
  } catch (e) {
    throw toError(e);
  }
}

// Plain facts about the runtime, so a failure can be classified without guessing.
function diagnose() {
  const cap = globalThis.Capacitor;
  const lines = [];
  lines.push('Capacitor object: ' + (cap ? 'yes' : 'NO'));
  if (cap) {
    let platform = '?', native = '?';
    try { platform = cap.getPlatform(); } catch (e) { platform = 'error'; }
    try { native = String(cap.isNativePlatform()); } catch (e) { native = 'error'; }
    lines.push('Platform: ' + platform + ' · isNativePlatform: ' + native);
    lines.push('Plugins: ' + (cap.Plugins ? Object.keys(cap.Plugins).join(', ') || '(none)' : '(no Plugins)'));
    lines.push('PluginHeaders: ' + (cap.PluginHeaders ? cap.PluginHeaders.map((x) => x.name).join(', ') || '(none)' : '(none)'));
  }
  lines.push('androidBridge: ' + (globalThis.androidBridge ? 'yes' : 'no'));
  lines.push(PLUGIN + ': ' + (plugin() ? 'registered' : 'MISSING'));
  return lines;
}

// Asks the always-registered MvmDiag plugin what native registration really did (works even if MvmBridge is missing).
async function nativeReport() {
  const cap = globalThis.Capacitor;
  const d = cap && cap.Plugins ? cap.Plugins.MvmDiag : null;
  if (!d) return 'MvmDiag: MISSING (native files / MainActivity.kt not in this APK)';
  try { const r = await d.report(); return r.text; } catch (e) { return 'MvmDiag error: ' + ((e && e.message) || e); }
}

export const bridge = {
  diagnose,
  nativeReport,
  isNative() { return plugin() !== null; },

  // Health check: proves the Web -> Kotlin -> Web round trip works.
  async ping(value = 'ping') {
    if (!plugin()) return { ok: false, native: false };
    const res = await call('ping', { value });
    return { ...res, native: true };
  },

  // Device facts used later to pick safe preview/export settings.
  async getInfo() {
    if (!plugin()) return { native: false };
    const res = await call('getInfo');
    return { ...res, native: true };
  },

  // Opens the Android file picker. kind: 'audio' | 'image'.
  // Resolves { cancelled: true } or { cancelled: false, kind, media } once the file is
  // copied into the project folder and validated.
  pickMedia(kind, projectId) {
    return call('pickMedia', { kind, projectId });
  },

  // Which referenced files still exist: { audio?: bool, background?: bool }.
  checkMedia(projectId, files) {
    return call('checkMedia', { projectId, audio: files.audio, background: files.background });
  },

  // Small JPEG data URL for the background, or null.
  async getThumbnail(projectId, file) {
    const res = await call('getThumbnail', { projectId, file });
    return (res && res.dataUrl) || null;
  },

  // Removes a project's files from disk (no-op outside the app).
  // ── Playback (all of it runs in Kotlin; JS only sends commands and shows the state) ──
  // Every command resolves the same snapshot: { state, positionMs, durationMs, error? }
  // state: idle | ready | playing | paused | ended | error
  loadAudio(projectId, file) { return call('loadAudio', { projectId, file }); },
  play() { return call('play'); },
  pause() { return call('pause'); },
  seek(positionMs) { return call('seek', { positionMs: Math.round(positionMs) }); },
  stop() { return call('stop'); },
  getPlaybackState() { return call('getPlaybackState'); },
  releaseAudio() { return call('releaseAudio'); },
  // Native pushes the snapshot 4x/second while playing and on every state change.
  onPlayback(cb) {
    const p = plugin();
    if (!p || typeof p.addListener !== 'function') return null;
    try { return p.addListener('playback', cb); } catch (e) { return null; }
  },

  async removeMedia(projectId, kind) {
    if (!plugin()) return;
    await call('removeMedia', { projectId, kind });
  },

  async deleteProject(projectId) {
    if (!plugin()) return;
    await call('deleteProject', { projectId });
  },
};
