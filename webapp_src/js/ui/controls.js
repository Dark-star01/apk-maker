// Reusable form controls for the inspector. Sliders call onLive() while dragging (light update) and onCommit() when released.
import { h } from './dom.js';

export function row(label, control, hint) {
  // option groups (segmented controls) get the full width under their label; sliders/selects stay inline
  const stack = control && control.classList && control.classList.contains('seg');
  return h('div', { class: 'row' + (stack ? ' stack' : '') }, h('span', { class: 'label' }, label), control, hint ? h('span', { class: 'hint-inline' }, hint) : null);
}

export function seg(options, current, onPick, labels = {}) {
  return h('div', { class: 'seg' }, options.map((o) =>
    h('button', { type: 'button', class: o === current ? 'on' : '', onclick: () => onPick(o) }, labels[o] || o)));
}

export function toggle(on, onChange, labels = ['تشغيل', 'إيقاف']) {
  return h('div', { class: 'seg' },
    h('button', { type: 'button', class: on ? 'on' : '', onclick: () => onChange(true) }, labels[0]),
    h('button', { type: 'button', class: !on ? 'on' : '', onclick: () => onChange(false) }, labels[1]));
}

export function select(options, labels, current, onPick) {
  return h('select', { onchange: (e) => onPick(e.target.value) },
    options.map((o) => h('option', { value: o, selected: o === current }, labels[o] || o)));
}

export function colorInput(value, onPick) {
  return h('input', { type: 'color', value, oninput: (e) => onPick(e.target.value, true), onchange: (e) => onPick(e.target.value, false) });
}

// field slider: value shown live; `to` converts the slider number to the stored value, `from` the opposite.
export function slider({ min, max, step = 1, value, fmt = (v) => String(v), onLive, onCommit, disabled = false }) {
  const out = h('span', { class: 'slider-val', dir: 'ltr' }, fmt(value));
  const input = h('input', { type: 'range', min, max, step, value, disabled });
  input.addEventListener('input', () => { const v = Number(input.value); out.textContent = fmt(v); if (onLive) onLive(v); });
  input.addEventListener('change', () => { if (onCommit) onCommit(Number(input.value)); });
  return h('div', { class: 'slider' }, input, out);
}

export const pct = (v) => Math.round(v * 100) + '%';
export const clock = (ms) => { const s = Math.max(0, ms) / 1000; const m = Math.floor(s / 60); return m + ':' + (s % 60).toFixed(1).padStart(4, '0'); };
