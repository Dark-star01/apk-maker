// Dark Studio panels: timeline bars, the dock (layers / templates / media / motion) and the top bar state.
import { h, clear, $, formatTime } from './dom.js';
import { state, select, setTab } from './state.js';
import { EFFECT_PRESETS, MOTION_MODES, MAX_LAYERS } from '../core/schema.js';
import { TEMPLATES } from '../core/templates.js';
import { canUndo, canRedo } from '../core/history.js';
import { bridge } from '../core/bridge.js';
import { pick, remove } from './media.js';
import { waveLabel, waveSummary, mountWave, reanalyze } from './analysis.js';
import { MSG } from './messages.js';
import { fxReset } from './effects.js';
import { row, select as selectEl, slider } from './controls.js';
import { shapeIcon, textIcon } from './icons.js';
import { inspectorView, layerTitle } from './inspector.js';
import { layers, moveLayer, toggleVisible, editEffects, applyTemplate } from './sceneOps.js';
import { openAddLayer } from './sheets.js';
import { durationMs } from './playback.js';
import { toast } from './dom.js';

// ── Media status (shared by the audio lane and the media dock) ──
function audioView() {
  const { audio } = state.project.media;
  if (state.busy.audio) return { status: 'loading', text: 'جارٍ التحميل…' };
  if (!audio) return { status: 'empty', text: 'لم تُضف أغنية بعد' };
  if (!state.mediaOk.audio) return { status: 'missing', text: MSG.audioMissing };
  return { status: 'ready', text: `${audio.name} · ${formatTime(audio.durationMs)}` };
}

function backgroundView() {
  const { background } = state.project.media;
  if (state.busy.image) return { status: 'loading', text: 'جارٍ التحميل…' };
  if (!background) return { status: 'empty', text: 'لم تُضف صورة بعد' };
  if (!state.mediaOk.background) return { status: 'missing', text: MSG.backgroundMissing };
  const dims = background.width && background.height ? ` · ${background.width}×${background.height}` : '';
  return { status: 'ready', text: background.name + dims };
}

// ── Timeline: the audio lane (scrubbing area) + one bar per layer ──
function audioLane() {
  const w = state.wave;
  const bar = h('div', { class: 'lane-bar ' + (w.status === 'ready' ? 'wave-ready' : 'slot') });
  if (w.status === 'ready') mountWave(bar);
  else {
    if (w.status === 'analyzing') bar.append(h('div', { class: 'fill progress', id: 'wave-progress', style: { background: 'var(--lane-wave)', width: Math.round(w.progress * 100) + '%' } }));
    const v = audioView();
    bar.append(h('div', { class: 'name wave-label', dir: 'auto' }, state.project.media.audio ? waveLabel() : v.text));
  }
  return bar;
}

export function renderTracks() {
  const lane = $('#lane-audio');
  clear(lane);
  lane.append(audioLane());

  const root = $('#lanes');
  clear(root);
  const dur = durationMs();
  const all = layers();
  if (!all.length) { root.append(h('div', { class: 'lane-empty', dir: 'auto' }, 'لا توجد طبقات بعد — أضف موجة أو نصًا أو اختر قالبًا')); return; }
  for (let i = all.length - 1; i >= 0; i--) {
    const l = all[i];
    const startPct = dur > 0 ? Math.min(100, (l.startMs / dur) * 100) : 0;
    const endMs = l.endMs > 0 ? l.endMs : dur;
    const endPct = dur > 0 ? Math.min(100, Math.max(startPct + 1.5, (endMs / dur) * 100)) : 100;
    root.append(h('button', { type: 'button', class: 'lane-bar layer-bar' + (l.id === state.sel ? ' sel' : '') + (l.visible ? '' : ' off'), onclick: () => select(l.id),
      'aria-label': layerTitle(l) },
      h('span', { class: 'span ' + l.type, style: { left: startPct + '%', width: (endPct - startPct) + '%' } }),
      h('span', { class: 'name', dir: 'auto' }, layerTitle(l))));
  }
}

// ── Dock panels ──
function layerRow(l, i, n) {
  const order = n - 1 - i; // list shows the top layer first
  const range = formatTime(l.startMs) + '–' + (l.endMs > 0 ? formatTime(l.endMs) : 'النهاية');
  return h('div', { class: 'layer-row' + (l.id === state.sel ? ' sel' : '') + (l.visible ? '' : ' off') },
    h('button', { type: 'button', class: 'layer-main', onclick: () => select(l.id) },
      l.type === 'wave' ? shapeIcon(l.shape) : textIcon(),
      h('span', { class: 'layer-text' }, h('span', { class: 'layer-name', dir: 'auto' }, layerTitle(l)), h('small', { dir: 'ltr' }, range))),
    h('button', { class: 'icon-btn', type: 'button', 'aria-label': l.visible ? 'إخفاء' : 'إظهار', onclick: () => toggleVisible(l.id) }, l.visible ? '👁' : '🚫'),
    h('button', { class: 'icon-btn', type: 'button', 'aria-label': 'للأعلى', disabled: order === n - 1, onclick: () => moveLayer(l.id, 1) }, '▲'),
    h('button', { class: 'icon-btn', type: 'button', 'aria-label': 'للأسفل', disabled: order === 0, onclick: () => moveLayer(l.id, -1) }, '▼'));
}

