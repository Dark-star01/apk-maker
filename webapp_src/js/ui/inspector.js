// Inspector: the contextual tools of the selected layer. Every control writes through sceneOps.editLayer, i.e. into
// project.scene — the same data the native renderer receives for Preview and Export.
import { h } from './dom.js';
import { state, select } from './state.js';
import { editLayer, layerById, removeLayer, duplicateLayer, toggleVisible } from './sceneOps.js';
import { row, seg, toggle, select as selectEl, colorInput, slider, pct, clock } from './controls.js';
import { shapeIcon, textIcon, SHAPE_LABELS } from './icons.js';
import { WAVE_SHAPES, SHAPE_CAPS, TEXT_FONTS, TEXT_ANIMS } from '../core/schema.js';
import { durationMs, positionMs } from './playback.js';

const FONT_LABELS = { sans: 'عادي', serif: 'سيريف', mono: 'أحادي المسافة', condensed: 'ضيّق', light: 'خفيف', medium: 'متوسط', cursive: 'مائل خطي', casual: 'عفوي' };
const ANIM_LABELS = { none: 'بدون', fade: 'تلاشٍ', slideUp: 'انزلاق للأعلى', slideDown: 'انزلاق للأسفل', pop: 'قفزة' };
const REVEAL_LABELS = { line: 'السطر كاملًا', words: 'كلمة بكلمة', chars: 'حرفًا بحرف' };
const POS_LABELS = { top: 'أعلى', center: 'وسط', bottom: 'أسفل', custom: 'مخصص' };
const SRC_LABELS = { volume: 'مستوى الصوت', bass: 'الجهير', mid: 'المتوسط', treble: 'الحاد' };
const ALIGN_LABELS = { left: 'يسار', center: 'وسط', right: 'يمين' };

const WAVE_TABS = [['main', 'الشكل'], ['color', 'اللون'], ['place', 'الحجم والموضع'], ['react', 'التفاعل'], ['time', 'التوقيت']];
const TEXT_TABS = [['main', 'النص'], ['place', 'الموضع'], ['anim', 'الظهور'], ['style', 'التنسيق'], ['time', 'التوقيت']];

export function layerTitle(l) {
  if (l.type === 'wave') return 'موجة · ' + (SHAPE_LABELS[l.shape] || l.shape);
  const t = l.text.replace(/\s+/g, ' ').trim();
  return t ? (t.length > 28 ? t.slice(0, 28) + '…' : t) : 'نص فارغ';
}

export function inspectorView() {
  const l = layerById(state.sel);
  if (!l) return null;
  const tabs = l.type === 'wave' ? WAVE_TABS : TEXT_TABS;
  const tab = tabs.some((t) => t[0] === state.insp) ? state.insp : 'main';
  const set = (patch, o) => editLayer(l.id, patch, o);
  const live = (key) => (patch) => editLayer(l.id, patch, { key: 'l:' + l.id + ':' + key, light: true });
  const body = l.type === 'wave' ? waveBody(l, tab, set, live) : textBody(l, tab, set, live);
  return h('div', { class: 'insp' },
    h('div', { class: 'insp-head' },
      h('button', { class: 'btn small', type: 'button', onclick: () => select(null), 'aria-label': 'رجوع إلى الطبقات' }, '‹ الطبقات'),
      h('span', { class: 'insp-title', dir: 'auto' }, layerTitle(l)),
      h('button', { class: 'icon-btn', type: 'button', 'aria-label': l.visible ? 'إخفاء الطبقة' : 'إظهار الطبقة', onclick: () => toggleVisible(l.id) }, l.visible ? '👁' : '🚫'),
      h('button', { class: 'icon-btn', type: 'button', 'aria-label': 'نسخ الطبقة', onclick: () => duplicateLayer(l.id) }, '⧉'),
      h('button', { class: 'icon-btn danger', type: 'button', 'aria-label': 'حذف الطبقة', onclick: () => removeLayer(l.id) }, '🗑')),
    h('div', { class: 'chips' }, tabs.map(([k, label]) =>
      h('button', { type: 'button', class: 'chip-tab' + (k === tab ? ' on' : ''), onclick: () => select(l.id, k) }, label))),
    h('div', { class: 'insp-body' }, body));
}

