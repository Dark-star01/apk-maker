// Bottom sheets: add a layer, add lyrics, project settings / diagnostics.
import { h, $, toast } from './dom.js';
import { state } from './state.js';
import { openSheet, closeSheet } from './dialogs.js';
import { WAVE_SHAPES, MAX_LAYERS } from '../core/schema.js';
import { shapeIcon, textIcon, SHAPE_LABELS } from './icons.js';
import { addWave, addText, addLyrics, layers } from './sceneOps.js';
import { slider, clock } from './controls.js';
import { durationMs } from './playback.js';

export function openAddLayer() {
  const grid = h('div', { class: 'shape-grid' }, WAVE_SHAPES.map((s) =>
    h('button', { type: 'button', class: 'shape-card', onclick: () => { closeSheet(); addWave(s); } }, shapeIcon(s), h('span', {}, SHAPE_LABELS[s]))));
  openSheet(
    h('h2', {}, 'إضافة طبقة'),
    h('div', { class: 'hint' }, 'موجة صوتية'),
    grid,
    h('div', { class: 'hint', style: { marginTop: '14px' } }, 'نص'),
    h('div', { class: 'btn-row' },
      h('button', { class: 'btn', type: 'button', onclick: () => { closeSheet(); addText('نص جديد'); } }, h('span', { class: 'inline-icon' }, textIcon()), 'نص واحد'),
      h('button', { class: 'btn', type: 'button', onclick: () => { closeSheet(); openLyrics(); } }, 'كلمات أغنية (عدة أسطر)')),
    h('div', { class: 'actions' }, h('button', { class: 'btn', type: 'button', onclick: closeSheet }, 'إغلاق')));
}

export function openLyrics() {
  const dur = durationMs();
  let from = 0; let to = dur;
  const ta = h('textarea', { class: 'textarea', rows: 7, dir: 'auto', placeholder: 'اكتب كل سطر من الكلمات في سطر مستقل…' });
  const info = h('div', { class: 'hint' });
  const count = () => ta.value.split('\n').map((s) => s.trim()).filter(Boolean).length;
  const refresh = () => {
    const n = count();
    const per = n ? (Math.max(0, to - from) / n) / 1000 : 0;
    info.textContent = n ? `سيُنشأ ${n} سطر (طبقة نص لكل سطر)` + (dur > 0 ? `، نحو ${per.toFixed(1)} ث لكل سطر.` : '.') : 'لا توجد أسطر بعد.';
  };
  ta.addEventListener('input', refresh);
  const timing = dur > 0 ? [
    h('div', { class: 'row' }, h('span', { class: 'label' }, 'من'), slider({ min: 0, max: dur, step: 100, value: 0, fmt: clock, onLive: (v) => { from = v; if (to < from + 1000) to = Math.min(dur, from + 1000); refresh(); } })),
    h('div', { class: 'row' }, h('span', { class: 'label' }, 'إلى'), slider({ min: 0, max: dur, step: 100, value: dur, fmt: clock, onLive: (v) => { to = Math.max(v, from + 1000); refresh(); } })),
  ] : [h('div', { class: 'hint' }, 'أضف أغنية أولًا ليُوزَّع الوقت على مدتها (سيُستعمل الآن 4 ثوانٍ لكل سطر).')];
  if (dur <= 0) to = 0;
  refresh();
  const add = () => {
    const lines = ta.value.split('\n');
    const total = dur > 0 ? to : Math.max(1, lines.filter((s) => s.trim()).length) * 4000;
    const styleFrom = layers().filter((l) => l.type === 'text').pop();
    const r = addLyrics(lines, from, total, styleFrom);
    closeSheet();
    toast(r.added ? `أُضيف ${r.added} سطر` + (r.dropped ? ` (تجاوز الحد الأقصى ${MAX_LAYERS} طبقة، تُركت ${r.dropped})` : '') : 'لم يُضَف شيء');
  };
  openSheet(
    h('h2', {}, 'كلمات أغنية'),
    ta, info, ...timing,
    h('div', { class: 'hint' }, 'هذا توزيع متساوٍ يدوي وليس مزامنة تلقائية مع الغناء. بعد الإضافة اضبط توقيت كل سطر من تبويب «التوقيت».'),
    h('div', { class: 'actions' },
      h('button', { class: 'btn', type: 'button', onclick: closeSheet }, 'إلغاء'),
      h('button', { class: 'btn accent', type: 'button', onclick: add }, 'إضافة الأسطر')));
}
