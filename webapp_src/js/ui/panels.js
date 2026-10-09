// Bottom panels (Media / Wave / FX / Settings) and the three timeline lanes.
import { h, clear, $, toast, formatTime } from './dom.js';
import { state, mutate } from './state.js';
import { WAVE_STYLES, EFFECT_PRESETS, MOTION_MODES, outputSize } from '../core/schema.js';
import { bridge } from '../core/bridge.js';
import { pick, remove } from './media.js';
import { waveLabel, waveSummary, mountWave, reanalyze } from './analysis.js';
import { MSG } from './messages.js';
import { fxReset, fxLastText, fxRepaint } from './effects.js';
import { rendererLine, waveLine, rendererRepaint, runSelfTest } from './nativePreview.js';

const LABELS = { line: 'Line', bars: 'Bars', mirrored: 'Mirrored', bottom: 'Bottom', custom: 'Custom', manual: 'Manual', adaptive: 'Adaptive' };

function seg(options, current, onPick, labels = LABELS) {
  return h('div', { class: 'seg' }, options.map((o) =>
    h('button', { type: 'button', class: o === current ? 'on' : '', onclick: () => onPick(o) }, labels[o] || o)));
}

function row(label, control) {
  return h('div', { class: 'row' }, h('span', { class: 'label' }, label), control);
}

// ── Media status text, shared by the lanes and the Media panel ──
// status: 'empty' | 'loading' | 'missing' | 'ready'
function audioView() {
  const { audio } = state.project.media;
  if (state.busy.audio) return { status: 'loading', text: 'Loading…' };
  if (!audio) return { status: 'empty', text: 'No audio selected' };
  if (!state.mediaOk.audio) return { status: 'missing', text: MSG.audioMissing };
  return { status: 'ready', text: `${audio.name} · ${formatTime(audio.durationMs)}` };
}

function backgroundView() {
  const { background } = state.project.media;
  if (state.busy.image) return { status: 'loading', text: 'Loading…' };
  if (!background) return { status: 'empty', text: 'No background selected' };
  if (!state.mediaOk.background) return { status: 'missing', text: MSG.backgroundMissing };
  const dims = background.width && background.height ? ` · ${background.width}×${background.height}` : '';
  return { status: 'ready', text: background.name + dims };
}

// WAVE lane: the real waveform (one canvas) once Kotlin's analysis is ready, otherwise a status line.
function waveLane(l) {
  const w = state.wave;
  const bar = h('div', { class: 'lane-bar ' + (w.status === 'ready' ? 'wave-ready' : 'slot') });
  if (w.status === 'ready') mountWave(bar);
  else {
    const analyzing = w.status === 'analyzing';
    // (Node.append(null) would print the text "null": only append what exists)
    if (analyzing) bar.append(h('div', { class: 'fill progress', id: 'wave-progress', style: { background: l.color, width: Math.round(w.progress * 100) + '%' } }));
    bar.append(h('div', { class: 'name wave-label', dir: 'auto' }, waveLabel()));
  }
  return h('div', { class: 'lane' }, h('div', { class: 'lane-title' }, l.title), bar);
}

// ── Timeline lanes ──
export function renderTracks() {
  const root = $('#lanes');
  clear(root);
  const lanes = [
    { title: '🎵 AUDIO', view: audioView(), color: 'var(--lane-audio)' },
    { title: '🖼 BACKGROUND', view: backgroundView(), color: 'var(--lane-bg)' },
    { title: '〰 WAVE', wave: true, color: 'var(--lane-wave)' },
  ];
  for (const l of lanes) {
    if (l.wave) { root.append(waveLane(l)); continue; }
    const ready = l.view.status === 'ready';
    root.append(h('div', { class: 'lane' },
      h('div', { class: 'lane-title' }, l.title),
      h('div', { class: 'lane-bar ' + l.view.status },
        ready ? h('div', { class: 'fill', style: { background: l.color } }) : null,
        h('div', { class: 'name', dir: 'auto' }, l.view.text))));
  }
}

