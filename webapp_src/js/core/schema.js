// Project schema — the single source of truth the Timeline/Renderer will read.
// Keep it additive: new features add new keys with defaults; never rename existing keys
// without bumping SCHEMA_VERSION and adding a migration in migrate().

export const SCHEMA_VERSION = 1;

export const ASPECTS = { '16:9': [16, 9], '9:16': [9, 16], '1:1': [1, 1] };
export const RESOLUTIONS = ['360p', '420p', '720p'];
export const WAVE_STYLES = ['line', 'bars', 'mirrored'];
// MVP exposes bottom + custom; top/center are accepted by the schema for later.
export const WAVE_POSITIONS = ['top', 'center', 'bottom', 'custom'];
export const WAVE_COLOR_MODES = ['manual', 'adaptive'];
export const REACT_SOURCES = ['volume', 'bass', 'mid', 'treble'];
// Effect Engine settings (Phase 5). The maths lives in Kotlin (effects/EffectEngine.kt); these are only its inputs.
// Wire names must match effects/EffectSettings.kt.
export const EFFECT_PRESETS = ['none', 'subtle', 'pulse', 'beat', 'cinematic'];
export const MOTION_MODES = ['static', 'slowZoom', 'float', 'pulseZoom', 'cinematicDrift'];

export function defaults() {
  return {
    schemaVersion: SCHEMA_VERSION,
    id: '',
    name: 'Untitled',
    createdAt: 0,
    updatedAt: 0,
    aspectRatio: '16:9',
    resolution: '720p',
    fps: 30,
    media: {
      // References only (never file bytes). `file` is a name inside the project's native folder.
      // audio:      { file, name, mime, sizeBytes, durationMs, sampleRate, channels, rev }
      // background: { file, thumb, name, mime, sizeBytes, width, height }
      audio: null,
      background: null,
    },
    wave: {
      enabled: true,
      style: 'mirrored',
      colorMode: 'manual',
      color: '#ffffff',
      position: 'bottom',
      customY: 0.8, // 0 = top, 1 = bottom (used when position === 'custom')
      reactive: true,
      reactTo: 'volume',
      height: 0.18, // fraction of frame height
    },
    backgroundMotion: {
      zoom: 1.05,
      pan: true,
    },
    // Inputs of the native Effect Engine. Additive: later effects add keys here (never rename existing ones).
    //   preset     how the picture reacts to the audio; motion: the background's camera path (independent of preset)
    //   intensity  0..2 amount of every reaction;  smoothing  0..1 (0 snappy, 1 very smooth);  seed  shake pattern id
    // (backgroundMotion above is the Phase 1 placeholder and is not used by the engine.)
    effects: { preset: 'pulse', motion: 'slowZoom', intensity: 1, smoothing: 0.5, seed: 1 },
    // Where the user left off (ms). Restored paused when the project is reopened.
    playhead: { positionMs: 0 },
    // Reserved so future engines can store settings without a schema bump.
    extensions: {},
  };
}

function isObj(v) { return v && typeof v === 'object' && !Array.isArray(v); }

function mergeDefaults(base, raw) {
  const out = Array.isArray(base) ? [] : {};
  for (const k of Object.keys(base)) {
    const b = base[k];
    const r = raw ? raw[k] : undefined;
    if (isObj(b) && Object.keys(b).length) out[k] = mergeDefaults(b, isObj(r) ? r : {});
    else out[k] = r === undefined ? b : r;
  }
  // Keep unknown keys (written by newer builds) instead of dropping them.
  if (isObj(raw)) for (const k of Object.keys(raw)) if (!(k in out)) out[k] = raw[k];
  return out;
}

function pick(value, allowed, fallback) {
  return allowed.includes(value) ? value : fallback;
}

// Same rule as the native side (ProjectFiles): plain names only, no paths.
const SAFE_FILE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;

function nonNeg(v) {
  v = Number(v);
  return Number.isFinite(v) && v >= 0 ? v : 0;
}

// Returns a clean audio reference, or null when the stored value is unusable.
export function sanitizeAudio(a) {
  if (!isObj(a) || !SAFE_FILE.test(String(a.file))) return null;
  return {
    file: String(a.file),
    name: String(a.name || a.file).slice(0, 200),
    mime: String(a.mime || ''),
    sizeBytes: nonNeg(a.sizeBytes),
    durationMs: nonNeg(a.durationMs),
    sampleRate: nonNeg(a.sampleRate),
    channels: nonNeg(a.channels),
    rev: nonNeg(a.rev), // import time; part of the audio identity (see core/timeline.js audioKey)
  };
}

