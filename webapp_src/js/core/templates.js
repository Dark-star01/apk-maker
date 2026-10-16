// Templates are DATA: a list of scene layers + effect settings. Applying one rewrites the project's scene; nothing here draws.
// (The card previews in the UI are schematic drawings generated from this same data, not renders of the real frame.)
import { defaultWaveLayer, defaultTextLayer, sanitizeLayers, newLayerId } from './schema.js';

const wave = (shape, o) => ({ ...defaultWaveLayer(shape), ...o });
const text = (t, o) => ({ ...defaultTextLayer(t), ...o });
const SAMPLE = 'اكتب كلمات أغنيتك هنا';

export const TEMPLATES = [
  {
    id: 'cinematic', name: 'سينمائي هادئ', desc: 'شريط موجي ناعم وحركة كاميرا بطيئة',
    effects: { preset: 'cinematic', motion: 'cinematicDrift', intensity: 0.8, smoothing: 0.7 },
    layers: [
      wave('ribbon', { position: 'center', height: 0.26, gradient: true, color: '#ffffff', color2: '#9db4ff', glow: 0.5, intensity: 0.9, thickness: 0.8, speed: 0.6 }),
      text(SAMPLE, { font: 'light', size: 0.065, y: 0.84, bold: false, animIn: 'fade', animOut: 'fade' }),
    ],
  },
  {
    id: 'lyrics', name: 'كلمات أنيقة', desc: 'كلمات تظهر كلمة بكلمة فوق خط متوهج',
    effects: { preset: 'subtle', motion: 'slowZoom', intensity: 0.7, smoothing: 0.6 },
    layers: [
      wave('glowLine', { position: 'bottom', height: 0.1, color: '#ffe3b3', glow: 0.4, intensity: 0.9 }),
      text(SAMPLE, { font: 'serif', size: 0.07, y: 0.48, animIn: 'slideUp', reveal: 'words', box: true, boxOpacity: 0.35, inMs: 500 }),
    ],
  },
  {
    id: 'rhythmic', name: 'إيقاعي حيوي', desc: 'طيف مرآة ملوّن ونبض يتبع الإيقاع',
    effects: { preset: 'beat', motion: 'pulseZoom', intensity: 1.3, smoothing: 0.35 },
    layers: [
      wave('spectrumMirror', { position: 'center', height: 0.34, gradient: true, color: '#ff4d6d', color2: '#ffd166', glow: 0.35, intensity: 1.2 }),
      text(SAMPLE, { font: 'condensed', size: 0.08, y: 0.14, animIn: 'pop', outline: true, shadow: false }),
    ],
  },
  {
    id: 'neon-circle', name: 'دائرة نيون', desc: 'حلقة أشعة تنبض بالصوت في وسط الصورة',
    effects: { preset: 'pulse', motion: 'float', intensity: 1, smoothing: 0.5 },
    layers: [
      wave('radial', { position: 'center', size: 0.2, gradient: true, color: '#00e5ff', color2: '#ff2bd6', glow: 0.8, thickness: 1.2 }),
      text(SAMPLE, { font: 'medium', size: 0.05, y: 0.9, animIn: 'fade' }),
    ],
  },
  {
    id: 'clean', name: 'نظيف وبسيط', desc: 'موجة بيضاء هادئة وعنوان صغير',
    effects: { preset: 'subtle', motion: 'static', intensity: 0.6, smoothing: 0.6 },
    layers: [
      wave('mirrored', { position: 'bottom', height: 0.14, color: '#ffffff' }),
      text(SAMPLE, { font: 'light', size: 0.05, y: 0.9, bold: false, animIn: 'fade' }),
    ],
  },
  {
    id: 'neon-bars', name: 'أعمدة نيون', desc: 'أعمدة مضيئة بتدرج أزرق وبنفسجي',
    effects: { preset: 'pulse', motion: 'slowZoom', intensity: 1, smoothing: 0.5 },
    layers: [
      wave('neonBars', { position: 'center', height: 0.3, gradient: true, color: '#4d7cff', color2: '#b04dff', glow: 0.7 }),
      text(SAMPLE, { font: 'sans', size: 0.06, y: 0.14, animIn: 'slideDown' }),
    ],
  },
  {
    id: 'glow-ring', name: 'حلقة متوهجة', desc: 'حلقة تتشكل مع الصوت ونقاط في الأسفل',
    effects: { preset: 'cinematic', motion: 'float', intensity: 0.9, smoothing: 0.6 },
    layers: [
      wave('ring', { position: 'center', size: 0.24, gradient: true, color: '#ffffff', color2: '#6c5ce7', glow: 0.8 }),
      wave('dots', { position: 'bottom', height: 0.1, color: '#a29bfe', glow: 0.3 }),
      text(SAMPLE, { font: 'light', size: 0.055, y: 0.5, bold: false, animIn: 'pop' }),
    ],
  },
  {
    id: 'experimental', name: 'تجريبي', desc: 'طبقات متعددة: أشعة وشريط ونقاط',
    effects: { preset: 'beat', motion: 'cinematicDrift', intensity: 1.1, smoothing: 0.45 },
    layers: [
      wave('ribbon', { position: 'top', height: 0.2, gradient: true, color: '#7bffea', color2: '#7b8cff', opacity: 0.8, speed: 1.4 }),
      wave('radial', { position: 'center', size: 0.18, gradient: true, color: '#ffe66d', color2: '#ff6b6b', glow: 0.6 }),
      wave('dots', { position: 'bottom', height: 0.1, color: '#ffffff' }),
    ],
  },
];

export function getTemplate(id) { return TEMPLATES.find((t) => t.id === id) || null; }

// Text keys that make up a "text style" (never the words or the timing).
const TEXT_STYLE = ['font', 'bold', 'italic', 'size', 'color', 'align', 'x', 'y', 'maxWidth', 'lineSpacing', 'animIn', 'animOut', 'reveal', 'easing',
  'inMs', 'outMs', 'revealMs', 'shadow', 'outline', 'outlineColor', 'box', 'boxColor', 'boxOpacity', 'opacity'];

// Returns the new scene layers for `project` + the effects to set. Rules (so a template never destroys the user's words):
//  - wave layers are replaced by the template's wave layers;
//  - the user's text layers stay (words + timing) and take the template's first text STYLE; with no text layers yet,
//    the template's sample text layer is added.
export function planTemplate(project, tpl) {
  const mine = project.scene.layers.filter((l) => l.type === 'text');
  const tplText = tpl.layers.find((l) => l.type === 'text');
  let texts;
  if (mine.length && tplText) {
    texts = mine.map((l) => { const n = { ...l }; for (const k of TEXT_STYLE) n[k] = tplText[k]; return n; });
  } else if (mine.length) texts = mine;
  else texts = tpl.layers.filter((l) => l.type === 'text');
  const waves = tpl.layers.filter((l) => l.type === 'wave');
  const fresh = (l) => ({ ...l, id: newLayerId(l.type) });
  // template order is kept: waves first (bottom), then text on top
  const layers = sanitizeLayers([...waves.map(fresh), ...texts.map((l) => (mine.includes(l) || mine.some((m) => m.id === l.id) ? l : fresh(l)))]);
  return { layers, effects: { ...tpl.effects } };
}