// ── Panels ──
function mediaPanel() {
  const { media } = state.project;
  const a = audioView();
  const b = backgroundView();
  return h('div', {},
    row('Audio', h('span', { class: 'value ' + a.status, dir: 'auto' }, a.status === 'empty' ? 'Not selected' : a.text)),
    h('div', { class: 'row' }, h('span', { class: 'hint' }, 'MP3 · WAV · M4A · AAC'),
      h('div', { class: 'btns' },
        h('button', { class: 'btn', type: 'button', disabled: state.busy.audio, onclick: () => pick('audio') },
          media.audio ? 'Replace audio' : 'Choose audio'),
        media.audio ? h('button', { class: 'btn', type: 'button', disabled: state.busy.audio, onclick: () => remove('audio') }, 'Remove') : null)),
    row('Background', h('span', { class: 'value ' + b.status, dir: 'auto' }, b.status === 'empty' ? 'Not selected' : b.text)),
    h('div', { class: 'row' }, h('span', { class: 'hint' }, 'JPG · PNG · WEBP'),
      h('div', { class: 'btns' },
        h('button', { class: 'btn', type: 'button', disabled: state.busy.image, onclick: () => pick('image') },
          media.background ? 'Replace image' : 'Choose image'),
        media.background ? h('button', { class: 'btn', type: 'button', disabled: state.busy.image, onclick: () => remove('image') }, 'Remove') : null)));
}

function wavePanel() {
  const w = state.project.wave;
  const hasAudio = !!state.project.media.audio;
  const busy = state.wave.status === 'analyzing' || state.wave.status === 'loading';
  const set = (patch) => mutate((p) => Object.assign(p.wave, patch));
  return h('div', {},
    row('Analysis', h('span', { class: 'value wave-label', dir: 'auto' }, waveSummary())),
    hasAudio && bridge.isNative()
      ? h('div', { class: 'row' }, h('span', { class: 'hint' }, 'Computed once, then cached'),
        h('button', { class: 'btn', type: 'button', disabled: busy, onclick: reanalyze }, 'Analyze again'))
      : null,
    row('Overlay', h('div', { class: 'seg' },
      h('button', { type: 'button', class: w.enabled ? 'on' : '', onclick: () => set({ enabled: true }) }, 'On'),
      h('button', { type: 'button', class: !w.enabled ? 'on' : '', onclick: () => set({ enabled: false }) }, 'Off'))),
    row('Style', seg(WAVE_STYLES, w.style, (v) => set({ style: v }))),
    row('Color', seg(['manual', 'adaptive'], w.colorMode, (v) => set({ colorMode: v }))),
    w.colorMode === 'manual'
      ? row('Wave color', h('input', { type: 'color', value: w.color, onchange: (e) => set({ color: e.target.value }) }))
      : h('div', { class: 'hint' }, 'Adaptive color is picked from the background image (needs a background).'),
    row('Position', seg(['top', 'center', 'bottom', 'custom'], w.position, (v) => set({ position: v }))),
    w.position === 'custom'
      ? row('Vertical', h('input', {
        type: 'range', min: 0, max: 100, value: Math.round(w.customY * 100),
        onchange: (e) => set({ customY: Number(e.target.value) / 100 }),
      }))
      : null,
    row('Size', h('input', {
      type: 'range', min: 5, max: 50, value: Math.round(w.height * 100),
      onchange: (e) => set({ height: Number(e.target.value) / 100 }),
    })),
    row('Reactive', h('div', { class: 'seg' },
      h('button', { type: 'button', class: w.reactive ? 'on' : '', onclick: () => set({ reactive: true }) }, 'On'),
      h('button', { type: 'button', class: !w.reactive ? 'on' : '', onclick: () => set({ reactive: false }) }, 'Off'))));
}

const PRESET_LABELS = { none: 'None', subtle: 'Subtle', pulse: 'Pulse', beat: 'Beat', cinematic: 'Cinematic' };
const MOTION_LABELS = { static: 'Static', slowZoom: 'Slow zoom', float: 'Float', pulseZoom: 'Pulse zoom', cinematicDrift: 'Cinematic drift' };

function select(options, labels, current, onPick) {
  return h('select', { onchange: (e) => onPick(e.target.value) },
    options.map((o) => h('option', { value: o, selected: o === current }, labels[o] || o)));
}

function readRow(label, key) {
  return h('div', { class: 'row fx-row' }, h('span', { class: 'label' }, label), h('span', { class: 'value', 'data-fx': key }, '—'));
}

function meterRow(label, key) {
  return h('div', { class: 'row fx-row' }, h('span', { class: 'label' }, label),
    h('div', { class: 'meter' }, h('i', { 'data-meter': key })));
}

