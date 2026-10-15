// Export dialog (Phase 7). Kotlin does all the work (shared Renderer -> MediaCodec -> MediaMuxer); this module only
//  - lets the user pick the resolution (the project's existing setting),
//  - starts / cancels the native job,
//  - polls its status about once a second (never per frame) and shows it,
//  - offers Share / Open for the finished file.
import { h, clear, $, toast } from './dom.js';
import { state, mutate } from './state.js';
import { bridge } from '../core/bridge.js';
import { MSG, errorMessage } from './messages.js';
import { RESOLUTIONS, outputSize } from '../core/schema.js';
import { projectPayload } from './nativePreview.js';
import { openSheet, closeSheet } from './dialogs.js';

const POLL_MS = 800;
const LABELS = {
  idle: '', preparing: 'Preparing…', encodingAudio: 'Encoding audio…', rendering: 'Rendering video…',
  finalizing: 'Finalizing and checking the file…', completed: 'Completed', failed: 'Failed', cancelled: 'Cancelled',
};
const ACTIVE = { preparing: 1, encodingAudio: 1, rendering: 1, finalizing: 1 };

let job = null;        // last snapshot from native (or a local failure)
let timer = 0;
let starting = false;  // a start request is in flight (blocks a second tap)
let paint = null;      // repaints the open dialog, if any

const isActive = () => starting || !!(job && ACTIVE[job.state]);

function stopPolling() { if (timer) { clearInterval(timer); timer = 0; } }

async function pollOnce() {
  try {
    const s = await bridge.getExportState();
    job = s;
    if (paint) paint();
    if (!ACTIVE[s.state]) {
      stopPolling();
      if (s.state === 'completed') toast('تم التصدير: ' + (s.outputName || ''));
    }
  } catch (e) {
    stopPolling();
    job = { state: 'failed', percent: 0, errorCode: 'EXPORT_UNEXPECTED' };
    if (paint) paint();
  }
}

function startPolling() { stopPolling(); timer = setInterval(pollOnce, POLL_MS); }

async function start() {
  const p = state.project;
  if (!bridge.isNative()) { toast(MSG.needNative); return; }
  if (isActive()) return;
  if (!p.media.audio) { job = { state: 'failed', percent: 0, errorCode: 'EXPORT_NO_AUDIO' }; if (paint) paint(); return; }
  if (!p.media.background) { job = { state: 'failed', percent: 0, errorCode: 'EXPORT_NO_BACKGROUND' }; if (paint) paint(); return; }
  starting = true; job = { state: 'preparing', percent: 0 }; if (paint) paint();
  try {
    const payload = projectPayload(p);
    payload.title = p.name;
    payload.aspectRatio = p.aspectRatio;
    payload.resolution = p.resolution;
    payload.fps = p.fps;
    await bridge.startExport(payload);
    starting = false;
    startPolling();
    pollOnce();
  } catch (e) {
    starting = false;
    job = { state: 'failed', percent: 0, errorCode: (e && e.code) || 'EXPORT_UNEXPECTED' };
    if (paint) paint();
  }
}

async function cancel() {
  try { await bridge.cancelExport(); } catch (e) { /* the poll shows the real state */ }
  pollOnce();
}

async function viewFile(kind) {
  if (!job || !job.outputPath) return;
  try { if (kind === 'share') await bridge.shareExport(job.outputPath); else await bridge.openExport(job.outputPath); }
  catch (e) { toast(errorMessage({ code: (e && e.code) || 'EXPORT_UNEXPECTED' })); }
}

