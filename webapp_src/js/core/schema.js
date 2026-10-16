// Project schema — the single source of truth the Timeline/Renderer will read.
// Keep it additive: new features add new keys with defaults; never rename existing keys
// without bumping SCHEMA_VERSION and adding a migration in migrate().

export const SCHEMA_VERSION = 2; // v2: `scene` (layers of waves + text) and `templateId`; v1 projects are migrated in migrate()

export const ASPECTS = { '16:9': [16, 9], '9:16': [9, 16], '1:1': [1, 1] };
export const RESOLUTIONS = ['360p', '420p', '720p'];
export const WAVE_STYLES = ['line', 'bars', 'mirrored']; // v1 names (legacy `wave` key); every one of them is also a scene shape
// Every waveform shape the native renderer can draw (wire names must match scene/SceneModel.kt WaveShape).
export const WAVE_SHAPES = ['mirrored', 'bars', 'line', 'neonBars', 'spectrumMirror', 'dots', 'radial', 'ring', 'ribbon', 'glowLine'];
export const TEXT_FONTS = ['sans', 'serif', 'mono', 'condensed', 'light', 'medium', 'cursive', 'casual'];
export const TEXT_ANIMS = ['none', 'fade', 'slideUp', 'slideDown', 'pop'];
export const TEXT_REVEALS = ['line', 'words', 'chars'];
export const TEXT_ALIGNS = ['left', 'center', 'right'];
export const MAX_LAYERS = 160; // native SceneParser.MAX_LAYERS must be >= this
export const MAX_TEXT_CHARS = 600;
export const LAYER_TYPES = ['wave', 'text'];

// Which controls each shape really honours in the native renderer (the UI only shows these; no fake sliders).
// legacy shapes (line/bars/mirrored) run the original overlay: colour, position, size, audio source, opacity, timing.
const BAND = ['color', 'gradient', 'position', 'height', 'thickness', 'glow', 'intensity', 'opacity', 'reactive'];
export const SHAPE_CAPS = {
  mirrored: ['color', 'position', 'height', 'opacity', 'reactive'],
  bars: ['color', 'position', 'height', 'opacity', 'reactive'],
  line: ['color', 'position', 'height', 'opacity', 'reactive'],
  neonBars: BAND, spectrumMirror: BAND, dots: BAND,
  radial: ['color', 'gradient', 'center', 'size', 'thickness', 'glow', 'intensity', 'opacity', 'reactive'],
  ring: ['color', 'gradient', 'center', 'size', 'thickness', 'glow', 'intensity', 'opacity', 'reactive'],
  ribbon: [...BAND, 'speed'], glowLine: [...BAND, 'speed'],
};
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
    // LEGACY (schema v1). Kept only so old data is never lost and migrate() can read it; the app no longer edits or sends it.
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
    // v2: everything drawn over the picture, bottom to top. Layer shapes: see defaultWaveLayer / defaultTextLayer.
    scene: { layers: [] },
    templateId: null, // the template last applied (informational; the scene itself is the truth)
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

// ── Layers ─────────────────────────────────────────────────────────────────
let layerCounter = 0;
export function newLayerId(type) {
  layerCounter += 1;
  return (type === 'text' ? 't' : 'w') + Date.now().toString(36) + layerCounter.toString(36) + Math.random().toString(36).slice(2, 5);
}

export function defaultWaveLayer(shape = 'mirrored') {
  const radialLike = shape === 'radial' || shape === 'ring';
  return {
    id: newLayerId('wave'), type: 'wave', visible: true, startMs: 0, endMs: 0,
    shape, color: '#ffffff', color2: '#a29bfe', gradient: false, colorMode: 'manual',
    position: radialLike ? 'center' : 'bottom', customY: 0.8, height: shape === 'ribbon' || shape === 'glowLine' ? 0.22 : 0.18,
    reactive: true, reactTo: 'volume', thickness: 1, opacity: 1, intensity: 1, glow: radialLike || shape === 'neonBars' ? 0.5 : 0,
    speed: 1, size: 0.22, cx: 0.5, cy: 0.5,
  };
}

export function defaultTextLayer(text = '') {
  return {
    id: newLayerId('text'), type: 'text', visible: true, startMs: 0, endMs: 0,
    text, font: 'sans', bold: true, italic: false, size: 0.06, color: '#ffffff', align: 'center', x: 0.5, y: 0.5,
    maxWidth: 0.84, lineSpacing: 1.2, animIn: 'fade', animOut: 'fade', reveal: 'line', easing: 'easeOut',
    inMs: 400, outMs: 300, revealMs: 0, shadow: true, outline: false, outlineColor: '#000000',
    box: false, boxColor: '#000000', boxOpacity: 0.45, opacity: 1,
  };
}

const HEX = /^#[0-9a-fA-F]{6}$/;
const color = (v, fb) => (typeof v === 'string' && HEX.test(v) ? v.toLowerCase() : fb);
const bool = (v, fb) => (typeof v === 'boolean' ? v : fb);
const ms = (v) => { v = Math.round(Number(v)); return Number.isFinite(v) && v > 0 ? Math.min(v, 86400000) : 0; };

