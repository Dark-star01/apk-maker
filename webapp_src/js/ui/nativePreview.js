// Native Preview glue. Kotlin owns the picture, the effects and the clock; this module only
//  1. tells native WHERE the preview is (the #preview rectangle, hidden while a dialog covers it),
//  2. tells native WHICH project data to draw (names + settings, never pixels),
//  3. shows the renderer's diagnostics.
// It never computes a visual value and never sends frames; native follows the AudioEngine clock by itself.
import { $ } from './dom.js';
import { state } from './state.js';
import { bridge } from '../core/bridge.js';
import { ASPECTS } from '../core/schema.js';
import { audioKey } from '../core/timeline.js';
import { positionMs } from './playback.js';

const RETRY_MS = 5000;
let chain = Promise.resolve();
let attached = false;
let failedAt = 0;
let rectSig = '';
let projSig = '';
let pending = false;

function currentRect() {
  const el = $('#preview');
  const r = el.getBoundingClientRect();
  if (r.width < 4 || r.height < 4) return null;
  const dpr = window.devicePixelRatio || 1;
  const overlayOpen = !$('#overlay').hidden; // a dialog/sheet is open: the native view must not cover it
  return { x: r.left, y: r.top, width: r.width, height: r.height, dpr, viewportWidth: window.innerWidth, visible: !overlayOpen };
}

// While the native view owns the picture the HTML stand-in (thumbnail + label) must not also be visible.
function setNative(on) {
  const el = $('#preview');
  if (el) el.classList.toggle('native-on', !!on);
}

function projectPayload(project) {
  const a = project.media.audio;
  const b = project.media.background;
  const [aw, ah] = ASPECTS[project.aspectRatio] || ASPECTS['16:9'];
  return {
    projectId: project.id,
    background: b ? b.file : null,
    audioFile: a ? a.file : null,
    durationMs: a ? a.durationMs : 0,
    effects: project.effects,
    aspect: aw / ah,
    wave: project.wave,
  };
}

// Called after every render and on layout changes. Cheap: it only talks to native when something really changed.
export function syncPreview() {
  const project = state.project;
  if (!bridge.isNative() || !project) return;
  if (state.pv.status === 'error' && performance.now() - failedAt < RETRY_MS) return;
  const rect = currentRect();
  if (!rect) return;

  const rSig = [Math.round(rect.x * rect.dpr), Math.round(rect.y * rect.dpr), Math.round(rect.width * rect.dpr), Math.round(rect.height * rect.dpr), rect.visible, rect.viewportWidth].join(',');
  const payload = projectPayload(project);
  const pSig = JSON.stringify(payload) + '|' + JSON.stringify(project.media.background) + '|' + audioKey(project) + '|' + state.wave.status;
  const needAttach = !attached;
  const needRect = rSig !== rectSig;
  const needProject = pSig !== projSig;
  if (!needAttach && !needRect && !needProject) return;
  rectSig = rSig;
  projSig = pSig;

  // One ordered queue: native sees attach -> rect -> project in this order, never interleaved.
  chain = chain.then(async () => {
    if (needAttach) { await bridge.attachPreview(rect); attached = true; state.pv.status = 'attached'; setNative(true); }
    else if (needRect) await bridge.setPreviewRect(rect);
    if (needProject || needAttach) await bridge.setPreviewProject(payload);
    state.pv.error = null;
  }).catch((e) => {
    state.pv.status = 'error';
    state.pv.error = (e && e.code) || 'UNEXPECTED';
    failedAt = performance.now();
    attached = false; rectSig = ''; projSig = ''; setNative(false); // retry from scratch later (the static stand-in shows again)
  });
}

function schedule() {
  if (pending) return;
  pending = true;
  requestAnimationFrame(() => { pending = false; syncPreview(); });
}

export function initPreview() {
  if (!bridge.isNative()) return;
  if (typeof ResizeObserver === 'function') new ResizeObserver(schedule).observe($('#preview'));
  window.addEventListener('resize', schedule);
  if (typeof MutationObserver === 'function') new MutationObserver(schedule).observe($('#overlay'), { attributes: true, attributeFilter: ['hidden'] });
  setInterval(pollDiag, 1000);
}

// ── Diagnostics ─────────────────────────────────────────────────────────────
export function rendererLine() {
  const d = state.pv.info;
  if (!bridge.isNative()) return 'Native renderer: app only';
  if (state.pv.status === 'error') return 'Renderer error: ' + state.pv.error;
  if (!d) return 'Renderer: waiting…';
  const t = (d.timeMs / 1000).toFixed(2);
  const img = d.imageW ? d.imageW + '×' + d.imageH : d.background;
  return 'Renderer: ' + d.renderer + ' · Surface: ' + d.surface + ' · Frame: ' + d.frame + ' · Time: ' + t + 's · FPS: ' + d.fps + ' (target ' + d.targetFps + ') · Image: ' + img + (d.error ? ' · ' + d.error : '');
}

export function waveLine() {
  const d = state.pv.info;
  if (!bridge.isNative()) return 'Waveform overlay: app only';
  if (!d || !d.waveStatus) return 'Waveform overlay: waiting…';
  const names = { on: 'ENABLED', off: 'DISABLED', 'no data': 'ENABLED · data MISSING' };
  return 'Waveform overlay: ' + (names[d.waveStatus] || d.waveStatus) + ' · style: ' + d.waveStyle + (d.waveError ? ' · error: ' + d.waveError : ' · error: none');
}

function paintDiag() {
  const wl = waveLine();
  document.querySelectorAll('[data-rd="wave"]').forEach((el) => { if (el.textContent !== wl) el.textContent = wl; });
  const line = rendererLine();
  document.querySelectorAll('[data-rd="text"]').forEach((el) => { if (el.textContent !== line) el.textContent = line; });
}

export function rendererRepaint() { paintDiag(); }

async function pollDiag() {
  if (!state.project || (state.tab !== 'fx' && state.tab !== 'settings') || document.visibilityState === 'hidden') return;
  try { state.pv.info = await bridge.getRendererState(); } catch (e) { state.pv.info = null; }
  paintDiag();
}

// "Same time -> same frame": native renders the current time twice (and another time once) offscreen with the real Renderer.
export async function runSelfTest() {
  const el = document.querySelector('[data-rd="self"]');
  const set = (t) => { if (el) el.textContent = t; };
  set('Running…');
  try {
    const t = Math.round(positionMs());
    const [aw, ah] = ASPECTS[state.project.aspectRatio] || ASPECTS['16:9'];
    const r = await bridge.rendererSelfTest({ timeMs: t, otherMs: t + 1234, aspect: aw / ah });
    set((r.identical ? 'IDENTICAL' : 'DIFFERENT') + ' at ' + (t / 1000).toFixed(2) + 's (crc ' + r.crcA + ' / ' + r.crcB + ') · ' +
      (r.changesWithTime ? 'frame changes with time' : 'same as +1.2s (static)') + ' · ' + r.width + '×' + r.height);
  } catch (e) {
    set('Self-test failed: ' + ((e && e.code) || (e && e.message) || e));
  }
}
