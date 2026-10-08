// Playback controller + timeline/transport view.
// Native (Kotlin AudioEngine) owns the audio and the clock. JS only sends commands and draws:
//  - native pushes {state, positionMs, durationMs} 4x/second while playing and on every change;
//  - between pushes the playhead is extrapolated, redrawn at most 20x/second (text/transform only).
import { $, formatTime, toast } from './dom.js';
import { state, emit, scheduleSave, flushSave, freshPlay } from './state.js';
import { bridge } from '../core/bridge.js';
import { MSG, errorMessage } from './messages.js';
import { renderStateAt, audioKey } from '../core/timeline.js';

const FRAME_MS = 50;        // max UI redraw rate while playing
const SEEK_THROTTLE_MS = 120; // max native seeks per second while scrubbing
const STALE_MS = 1500;      // no native push for this long while playing -> ask for the state
const PAD = 12;             // horizontal padding of #tracks (CSS)

let loadedKey = null;
let token = 0;
let rafId = 0;
let lastDraw = 0;
let scrubbing = false;
let scrubMs = 0;
let lastSeekAt = 0;
let seekTimer = 0;
let ignoreUntil = 0;
let refreshing = false;
const shown = {};

const audio = () => (state.project ? state.project.media.audio : null);
const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));

export function durationMs() {
  const pl = state.play;
  if (pl.durationMs > 0) return pl.durationMs;
  const a = audio();
  return a ? a.durationMs : 0;
}

export function positionMs() {
  const pl = state.play;
  if (pl.status === 'playing') return Math.min(durationMs(), pl.positionMs + (performance.now() - pl.at));
  return pl.positionMs;
}

function canSeek() {
  return !!audio() && bridge.isNative() && ['ready', 'playing', 'paused', 'ended'].includes(state.play.status);
}

// ── Native -> state ─────────────────────────────────────────────────────────
function apply(snap, fromEvent) {
  if (!snap || !state.project) return;
  const pl = state.play;
  const prev = pl.status;
  if (snap.durationMs > 0) pl.durationMs = snap.durationMs;
  // After a scrub, late pushes must not yank the playhead back; the end of the audio always wins.
  const holdPosition = scrubbing || (fromEvent && performance.now() < ignoreUntil && snap.state !== 'ended');
  if (!holdPosition) {
    pl.positionMs = snap.positionMs;
    pl.at = performance.now();
  }
  pl.status = snap.state;
  pl.error = snap.error || null;
  if (pl.status !== prev) onStatusChange(prev);
  if (!rafId && pl.status === 'playing') rafId = requestAnimationFrame(loop);
  draw(true);
}

function onStatusChange(prev) {
  const pl = state.play;
  if (prev === 'playing') savePosition();
  if (pl.status === 'ended') savePosition();
  if (pl.status === 'error') toast(errorMessage({ code: pl.error || 'PLAYER_FAILED' }));
}

function savePosition() {
  const p = state.project;
  if (!p) return;
  const pos = state.play.status === 'ended' ? 0 : Math.round(positionMs());
  if (p.playhead.positionMs !== pos) {
    p.playhead.positionMs = pos;
    scheduleSave();
  }
}

// ── Loading ─────────────────────────────────────────────────────────────────
// Called after every render: (re)loads the native player only when project/audio changed.
export async function sync() {
  const project = state.project;
  if (!project) return;
  const a = project.media.audio;
  const key = audioKey(project);
  if (key === loadedKey) return;
  const sameProject = loadedKey !== null && loadedKey.split('|')[0] === project.id; // audio replaced, not project opened
  loadedKey = key;
  const my = ++token;
  clearTimeout(seekTimer);
  scrubbing = false;
  state.play = freshPlay();
  if (!bridge.isNative()) { draw(true); return; }
  if (!a) {
    try { await bridge.releaseAudio(); } catch (e) { /* nothing is loaded anyway */ }
    draw(true);
    return;
  }
  state.play.status = 'loading';
  draw(true);
  try {
    const snap = await bridge.loadAudio(project.id, a.file);
    if (my !== token) return;
    apply(snap, false);
    const saved = sameProject ? 0 : project.playhead.positionMs;
    if (saved > 1000 && saved < snap.durationMs - 1000) {
      const moved = await bridge.seek(saved);
      if (my !== token) return;
      apply(moved, false);
    }
  } catch (e) {
    if (my !== token) return;
    state.play.status = 'error';
    if (e && e.code === 'AUDIO_MISSING') { state.mediaOk.audio = false; emit(); } // shown by the lane/panel, no toast
    else toast(errorMessage(e));
  }
  draw(true);
}

async function refresh() {
  if (refreshing || !bridge.isNative() || !audio() || state.play.status === 'loading') return;
  refreshing = true;
  try { apply(await bridge.getPlaybackState(), true); } catch (e) { /* next push will fix it */ } finally { refreshing = false; }
}