function layersPanel() {
  const insp = inspectorView();
  if (insp) return insp;
  const all = layers();
  const head = h('div', { class: 'panel-head' },
    h('strong', {}, 'الطبقات' + (all.length ? ' (' + all.length + ')' : '')),
    h('button', { class: 'btn accent small', type: 'button', disabled: all.length >= MAX_LAYERS, onclick: openAddLayer }, '＋ إضافة'));
  if (!all.length) {
    return h('div', {}, head,
      h('div', { class: 'empty' },
        h('div', { class: 'empty-title' }, 'ابدأ صناعة الفيديو'),
        h('div', { class: 'hint' }, 'اختر قالبًا جاهزًا ثم عدّله، أو أضف موجة صوتية أو نصًا بنفسك.'),
        h('div', { class: 'btn-row' },
          h('button', { class: 'btn accent', type: 'button', onclick: () => setTab('templates') }, 'اختر قالبًا'),
          h('button', { class: 'btn', type: 'button', onclick: openAddLayer }, 'أضف طبقة'))));
  }
  const list = h('div', { class: 'layer-list' });
  for (let i = all.length - 1; i >= 0; i--) list.append(layerRow(all[i], i, all.length));
  return h('div', {}, head, list);
}

// Schematic card: the template's own data drawn as little marks (not a render of the real frame).
function templateCard(t) {
  const prev = h('div', { class: 'tpl-prev' });
  if (state.thumb) prev.style.backgroundImage = 'url("' + state.thumb.url + '")';
  for (const l of t.layers) {
    if (l.type === 'wave') {
      const radial = l.shape === 'radial' || l.shape === 'ring';
      const y = radial ? l.cy : l.position === 'top' ? 0.18 : l.position === 'center' ? 0.5 : 0.82;
      const el = h('span', { class: 'tpl-mark', style: { top: (y * 100) + '%', color: l.color, left: (radial ? l.cx * 100 : 50) + '%', width: radial ? '34%' : '70%', height: radial ? '60%' : '30%', opacity: l.opacity } }, shapeIcon(l.shape));
      prev.append(el);
    } else {
      prev.append(h('span', { class: 'tpl-text', style: { top: (l.y * 100) + '%', color: l.color } }, 'أبجد'));
    }
  }
  return h('button', { type: 'button', class: 'tpl-card' + (state.project.templateId === t.id ? ' on' : ''), onclick: () => {
    applyTemplate(t);
    toast('تم تطبيق «' + t.name + '» — يمكنك التراجع بزر ↶');
  } }, prev, h('span', { class: 'tpl-name' }, t.name), h('small', {}, t.desc));
}

function templatesPanel() {
  return h('div', {},
    h('div', { class: 'panel-head' }, h('strong', {}, 'القوالب')),
    h('div', { class: 'hint' }, 'القالب يستبدل الموجات والحركة ويطبّق تنسيقه على نصوصك دون تغيير كلماتك أو توقيتها. المعاينات أدناه رسوم تخطيطية.'),
    h('div', { class: 'tpl-grid' }, TEMPLATES.map(templateCard)));
}