// One clean layer or null. Mirrors native SceneParser's limits so what the UI shows is what native draws.
export function sanitizeLayer(raw) {
  if (!isObj(raw) || !LAYER_TYPES.includes(raw.type)) return null;
  const id = typeof raw.id === 'string' && /^[A-Za-z0-9_-]{1,40}$/.test(raw.id) ? raw.id : newLayerId(raw.type);
  if (raw.type === 'wave') {
    const d = defaultWaveLayer(pick(raw.shape, WAVE_SHAPES, 'mirrored'));
    return {
      ...d, id, visible: bool(raw.visible, true), startMs: ms(raw.startMs), endMs: ms(raw.endMs),
      shape: pick(raw.shape, WAVE_SHAPES, d.shape), color: color(raw.color, d.color), color2: color(raw.color2, d.color2),
      gradient: bool(raw.gradient, false), colorMode: pick(raw.colorMode, WAVE_COLOR_MODES, 'manual'),
      position: pick(raw.position, WAVE_POSITIONS, d.position), customY: clamp(Number(raw.customY), 0, 1, d.customY),
      height: clamp(Number(raw.height), 0.05, 0.5, d.height), reactive: bool(raw.reactive, true),
      reactTo: pick(raw.reactTo, REACT_SOURCES, 'volume'), thickness: clamp(Number(raw.thickness), 0.4, 3, 1),
      opacity: clamp(Number(raw.opacity), 0, 1, 1), intensity: clamp(Number(raw.intensity), 0, 2, 1),
      glow: clamp(Number(raw.glow), 0, 1, d.glow), speed: clamp(Number(raw.speed), 0, 2, 1),
      size: clamp(Number(raw.size), 0.08, 0.45, 0.22), cx: clamp(Number(raw.cx), 0, 1, 0.5), cy: clamp(Number(raw.cy), 0, 1, 0.5),
    };
  }
  const d = defaultTextLayer();
  return {
    ...d, id, visible: bool(raw.visible, true), startMs: ms(raw.startMs), endMs: ms(raw.endMs),
    text: String(raw.text == null ? '' : raw.text).slice(0, MAX_TEXT_CHARS),
    font: pick(raw.font, TEXT_FONTS, 'sans'), bold: bool(raw.bold, true), italic: bool(raw.italic, false),
    size: clamp(Number(raw.size), 0.02, 0.25, d.size), color: color(raw.color, d.color), align: pick(raw.align, TEXT_ALIGNS, 'center'),
    x: clamp(Number(raw.x), 0, 1, 0.5), y: clamp(Number(raw.y), 0, 1, 0.5), maxWidth: clamp(Number(raw.maxWidth), 0.2, 1, d.maxWidth),
    lineSpacing: clamp(Number(raw.lineSpacing), 0.8, 2, d.lineSpacing),
    animIn: pick(raw.animIn, TEXT_ANIMS, 'fade'), animOut: pick(raw.animOut, TEXT_ANIMS, 'fade'), reveal: pick(raw.reveal, TEXT_REVEALS, 'line'),
    easing: pick(raw.easing, ['linear', 'easeOut', 'easeInOut', 'backOut'], 'easeOut'),
    inMs: Math.min(3000, ms(raw.inMs == null ? d.inMs : raw.inMs)), outMs: Math.min(3000, ms(raw.outMs == null ? d.outMs : raw.outMs)),
    revealMs: Math.min(20000, ms(raw.revealMs)), shadow: bool(raw.shadow, true), outline: bool(raw.outline, false),
    outlineColor: color(raw.outlineColor, '#000000'), box: bool(raw.box, false), boxColor: color(raw.boxColor, '#000000'),
    boxOpacity: clamp(Number(raw.boxOpacity), 0, 1, d.boxOpacity), opacity: clamp(Number(raw.opacity), 0, 1, 1),
  };
}

export function sanitizeLayers(list) {
  if (!Array.isArray(list)) return [];
  const out = []; const seen = new Set();
  for (const raw of list) {
    if (out.length >= MAX_LAYERS) break;
    const l = sanitizeLayer(raw);
    if (!l) continue;
    if (seen.has(l.id)) l.id = newLayerId(l.type);
    seen.add(l.id); out.push(l);
  }
  return out;
}

// v1 -> v2: the single legacy wave overlay becomes the first scene layer (same style/colour/position/size, so the picture does not change).
function migrate(raw) {
  if (!isObj(raw)) return raw;
  const v = Number(raw.schemaVersion) || 1;
  if (v < 2 && !(isObj(raw.scene) && Array.isArray(raw.scene.layers))) {
    const layers = [];
    const w = raw.wave;
    if (isObj(w) && w.enabled !== false) {
      layers.push({
        type: 'wave', shape: w.style, color: w.color, colorMode: w.colorMode, position: w.position, customY: w.customY,
        height: w.height, reactive: w.reactive, reactTo: w.reactTo,
      });
    }
    return { ...raw, scene: { layers } };
  }
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
  p.scene = { layers: sanitizeLayers(p.scene && p.scene.layers) };
  p.templateId = typeof p.templateId === 'string' ? p.templateId.slice(0, 40) : null;
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
