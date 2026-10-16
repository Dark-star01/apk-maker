// Small schematic icons, one per waveform shape, generated from numbers (static markup only; no user data ever goes in).
const W = 56; const H = 32; const CY = H / 2;
const f = (n) => n.toFixed(1);
const wrap = (inner) => `<svg viewBox="0 0 ${W} ${H}" width="100%" height="100%" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${inner}</svg>`;
const env = (i, n) => 0.25 + 0.75 * Math.abs(Math.sin(i * 1.7 + 0.6)) * (0.6 + 0.4 * Math.cos(i * 0.9));

function sine(amp, freq, phase) {
  let d = '';
  for (let x = 4; x <= W - 4; x += 2) {
    const u = (x - 4) / (W - 8);
    const y = CY - amp * Math.pow(Math.sin(Math.PI * u), 1.2) * Math.sin(2 * Math.PI * freq * u + phase);
    d += (d ? 'L' : 'M') + f(x) + ' ' + f(y);
  }
  return d;
}

const MAKERS = {
  mirrored: () => { let s = ''; for (let i = 0; i < 9; i++) { const x = 6 + i * 5.5; const h = 12 * env(i, 9); s += `<path d="M${f(x)} ${f(CY - h)}V${f(CY + h)}"/>`; } return s; },
  bars: () => { let s = ''; for (let i = 0; i < 9; i++) { const x = 6 + i * 5.5; const h = 22 * env(i, 9); s += `<path d="M${f(x)} ${H - 4}V${f(H - 4 - h)}"/>`; } return s; },
  line: () => { let d = ''; for (let i = 0; i < 14; i++) { const x = 4 + i * 3.8; const y = H - 6 - 20 * env(i, 14); d += (d ? 'L' : 'M') + f(x) + ' ' + f(y); } return `<path d="${d}"/>`; },
  neonBars: () => { let s = ''; for (let i = 0; i < 6; i++) { const x = 6 + i * 8.5; const h = 12 * env(i, 6); s += `<rect x="${f(x)}" y="${f(CY - h)}" width="5" height="${f(h * 2)}" rx="2.5"/>`; } return s; },
  spectrumMirror: () => { let s = ''; for (let i = 0; i < 11; i++) { const x = 5 + i * 4.6; const h = 11 * env(i, 11); s += `<path d="M${f(x)} ${CY - 1}V${f(CY - 1 - h)}"/><path opacity=".4" d="M${f(x)} ${CY + 1}V${f(CY + 1 + h * 0.55)}"/>`; } return s; },
  dots: () => { let s = ''; for (let i = 0; i < 8; i++) { const x = 6 + i * 6.3; const r = 1.3 + 3.4 * env(i, 8); s += `<circle cx="${f(x)}" cy="${CY}" r="${f(r)}" fill="currentColor" stroke="none"/>`; } return s; },
  radial: () => { let s = ''; const cx = W / 2; for (let i = 0; i < 16; i++) { const a = (i / 16) * Math.PI * 2; const r0 = 6; const r1 = 6 + 9 * env(i, 16); s += `<path d="M${f(cx + Math.cos(a) * r0)} ${f(CY + Math.sin(a) * r0)}L${f(cx + Math.cos(a) * r1)} ${f(CY + Math.sin(a) * r1)}"/>`; } return s; },
  ring: () => { let d = ''; const cx = W / 2; for (let i = 0; i <= 32; i++) { const a = (i / 32) * Math.PI * 2; const r = 9 + 3.5 * env(i % 32, 32); d += (i ? 'L' : 'M') + f(cx + Math.cos(a) * r) + ' ' + f(CY + Math.sin(a) * r); } return `<path d="${d}Z"/>`; },
  ribbon: () => `<path d="${sine(10, 1.2, 0)}"/><path opacity=".65" d="${sine(8, 1.9, 1.7)}"/><path opacity=".35" d="${sine(6, 2.6, 3.4)}"/>`,
  glowLine: () => `<path opacity=".3" stroke-width="5" d="${sine(11, 3, 0)}"/><path d="${sine(11, 3, 0)}"/>`,
};

export function shapeIcon(shape) {
  const el = document.createElement('span');
  el.className = 'shape-icon';
  const make = MAKERS[shape] || MAKERS.mirrored;
  el.innerHTML = wrap(make()); // static, generated above
  return el;
}

export const SHAPE_LABELS = {
  mirrored: 'متناظر', bars: 'أعمدة', line: 'خط', neonBars: 'أعمدة نيون', spectrumMirror: 'طيف مرآة',
  dots: 'نقاط', radial: 'دائري', ring: 'حلقة', ribbon: 'شريط', glowLine: 'خط متوهج',
};

export function textIcon() {
  const el = document.createElement('span');
  el.className = 'shape-icon';
  el.innerHTML = wrap('<path d="M16 9H40M28 9V25M23 25H33"/>');
  return el;
}