function mediaPanel() {
  const { media } = state.project;
  const a = audioView();
  const b = backgroundView();
  const hasAudio = !!media.audio;
  const busy = state.wave.status === 'analyzing' || state.wave.status === 'loading';
  return h('div', {},
    h('div', { class: 'panel-head' }, h('strong', {}, 'الوسائط')),
    row('الأغنية', h('span', { class: 'value ' + a.status, dir: 'auto' }, a.text)),
    h('div', { class: 'row' }, h('span', { class: 'hint' }, 'MP3 · WAV · M4A · AAC'),
      h('div', { class: 'btns' },
        h('button', { class: 'btn' + (hasAudio ? '' : ' accent'), type: 'button', disabled: state.busy.audio, onclick: () => pick('audio') }, hasAudio ? 'استبدال' : 'اختيار أغنية'),
        hasAudio ? h('button', { class: 'btn', type: 'button', disabled: state.busy.audio, onclick: () => remove('audio') }, 'إزالة') : null)),
    row('الصورة', h('span', { class: 'value ' + b.status, dir: 'auto' }, b.text)),
    h('div', { class: 'row' }, h('span', { class: 'hint' }, 'JPG · PNG · WEBP'),
      h('div', { class: 'btns' },
        h('button', { class: 'btn' + (media.background ? '' : ' accent'), type: 'button', disabled: state.busy.image, onclick: () => pick('image') }, media.background ? 'استبدال' : 'اختيار صورة'),
        media.background ? h('button', { class: 'btn', type: 'button', disabled: state.busy.image, onclick: () => remove('image') }, 'إزالة') : null)),
    hasAudio ? row('تحليل الصوت', h('span', { class: 'value wave-label', dir: 'auto' }, waveSummary())) : null,
    hasAudio && bridge.isNative()
      ? h('div', { class: 'row' }, h('span', { class: 'hint' }, 'يُحسب مرة واحدة ثم يُحفظ'),
        h('button', { class: 'btn', type: 'button', disabled: busy, onclick: reanalyze }, 'إعادة التحليل'))
      : null);
}

const PRESET_LABELS = { none: 'بدون', subtle: 'خفيف', pulse: 'نبض', beat: 'إيقاع', cinematic: 'سينمائي' };
const MOTION_LABELS = { static: 'ثابت', slowZoom: 'تقريب بطيء', float: 'طفو', pulseZoom: 'تقريب نابض', cinematicDrift: 'انجراف سينمائي' };

function motionPanel() {
  const e = state.project.effects;
  const set = (patch) => { editEffects(patch); fxReset(); };
  const live = (key) => (patch) => { editEffects(patch, { key: 'fx:' + key, light: true }); fxReset(); };
  return h('div', {},
    h('div', { class: 'panel-head' }, h('strong', {}, 'حركة الصورة والتأثيرات')),
    row('تفاعل الصورة مع الصوت', selectEl(EFFECT_PRESETS, PRESET_LABELS, e.preset, (v) => set({ preset: v }))),
    row('حركة الكاميرا', selectEl(MOTION_MODES, MOTION_LABELS, e.motion, (v) => set({ motion: v }))),
    row('الشدة', slider({ min: 0, max: 200, value: Math.round(e.intensity * 100), fmt: (v) => v + '%', onLive: (v) => live('intensity')({ intensity: v / 100 }) })),
    row('النعومة', slider({ min: 0, max: 100, value: Math.round(e.smoothing * 100), fmt: (v) => v + '%', onLive: (v) => live('smoothing')({ smoothing: v / 100 }) })),
    h('div', { class: 'hint' }, 'هذه الإعدادات تؤثر في الصورة الخلفية (تقريب واهتزاز ووهج). الموجات والنصوص لها إعداداتها داخل «الطبقات».'));
}

// What the open panel shows depends on exactly these values: rebuild only when it changes (typing / dragging must never lose focus).
function panelSig() {
  const p = state.project;
  const dur = durationMs();
  switch (state.tab) {
    case 'templates': return 'tpl|' + p.templateId + '|' + (state.thumb ? state.thumb.url.length : 0);
    case 'media': return 'media|' + JSON.stringify([p.media, state.busy, state.mediaOk, state.wave.status, bridge.isNative()]);
    case 'motion': return 'motion|' + JSON.stringify(p.effects);
    default: return 'layers|' + state.sel + '|' + state.insp + '|' + dur + '|' + JSON.stringify(p.scene.layers);
  }
}
let lastSig = '';
export function panelSigRefresh() { if (state.project) lastSig = panelSig(); }

export function renderPanel() {
  if (!state.project) return;
  const sig = panelSig();
  if (sig === lastSig && $('#panel').firstChild) return;
  lastSig = sig;
  const root = $('#panel');
  clear(root);
  root.scrollTop = 0;
  const view = { layers: layersPanel, templates: templatesPanel, media: mediaPanel, motion: motionPanel }[state.tab] || layersPanel;
  root.append(view());
  const on = root.querySelector('.chip-tab.on');
  if (on && on.scrollIntoView) on.scrollIntoView({ block: 'nearest', inline: 'center' });
}

export function renderTabs() {
  document.querySelectorAll('#tabs button').forEach((b) => b.classList.toggle('active', b.dataset.tab === state.tab));
  // Contextual tools: while a layer is being edited its inspector owns the bottom area (‹ الطبقات goes back), so the preview gets the room.
  $('#app').classList.toggle('inspecting', !!(state.sel && state.tab === 'layers'));
  const u = $('#btn-undo'); const r = $('#btn-redo');
  if (u) u.disabled = !canUndo();
  if (r) r.disabled = !canRedo();
}
