// Audio analysis controller + waveform lane. Kotlin analyses (once, cached next to the audio file);
// JS asks for the result, decodes it into typed arrays and draws ONE canvas (no per-point DOM, no per-frame work).
import { $, toast } from './dom.js';
import { state, emit, freshWave } from './state.js';
import { bridge } from '../core/bridge.js';
import { errorMessage } from './messages.js';
import { decodeWaveData } from '../core/wavedata.js';
import { audioKey } from '../core/timeline.js';

const LANE_PAD = 12;   // #tracks horizontal padding (CSS)
const WAVE_H = 52;     // css px height of the waveform

let loadedKey = null;
let token = 0;
let canvas = null;
let drawnSig = '';

// ── Loading / analysing ─────────────────────────────────────────────────────
// Called after every render; does nothing unless the project or its audio changed.
export async function syncWave() {
  const project = state.project;
  if (!project) return;
  const key = audioKey(project);
  if (key === loadedKey) return;
  loadedKey = key;
  const my = ++token;
  if (state.wave.status === 'analyzing' && bridge.isNative()) bridge.cancelAnalysis().catch(() => {});
  state.wave = freshWave();
  const a = project.media.audio;
  if (!a || !bridge.isNative()) { emit(); return; }
  await run(my, project, a, false);
}

async function run(my, project, a, force) {
  const wave = state.wave;
  wave.status = 'loading';
  emit();
  try {
    let res = null;
    if (!force) {
      try { res = await bridge.getWaveData(project.id, a.file); } catch (e) { if (!e || e.code !== 'NO_WAVE_DATA') throw e; }
    }
    if (my !== token) return;
    if (!res) {
      wave.status = 'analyzing';
      wave.progress = 0;
      emit();
      await bridge.analyzeAudio(project.id, a.file, a.durationMs, force);
      if (my !== token) return;
      res = await bridge.getWaveData(project.id, a.file);
      if (my !== token) return;
    }
    wave.data = decodeWaveData(res);
    wave.status = 'ready';
  } catch (e) {
    if (my !== token) return;
    if (e && e.code === 'ANALYSIS_CANCELLED') { wave.status = 'none'; emit(); return; }
    wave.status = 'error';
    wave.error = (e && e.code) || 'ANALYSIS_FAILED';
    if (wave.error !== 'AUDIO_MISSING') toast(errorMessage(e)); // a missing file is already shown by the audio lane
  }
  emit();
}

// "Analyze again" button.
export function reanalyze() {
  const project = state.project;
  const a = project && project.media.audio;
  if (!a || !bridge.isNative() || state.wave.status === 'analyzing' || state.wave.status === 'loading') return;
  const my = ++token;
  loadedKey = audioKey(project);
  state.wave = freshWave();
  run(my, project, a, true);
}

// ── What the UI shows ───────────────────────────────────────────────────────
export function waveLabel() {
  const w = state.wave;
  if (!state.project.media.audio) return 'Waveform appears after you choose audio';
  if (!bridge.isNative()) return 'Waveform needs the app';
  if (w.status === 'loading') return 'Loading waveform…';
  if (w.status === 'analyzing') return 'Analyzing audio… ' + Math.round(w.progress * 100) + '%';
  if (w.status === 'error') return 'Waveform unavailable';
  return '';
}

export function waveSummary() {
  const w = state.wave;
  if (w.status === 'ready') return w.data.count + ' points · ' + w.data.sampleRate + '/s · bass, mid, treble';
  return waveLabel() || '—';
}

// Progress is the only thing updated outside a full render (a few times per analysis, text + bar only).
function onProgress(p) {
  const w = state.wave;
  if (w.status !== 'analyzing' || !p) return;
  w.progress = Math.min(1, Math.max(0, Number(p.progress) || 0));
  const label = waveLabel();
  document.querySelectorAll('.wave-label').forEach((el) => { el.textContent = label; });
  const bar = $('#wave-progress');
  if (bar) bar.style.width = Math.round(w.progress * 100) + '%';
}

// ── Canvas ──────────────────────────────────────────────────────────────────
// Puts the (single, persistent) canvas into the lane bar and redraws only if data or size changed.
export function mountWave(bar) {
  const data = state.wave.data;
  if (!data) return;
  if (!canvas) { canvas = document.createElement('canvas'); canvas.id = 'wave-canvas'; canvas.setAttribute('aria-label', 'Waveform'); }
  bar.append(canvas);
  drawWave(data);
}

function drawWave(data) {
  const cssW = Math.max(40, $('#tracks').clientWidth - 2 * LANE_PAD);
  const dpr = Math.min(2, window.devicePixelRatio || 1);
  const sig = [data.count, data.durationMs, data.amplitude[0], data.amplitude[data.count >> 1], cssW, dpr].join('/');
  if (sig === drawnSig) return;
  drawnSig = sig;

  const W = Math.round(cssW * dpr);
  const H = Math.round(WAVE_H * dpr);
  canvas.width = W;
  canvas.height = H;
  canvas.style.width = cssW + 'px';
  canvas.style.height = WAVE_H + 'px';
  const g = canvas.getContext('2d');
  g.clearRect(0, 0, W, H);
  g.fillStyle = getComputedStyle(document.documentElement).getPropertyValue('--lane-wave').trim() || '#a855f7';

  const amp = data.amplitude;
  const n = data.count;
  const mid = H / 2;
  for (let x = 0; x < W; x++) {
    const i0 = Math.floor((x / W) * n);
    const i1 = Math.max(i0 + 1, Math.floor(((x + 1) / W) * n));
    let m = 0;
    for (let i = i0; i < i1 && i < n; i++) if (amp[i] > m) m = amp[i];
    const h = Math.max(1, Math.round(Math.sqrt(m / 255) * mid)); // sqrt: quiet parts stay visible
    g.fillRect(x, mid - h, 1, h * 2);
  }
}

export function initAnalysis() {
  bridge.onAnalysisProgress(onProgress);
  window.addEventListener('resize', () => requestAnimationFrame(() => { if (state.wave.data && canvas && canvas.isConnected) drawWave(state.wave.data); }));
}