function fmtSize(b) { return b >= 1048576 ? (b / 1048576).toFixed(1) + ' MB' : Math.max(1, Math.round(b / 1024)) + ' KB'; }
function fmtDur(ms) { const s = Math.round(ms / 1000); return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0'); }

export function openExport() {
  const p = state.project;
  const info = h('div', {});
  const status = h('div', { class: 'export-status', 'data-exp': 'status' });
  const actions = h('div', { class: 'actions' });

  const render = () => {
    const active = isActive();
    const size = outputSize(p.aspectRatio, p.resolution);
    clear(info);
    info.append(
      h('div', { class: 'hint' }, 'Resolution'),
      h('div', { class: 'seg' }, RESOLUTIONS.map((r) =>
        h('button', {
          type: 'button', class: r === p.resolution ? 'on' : '', disabled: active,
          onclick: () => { mutate((x) => { x.resolution = r; }); render(); },
        }, r))),
      h('div', { class: 'hint', style: { marginTop: '10px' } },
        `${p.aspectRatio} · ${size.width}×${size.height} · ${p.fps} fps · H.264 + AAC (MP4)`));

    clear(status);
    const s = job;
    if (s && s.state && s.state !== 'idle') {
      const pct = Math.max(0, Math.min(100, s.percent || 0));
      status.append(h('div', { class: 'export-label' }, LABELS[s.state] || s.state));
      if (ACTIVE[s.state] || s.state === 'completed') {
        status.append(
          h('div', { class: 'export-bar', role: 'progressbar', 'aria-valuenow': String(pct) }, h('div', { class: 'export-fill', style: { width: pct + '%' } })),
          h('div', { class: 'hint', dir: 'ltr', 'data-exp': 'pct' }, pct + '%' + (s.state === 'rendering' && s.frames ? ` · frame ${s.frame}/${s.frames}` : '')));
      }
      if (s.width && (ACTIVE[s.state] || s.state === 'completed')) {
        status.append(h('div', { class: 'hint', dir: 'ltr', 'data-exp': 'actual' }, `Encoding ${s.width}×${s.height} @ ${s.fps} fps`));
      }
      if (s.state === 'completed') {
        status.append(h('div', { class: 'hint', dir: 'ltr', 'data-exp': 'file' },
          `${s.outputName} · ${fmtSize(s.sizeBytes || 0)} · ${fmtDur(s.durationMs || 0)} · saved inside the app (Share / Open copies it to Movies)`));
      }
      if (s.state === 'failed') {
        status.append(h('div', { class: 'value missing', dir: 'auto', 'data-exp': 'error' }, errorMessage({ code: s.errorCode })));
        if (s.errorMessage) status.append(h('div', { class: 'hint diag', dir: 'ltr' }, s.errorMessage));
      }
      if (s.state === 'cancelled') status.append(h('div', { class: 'hint', dir: 'auto' }, MSG.exportCancelled));
    }

    clear(actions);
    if (active) {
      actions.append(
        h('button', { class: 'btn', type: 'button', disabled: true }, 'Close'),
        h('button', { class: 'btn accent', type: 'button', 'data-exp': 'cancel', onclick: cancel }, 'Cancel export'));
    } else {
      actions.append(h('button', { class: 'btn', type: 'button', onclick: () => { paint = null; closeSheet(); } }, 'Close'));
      if (job && job.state === 'completed' && job.outputPath) {
        actions.append(
          h('button', { class: 'btn', type: 'button', 'data-exp': 'open', onclick: () => viewFile('open') }, 'Open'),
          h('button', { class: 'btn', type: 'button', 'data-exp': 'share', onclick: () => viewFile('share') }, 'Share'));
      }
      actions.append(h('button', { class: 'btn accent', type: 'button', 'data-exp': 'start', onclick: start },
        job && job.state === 'completed' ? 'Export again' : 'Export'));
    }
  };
  paint = render;
  render();

  openSheet(h('h2', {}, 'Export'), info, status, actions);
  // While a job runs the sheet cannot be dismissed by tapping outside; Cancel is the way out.
  $('#overlay').onclick = (e) => { if (e.target === $('#overlay') && !isActive()) { paint = null; closeSheet(); } };
  // Re-opened while a job is still running: pick the polling up again.
  if (job && ACTIVE[job.state] && !timer) startPolling();
}
