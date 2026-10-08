// Effects controller (diagnostics view). The Kotlin EffectEngine owns every effect value; this module only
// asks it for the EffectState at the playhead and shows the numbers. No effect maths happens in JS.
import { state } from './state.js';
import { bridge } from '../core/bridge.js';
import { audioKey } from '../core/timeline.js';
import { ASPECTS } from '../core/schema.js';

const MIN_GAP_MS = 120;   // at most ~8 native calls per second, and never more than one in flight
const RETRY_MS = 2000;    // after an error
let token = 0;

function settingsSig(project) {
  const e = project.effects;
  return [e.preset, e.motion, e.intensity, e.smoothing, e.seed, project.aspectRatio, audioKey(project), state.wave.status].join('|');
}

// Called from the playback draw loop (<= 20/s). Does nothing unless the FX tab is open.
export function fxTick(posMs, durMs, force) {
  const fx = state.fx;
  const project = state.project;
  fx.pos = posMs; fx.dur = durMs;
  if (!project || state.tab !== 'fx' || !bridge.isNative() || fx.busy) return;
  const now = performance.now();
  if (now - fx.at < (fx.error ? RETRY_MS : MIN_GAP_MS)) return;
  const sig = Math.round(posMs / 40) + '@' + settingsSig(project);
  if (!force && sig === fx.sig) return;
  fx.busy = true;
  fx.at = now;
  const my = token;
  const a = project.media.audio;
  const [aw, ah] = ASPECTS[project.aspectRatio] || ASPECTS['16:9'];
  bridge.getEffectState({
    projectId: project.id, file: a ? a.file : null, timeMs: posMs, durationMs: durMs || (a ? a.durationMs : 0),
    effects: project.effects, aspect: aw / ah,
  }).then((v) => {
    if (my !== token || state.project !== project) return;
    fx.values = v; fx.error = null; fx.sig = sig;
    paint(v);
  }).catch((e) => {
    if (my !== token) return;
    fx.error = (e && e.code) || 'UNEXPECTED';
    fx.sig = null;
    paintError(fx.error);
  }).then(() => { if (my === token) fx.busy = false; });
}

// A setting changed or the project/audio changed: forget the old values and ask again right away.
export function fxReset() {
  token++;
  state.fx.busy = false; state.fx.sig = null; state.fx.error = null; state.fx.at = 0;
  if (state.tab === 'fx') fxTick(state.fx.pos, state.fx.dur, true);
}

// ── DOM (targeted updates only: the panel itself is not rebuilt per call) ───
const FIELDS = ['scale', 'rotationDeg', 'translateX', 'translateY', 'opacity'];
const METERS = ['amplitude', 'bass', 'mid', 'treble', 'glow', 'intensity', 'shake'];
const f = (v, d) => (Number.isFinite(v) ? v.toFixed(d) : '—');

function paint(v) {
  const set = (k, t) => { const el = document.querySelector('[data-fx="' + k + '"]'); if (el && el.textContent !== t) el.textContent = t; };
  set('scale', f(v.scale, 3));
  set('rotationDeg', f(v.rotationDeg, 2) + '°');
  set('translateX', f(v.translateX * 100, 2) + '%');
  set('translateY', f(v.translateY * 100, 2) + '%');
  set('opacity', f(v.opacity, 2));
  set('status', v.audioReactive ? 'Reacting to wave.data' : 'No wave data: neutral (motion only)');
  for (const k of METERS) {
    const val = k in v ? v[k] : v.audio[k];
    const el = document.querySelector('[data-meter="' + k + '"]');
    if (el) el.style.width = Math.round(Math.min(1, Math.max(0, val)) * 100) + '%';
  }
}

function paintError(code) {
  const el = document.querySelector('[data-fx="status"]');
  if (el) el.textContent = 'Effect engine error: ' + code;
}

// Re-applies the last received values to a freshly built FX panel (panels are rebuilt on every state change).
export function fxRepaint() { if (state.fx.values) paint(state.fx.values); }

export function fxLastText() {
  const v = state.fx.values;
  if (!v) return 'Waiting for the native engine…';
  return v.audioReactive ? 'Reacting to wave.data' : 'No wave data: neutral (motion only)';
}
export { FIELDS, METERS };