// FX tab: the inputs of the native Effect Engine + a live readout of what it returns at the playhead.
function fxPanel() {
  const e = state.project.effects;
  const set = (patch) => { mutate((p) => Object.assign(p.effects, patch)); fxReset(); };
  const root = h('div', {},
    row('Preset', select(EFFECT_PRESETS, PRESET_LABELS, e.preset, (v) => set({ preset: v }))),
    row('Background motion', select(MOTION_MODES, MOTION_LABELS, e.motion, (v) => set({ motion: v }))),
    row('Intensity', h('input', { type: 'range', min: 0, max: 200, value: Math.round(e.intensity * 100), onchange: (ev) => set({ intensity: Number(ev.target.value) / 100 }) })),
    row('Smoothing', h('input', { type: 'range', min: 0, max: 100, value: Math.round(e.smoothing * 100), onchange: (ev) => set({ smoothing: Number(ev.target.value) / 100 }) })),
    h('div', { class: 'hint diag', 'data-rd': 'text', dir: 'ltr' }, rendererLine()),
    h('div', { class: 'hint', 'data-fx': 'status', dir: 'auto' }, bridge.isNative() ? fxLastText() : 'Effect values come from the native engine (app only)'),
    readRow('Scale', 'scale'), readRow('Rotation', 'rotationDeg'), readRow('Translate X', 'translateX'),
    readRow('Translate Y', 'translateY'), readRow('Opacity', 'opacity'),
    meterRow('Amplitude', 'amplitude'), meterRow('Bass', 'bass'), meterRow('Mid', 'mid'), meterRow('Treble', 'treble'),
    meterRow('Glow', 'glow'), meterRow('Intensity', 'intensity'), meterRow('Shake', 'shake'));
  return root;
}

function settingsPanel() {
  const p = state.project;
  const size = outputSize(p.aspectRatio, p.resolution);
  const nativeRow = h('span', { class: 'value', id: 'native-status' }, 'Checking…');
  refreshNativeStatus(nativeRow);
  const diagNative = h('div', { style: { whiteSpace: 'pre-wrap' } }, '…');
  bridge.nativeReport().then((t) => { diagNative.textContent = t; });
  return h('div', {},
    row('Aspect ratio', h('span', { class: 'value' }, p.aspectRatio)),
    row('Export size', h('span', { class: 'value' }, `${p.resolution} · ${size.width}×${size.height} · ${p.fps} fps`)),
    row('Native engine', nativeRow),
    h('div', { class: 'hint diag', 'data-rd': 'text', dir: 'ltr' }, rendererLine()),
    h('div', { class: 'hint diag', 'data-rd': 'wave', dir: 'ltr' }, waveLine()),
    h('div', { class: 'row' }, h('span', { class: 'hint' }, 'Renders the same time twice and compares the pixels'),
      h('button', { class: 'btn', type: 'button', onclick: runSelfTest }, 'Renderer self-test')),
    h('div', { class: 'hint diag', 'data-rd': 'self', dir: 'ltr' }, '—'),
    h('div', { class: 'row' }, h('span', { class: 'hint' }, 'Tests the Web → Kotlin → Web connection'),
      h('button', { class: 'btn', type: 'button', onclick: runPing }, 'Test bridge')),
    h('div', { class: 'hint diag', dir: 'ltr' }, bridge.diagnose().map((l) => h('div', {}, l)), diagNative));
}

async function refreshNativeStatus(el) {
  try {
    const info = await bridge.getInfo();
    el.textContent = info.native
      ? `OK · Android ${info.sdk} · ${info.totalMemMb} MB RAM`
      : 'Not available (browser)';
  } catch (e) {
    el.textContent = 'Error: ' + (e && e.message ? e.message : e);
  }
}

async function runPing() {
  try {
    const r = await bridge.ping('hello');
    toast(r.native ? `Bridge OK (thread: ${r.thread})` : 'No native engine (running in a browser)');
  } catch (e) {
    toast('Bridge error: ' + (e && e.message ? e.message : e));
  }
}

export function renderPanel() {
  const root = $('#panel');
  clear(root);
  const view = { media: mediaPanel, wave: wavePanel, fx: fxPanel, settings: settingsPanel }[state.tab] || mediaPanel;
  root.append(view());
  if (state.tab === 'fx') fxRepaint();
  rendererRepaint();
}

export function renderTabs() {
  document.querySelectorAll('#tabs button').forEach((b) => b.classList.toggle('active', b.dataset.tab === state.tab));
}