export function sanitizeBackground(b) {
  if (!isObj(b) || !SAFE_FILE.test(String(b.file))) return null;
  return {
    file: String(b.file),
    thumb: SAFE_FILE.test(String(b.thumb)) ? String(b.thumb) : null,
    name: String(b.name || b.file).slice(0, 200),
    mime: String(b.mime || ''),
    sizeBytes: nonNeg(b.sizeBytes),
    width: nonNeg(b.width),
    height: nonNeg(b.height),
  };
}

function migrate(raw) {
  // v1 is the first version. Future: if (raw.schemaVersion < 2) { ... }
  return raw;
}

export function normalizeProject(raw) {
  const d = defaults();
  const p = mergeDefaults(d, migrate(isObj(raw) ? raw : {}));
  p.schemaVersion = SCHEMA_VERSION;
  p.aspectRatio = pick(p.aspectRatio, Object.keys(ASPECTS), d.aspectRatio);
  p.resolution = pick(p.resolution, RESOLUTIONS, d.resolution);
  p.wave.enabled = p.wave.enabled !== false;
  p.wave.style = pick(p.wave.style, WAVE_STYLES, d.wave.style);
  p.wave.position = pick(p.wave.position, WAVE_POSITIONS, d.wave.position);
  p.wave.colorMode = pick(p.wave.colorMode, WAVE_COLOR_MODES, d.wave.colorMode);
  p.wave.reactTo = pick(p.wave.reactTo, REACT_SOURCES, d.wave.reactTo);
  p.wave.customY = clamp(Number(p.wave.customY), 0, 1, d.wave.customY);
  p.wave.height = clamp(Number(p.wave.height), 0.05, 0.5, d.wave.height);
  p.backgroundMotion.zoom = clamp(Number(p.backgroundMotion.zoom), 1, 1.3, d.backgroundMotion.zoom);
  p.effects.preset = pick(p.effects.preset, EFFECT_PRESETS, d.effects.preset);
  p.effects.motion = pick(p.effects.motion, MOTION_MODES, d.effects.motion);
  p.effects.intensity = clamp(Number(p.effects.intensity), 0, 2, d.effects.intensity);
  p.effects.smoothing = clamp(Number(p.effects.smoothing), 0, 1, d.effects.smoothing);
  p.effects.seed = Number.isInteger(p.effects.seed) ? Math.max(0, Math.min(1000000, p.effects.seed)) : d.effects.seed;
  p.media.audio = sanitizeAudio(p.media.audio);
  p.media.background = sanitizeBackground(p.media.background);
  p.playhead.positionMs = nonNeg(p.playhead.positionMs);
  p.fps = 30; // fixed in MVP
  p.name = String(p.name || 'Untitled').slice(0, 60);
  return p;
}

function clamp(v, lo, hi, fallback) {
  return Number.isFinite(v) ? Math.min(hi, Math.max(lo, v)) : fallback;
}

export function newId() {
  if (globalThis.crypto && typeof globalThis.crypto.randomUUID === 'function') return globalThis.crypto.randomUUID();
  return 'p-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 8);
}

export function newProject({ name, aspectRatio }) {
  const now = Date.now();
  const p = normalizeProject({ name: name || 'Untitled', aspectRatio });
  p.id = newId();
  p.createdAt = now;
  p.updatedAt = now;
  return p;
}

// "720p" means the SHORT side is 720 px, so 9:16 becomes 720x1280.
// Sizes are rounded to even numbers (H.264 requirement). Phase 7 will re-check
// encoder alignment (e.g. 746x420) on the real device before relying on these.
export function outputSize(aspectRatio, resolution) {
  const short = parseInt(resolution, 10) || 720;
  const [aw, ah] = ASPECTS[aspectRatio] || ASPECTS['16:9'];
  const even = (n) => Math.max(2, Math.round(n / 2) * 2);
  if (aw >= ah) return { width: even(short * aw / ah), height: short };
  return { width: short, height: even(short * ah / aw) };
}