// ── Wave ────────────────────────────────────────────────────────────────────
function waveBody(l, tab, set, live) {
  const caps = SHAPE_CAPS[l.shape] || [];
  const has = (c) => caps.includes(c);
  const out = [];
  if (tab === 'main') {
    out.push(h('div', { class: 'shape-grid' }, WAVE_SHAPES.map((s) =>
      h('button', { type: 'button', class: 'shape-card' + (s === l.shape ? ' on' : ''), onclick: () => set({ shape: s }) },
        shapeIcon(s), h('span', {}, SHAPE_LABELS[s])))));
    out.push(h('div', { class: 'hint' }, 'كل شكل يُرسم بطريقة مختلفة. تظهر في الألسنة الأخرى الإعدادات التي يدعمها الشكل المحدد فقط.'));
  }
  if (tab === 'color') {
    out.push(row('نمط اللون', seg(['manual', 'adaptive'], l.colorMode, (v) => set({ colorMode: v }), { manual: 'يدوي', adaptive: 'من الصورة' })));
    if (l.colorMode === 'manual') out.push(row('اللون', colorInput(l.color, (v, isLive) => (isLive ? live('color')({ color: v }) : set({ color: v }, { key: 'l:' + l.id + ':color' })))));
    else out.push(h('div', { class: 'hint' }, 'يُؤخذ اللون من صورة الخلفية (يحتاج صورة).'));
    if (has('gradient')) {
      out.push(row('تدرج لوني', toggle(l.gradient, (v) => set({ gradient: v }), ['تشغيل', 'إيقاف'])));
      if (l.gradient) out.push(row('اللون الثاني', colorInput(l.color2, (v, isLive) => (isLive ? live('color2')({ color2: v }) : set({ color2: v }, { key: 'l:' + l.id + ':color2' })))));
    }
    if (has('opacity')) out.push(row('الشفافية', slider({ min: 0, max: 100, value: Math.round(l.opacity * 100), fmt: (v) => v + '%', onLive: (v) => live('opacity')({ opacity: v / 100 }) })));
    if (has('glow')) out.push(row('التوهج', slider({ min: 0, max: 100, value: Math.round(l.glow * 100), fmt: (v) => v + '%', onLive: (v) => live('glow')({ glow: v / 100 }) })));
  }
  if (tab === 'place') {
    if (has('center')) {
      out.push(row('الحجم', slider({ min: 8, max: 45, value: Math.round(l.size * 100), fmt: (v) => v + '%', onLive: (v) => live('size')({ size: v / 100 }) })));
      out.push(row('أفقيًا', slider({ min: 0, max: 100, value: Math.round(l.cx * 100), fmt: (v) => v + '%', onLive: (v) => live('cx')({ cx: v / 100 }) })));
      out.push(row('عموديًا', slider({ min: 0, max: 100, value: Math.round(l.cy * 100), fmt: (v) => v + '%', onLive: (v) => live('cy')({ cy: v / 100 }) })));
    }
    if (has('position')) {
      out.push(row('الموضع', seg(['top', 'center', 'bottom', 'custom'], l.position, (v) => set({ position: v }), POS_LABELS)));
      if (l.position === 'custom') out.push(row('الارتفاع', slider({ min: 0, max: 100, value: Math.round(l.customY * 100), fmt: (v) => v + '%', onLive: (v) => live('customY')({ customY: v / 100 }) })));
    }
    if (has('height')) out.push(row('الحجم', slider({ min: 5, max: 50, value: Math.round(l.height * 100), fmt: (v) => v + '%', onLive: (v) => live('height')({ height: v / 100 }) })));
    if (has('thickness')) out.push(row('السماكة', slider({ min: 40, max: 300, value: Math.round(l.thickness * 100), fmt: (v) => (v / 100).toFixed(1) + '×', onLive: (v) => live('thickness')({ thickness: v / 100 }) })));
  }
  if (tab === 'react') {
    out.push(row('التفاعل مع الصوت', toggle(l.reactive, (v) => set({ reactive: v }))));
    out.push(row('يتبع', seg(['volume', 'bass', 'mid', 'treble'], l.reactTo, (v) => set({ reactTo: v }), SRC_LABELS)));
    if (has('intensity')) out.push(row('الشدة', slider({ min: 0, max: 200, value: Math.round(l.intensity * 100), fmt: (v) => v + '%', onLive: (v) => live('intensity')({ intensity: v / 100 }) })));
    if (has('speed')) out.push(row('سرعة الحركة', slider({ min: 0, max: 200, value: Math.round(l.speed * 100), fmt: (v) => (v / 100).toFixed(1) + '×', onLive: (v) => live('speed')({ speed: v / 100 }) })));
    if (['neonBars', 'spectrumMirror', 'dots', 'radial', 'ring'].includes(l.shape)) {
      out.push(h('div', { class: 'hint' }, 'عند اختيار «مستوى الصوت» يُوزَّع الجهير ← المتوسط ← الحاد على مواضع الشكل: تقريب من 3 نطاقات وليس FFT حقيقيًا.'));
    }
  }
  if (tab === 'time') out.push(...timingRows(l, set, live));
  return out;
}