// ── Commands ────────────────────────────────────────────────────────────────
export async function toggle() {
  if (!audio()) { toast(MSG.noAudio); return; }
  if (!bridge.isNative()) { toast(MSG.needNative); return; }
  if (state.play.status === 'loading') return;
  if (state.play.status === 'idle' || state.play.status === 'error') {
    loadedKey = null; // retry the load (file may have been fixed, or the player crashed)
    await sync();
    if (state.play.status === 'idle' || state.play.status === 'error') return;
  }
  try {
    const snap = state.play.status === 'playing' ? await bridge.pause() : await bridge.play();
    apply(snap, false);
  } catch (e) {
    toast(errorMessage(e));
    refresh();
  }
}

function sendSeek(ms) {
  lastSeekAt = performance.now();
  return bridge.seek(ms).then((snap) => apply(snap, true)).catch((e) => { toast(errorMessage(e)); refresh(); });
}

function queueSeek(ms) {
  const wait = SEEK_THROTTLE_MS - (performance.now() - lastSeekAt);
  clearTimeout(seekTimer);
  if (wait <= 0) sendSeek(ms);
  else seekTimer = setTimeout(() => sendSeek(ms), wait);
}

export function scrubStart(ratio) {
  if (!canSeek()) { if (!audio()) toast(MSG.noAudio); return; }
  scrubbing = true;
  scrubMove(ratio);
}

export function scrubMove(ratio) {
  if (!scrubbing) return;
  scrubMs = clamp(ratio, 0, 1) * durationMs();
  draw(true);
  queueSeek(scrubMs);
}

export function scrubEnd(ratio) {
  if (!scrubbing) return;
  if (typeof ratio === 'number') scrubMs = clamp(ratio, 0, 1) * durationMs();
  scrubbing = false;
  clearTimeout(seekTimer);
  ignoreUntil = performance.now() + 500; // late native pushes must not yank the playhead back
  const pl = state.play;
  pl.positionMs = scrubMs;
  pl.at = performance.now();
  if (pl.status === 'ended') pl.status = scrubMs > 0 ? 'paused' : 'ready';
  sendSeek(scrubMs);
  savePosition();
  draw(true);
}

// ── View ────────────────────────────────────────────────────────────────────
function loop(now) {
  rafId = 0;
  if (state.play.status !== 'playing') { draw(true); return; }
  if (now - lastDraw >= FRAME_MS) draw();
  if (performance.now() - state.play.at > STALE_MS) { state.play.at = performance.now(); refresh(); }
  rafId = requestAnimationFrame(loop);
}

function setText(sel, text) {
  if (shown[sel] === text) return;
  shown[sel] = text;
  const el = $(sel);
  if (el) el.textContent = text;
}

export function draw(force) {
  lastDraw = performance.now();
  if (!state.project) return;
  const pl = state.play;
  const dur = durationMs();
  const pos = scrubbing ? scrubMs : positionMs();
  const hasAudio = !!audio();
  const cur = formatTime(pos);
  const tot = formatTime(dur);

  setText('#time-current', cur);
  setText('#time-total', tot);
  setText('#ruler-end', tot);
  setText('#ruler-mid', formatTime(dur / 2));
  const rs = renderStateAt(state.project, pos, { durationMs: dur, playing: pl.status === 'playing', waveData: state.wave.data });
  setText('#preview-time', formatTime(rs.timeMs) + ' / ' + tot);

  const playing = pl.status === 'playing';
  const btn = $('#btn-play');
  setText('#btn-play', playing ? '⏸' : '▶');
  btn.disabled = pl.status === 'loading';
  btn.setAttribute('aria-label', playing ? 'Pause' : 'Play');

  const seek = $('#seek');
  seek.disabled = !canSeek();
  const v = String(Math.round(rs.progress * 1000));
  if (!scrubbing && seek.value !== v) seek.value = v;

  const head = $('#playhead');
  head.hidden = !hasAudio;
  if (hasAudio) {
    const width = $('#tracks').clientWidth - 2 * PAD;
    head.style.transform = 'translateX(' + (Math.max(0, width) * rs.progress).toFixed(1) + 'px)';
  }
  $('#tracks').classList.toggle('seekable', canSeek());
}

// ── Wiring (once) ───────────────────────────────────────────────────────────
export function initPlayback() {
  $('#btn-play').addEventListener('click', toggle);

  const tracks = $('#tracks');
  const ratioOf = (e) => {
    const r = tracks.getBoundingClientRect();
    return (e.clientX - r.left - PAD) / Math.max(1, r.width - 2 * PAD);
  };
  tracks.addEventListener('pointerdown', (e) => {
    try { tracks.setPointerCapture(e.pointerId); } catch (err) { /* older WebViews */ }
    scrubStart(ratioOf(e));
  });
  tracks.addEventListener('pointermove', (e) => scrubMove(ratioOf(e)));
  tracks.addEventListener('pointerup', (e) => scrubEnd(ratioOf(e)));
  tracks.addEventListener('pointercancel', () => scrubEnd());

  const seek = $('#seek');
  seek.addEventListener('input', () => {
    const r = Number(seek.value) / 1000;
    if (!scrubbing) scrubStart(r); else scrubMove(r);
  });
  seek.addEventListener('change', () => scrubEnd(Number(seek.value) / 1000));

  bridge.onPlayback((snap) => apply(snap, true));

  window.addEventListener('resize', () => draw(true));
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') {
      savePosition();
      flushSave();
    } else {
      refresh(); // native pauses when the app goes to the background
    }
  });
}
