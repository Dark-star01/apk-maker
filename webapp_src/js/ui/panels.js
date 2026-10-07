// Bottom panels (Media / Wave / Settings) and the three timeline lanes.
import { h, clear, $, toast, formatTime } from './dom.js';
import { state, mutate } from './state.js';
import { WAVE_STYLES, outputSize } from '../core/schema.js';
import { bridge } from '../core/bridge.js';
import { pick, remove } from './media.js';
import { MSG } from './messages.js';

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

// ── Timeline lanes ──
export function renderTracks() {
  const root = $('#tracks');
  clear(root);
  const lanes = [
    { title: '🎵 AUDIO', view: audioView(), color: 'var(--lane-audio)' },
    { title: '🖼 BACKGROUND', view: backgroundView(), color: 'var(--lane-bg)' },
    { title: '〰 WAVE', view: { status: 'ready', text: LABELS[state.project.wave.style] }, color: 'var(--lane-wave)' },
  ];
  for (const l of lanes) {
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
  const set = (patch) => mutate((p) => Object.assign(p.wave, patch));
  return h('div', {},
    row('Style', seg(WAVE_STYLES, w.style, (v) => set({ style: v }))),
    row('Color', seg(['manual', 'adaptive'], w.colorMode, (v) => set({ colorMode: v }))),
    w.colorMode === 'manual'
      ? row('Wave color', h('input', { type: 'color', value: w.color, onchange: (e) => set({ color: e.target.value }) }))
      : h('div', { class: 'hint' }, 'Adaptive color is picked from the background image (needs a background).'),
    row('Position', seg(['bottom', 'custom'], w.position, (v) => set({ position: v }))),
    w.position === 'custom'
      ? row('Vertical', h('input', {
        type: 'range', min: 0, max: 100, value: Math.round(w.customY * 100),
        onchange: (e) => set({ customY: Number(e.target.value) / 100 }),
      }))
      : null,
    row('Reactive', h('div', { class: 'seg' },
      h('button', { type: 'button', class: w.reactive ? 'on' : '', onclick: () => set({ reactive: true }) }, 'On'),
      h('button', { type: 'button', class: !w.reactive ? 'on' : '', onclick: () => set({ reactive: false }) }, 'Off'))));
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
  const view = { media: mediaPanel, wave: wavePanel, settings: settingsPanel }[state.tab] || mediaPanel;
  root.append(view());
}

export function renderTabs() {
  document.querySelectorAll('#tabs button').forEach((b) => b.classList.toggle('active', b.dataset.tab === state.tab));
}