// ── Text ────────────────────────────────────────────────────────────────────
function textBody(l, tab, set, live) {
  const out = [];
  if (tab === 'main') {
    const ta = h('textarea', { class: 'textarea', rows: 3, dir: 'auto', maxlength: 600, placeholder: 'اكتب النص هنا…' }, l.text);
    ta.addEventListener('input', () => live('text')({ text: ta.value }));
    out.push(ta);
    out.push(row('الخط', selectEl(TEXT_FONTS, FONT_LABELS, l.font, (v) => set({ font: v }))));
    out.push(h('div', { class: 'row' }, h('span', { class: 'label' }, 'النمط'),
      h('div', { class: 'seg' },
        h('button', { type: 'button', class: l.bold ? 'on' : '', onclick: () => set({ bold: !l.bold }) }, 'عريض'),
        h('button', { type: 'button', class: l.italic ? 'on' : '', onclick: () => set({ italic: !l.italic }) }, 'مائل'))));
    out.push(row('الحجم', slider({ min: 2, max: 25, value: Math.round(l.size * 100), fmt: (v) => v + '%', onLive: (v) => live('size')({ size: v / 100 }) })));
    out.push(row('اللون', colorInput(l.color, (v, isLive) => (isLive ? live('color')({ color: v }) : set({ color: v }, { key: 'l:' + l.id + ':color' })))));
    out.push(row('المحاذاة', seg(['right', 'center', 'left'], l.align, (v) => set({ align: v }), ALIGN_LABELS)));
  }
  if (tab === 'place') {
    out.push(h('div', { class: 'pos-grid', dir: 'ltr' }, [0.15, 0.5, 0.85].flatMap((y) => [0.2, 0.5, 0.8].map((x) =>
      h('button', { type: 'button', class: 'pos-cell' + (Math.abs(l.x - x) < 0.02 && Math.abs(l.y - y) < 0.02 ? ' on' : ''), 'aria-label': 'موضع سريع',
        onclick: () => set({ x, y, align: x < 0.3 ? 'left' : x > 0.7 ? 'right' : 'center' }) }, h('i', {})))))); 
    out.push(row('أفقيًا', slider({ min: 0, max: 100, value: Math.round(l.x * 100), fmt: (v) => v + '%', onLive: (v) => live('x')({ x: v / 100 }) })));
    out.push(row('عموديًا', slider({ min: 0, max: 100, value: Math.round(l.y * 100), fmt: (v) => v + '%', onLive: (v) => live('y')({ y: v / 100 }) })));
    out.push(row('عرض النص', slider({ min: 20, max: 100, value: Math.round(l.maxWidth * 100), fmt: (v) => v + '%', onLive: (v) => live('maxWidth')({ maxWidth: v / 100 }) })));
    out.push(h('div', { class: 'hint' }, 'يبقى النص داخل الإطار دائمًا، حتى لو اقترب الموضع من الحافة.'));
  }
  if (tab === 'anim') {
    out.push(row('طريقة الظهور', seg(['line', 'words', 'chars'], l.reveal, (v) => set({ reveal: v }), REVEAL_LABELS)));
    out.push(row('حركة الدخول', selectEl(TEXT_ANIMS, ANIM_LABELS, l.animIn, (v) => set({ animIn: v }))));
    out.push(row('حركة الخروج', selectEl(TEXT_ANIMS, ANIM_LABELS, l.animOut, (v) => set({ animOut: v }))));
    out.push(row('مدة الدخول', slider({ min: 0, max: 2000, step: 50, value: l.inMs, fmt: (v) => (v / 1000).toFixed(2) + ' ث', onLive: (v) => live('inMs')({ inMs: v }) })));
    out.push(row('مدة الخروج', slider({ min: 0, max: 2000, step: 50, value: l.outMs, fmt: (v) => (v / 1000).toFixed(2) + ' ث', onLive: (v) => live('outMs')({ outMs: v }) })));
    if (l.reveal !== 'line') out.push(row('مدة الكشف', slider({ min: 0, max: 10000, step: 100, value: l.revealMs, fmt: (v) => (v === 0 ? 'تلقائي' : (v / 1000).toFixed(1) + ' ث'), onLive: (v) => live('revealMs')({ revealMs: v }) })));
  }
  if (tab === 'style') {
    out.push(row('ظل', toggle(l.shadow, (v) => set({ shadow: v }))));
    out.push(row('حدود', toggle(l.outline, (v) => set({ outline: v }))));
    if (l.outline) out.push(row('لون الحدود', colorInput(l.outlineColor, (v, isLive) => (isLive ? live('outlineColor')({ outlineColor: v }) : set({ outlineColor: v }, { key: 'l:' + l.id + ':oc' })))));
    out.push(row('خلفية خلف النص', toggle(l.box, (v) => set({ box: v }))));
    if (l.box) {
      out.push(row('لون الخلفية', colorInput(l.boxColor, (v, isLive) => (isLive ? live('boxColor')({ boxColor: v }) : set({ boxColor: v }, { key: 'l:' + l.id + ':bc' })))));
      out.push(row('شفافية الخلفية', slider({ min: 0, max: 100, value: Math.round(l.boxOpacity * 100), fmt: (v) => v + '%', onLive: (v) => live('boxOpacity')({ boxOpacity: v / 100 }) })));
    }
    out.push(row('تباعد الأسطر', slider({ min: 80, max: 200, value: Math.round(l.lineSpacing * 100), fmt: (v) => (v / 100).toFixed(1) + '×', onLive: (v) => live('lineSpacing')({ lineSpacing: v / 100 }) })));
    out.push(row('الشفافية', slider({ min: 0, max: 100, value: Math.round(l.opacity * 100), fmt: (v) => v + '%', onLive: (v) => live('opacity')({ opacity: v / 100 }) })));
  }
  if (tab === 'time') out.push(...timingRows(l, set, live));
  return out;
}

// ── Timing (manual, for every layer) ────────────────────────────────────────
function timingRows(l, set, live) {
  const dur = durationMs();
  if (dur <= 0) return [h('div', { class: 'hint' }, 'أضف ملفًا صوتيًا أولًا لتتمكن من ضبط التوقيت.')];
  const end = l.endMs > 0 ? l.endMs : 0;
  const pos = Math.round(positionMs() / 100) * 100;
  const minGap = 500;
  const rows = [
    h('div', { class: 'hint' }, 'التوقيت يدوي: حرّك الشريحتين، أو شغّل الأغنية وانقر «من هنا» عند اللحظة المطلوبة.'),
    row('يبدأ عند', slider({ min: 0, max: dur, step: 100, value: Math.min(l.startMs, dur), fmt: clock,
      onLive: (v) => live('start')({ startMs: v, ...(end > 0 && end < v + minGap ? { endMs: Math.min(dur, v + minGap) } : {}) }) })),
    row('ينتهي عند', slider({ min: 0, max: dur, step: 100, value: Math.min(end, dur), fmt: (v) => (v === 0 ? 'نهاية الأغنية' : clock(v)),
      onLive: (v) => live('end')({ endMs: v === 0 ? 0 : Math.max(v, l.startMs + minGap) }) })),
    h('div', { class: 'btn-row' },
      h('button', { class: 'btn', type: 'button', onclick: () => set({ startMs: pos, ...(end > 0 && end < pos + minGap ? { endMs: Math.min(dur, pos + minGap) } : {}) }) }, 'يبدأ من هنا'),
      h('button', { class: 'btn', type: 'button', onclick: () => set({ endMs: Math.min(dur, Math.max(pos, l.startMs + minGap)) }) }, 'ينتهي هنا'),
      h('button', { class: 'btn', type: 'button', onclick: () => set({ startMs: 0, endMs: 0 }) }, 'طوال الأغنية')),
  ];
  return rows;
}
