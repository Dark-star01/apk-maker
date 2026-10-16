// Every edit of the scene goes through here: history, saving, and (for sliders/typing) light vs full re-render.
import { state, mutate, touch, emit, emitLight } from './state.js';
import { checkpoint, undo as hUndo, redo as hRedo } from '../core/history.js';
import { defaultWaveLayer, defaultTextLayer, sanitizeLayers, sanitizeLayer, newLayerId, MAX_LAYERS } from '../core/schema.js';
import { planTemplate } from '../core/templates.js';

export const layers = () => (state.project ? state.project.scene.layers : []);
export const layerById = (id) => layers().find((l) => l.id === id) || null;

// Structural change (add / remove / reorder / template): history + full re-render.
function structural(fn, key) {
  if (!state.project) return;
  checkpoint(state.project, key);
  mutate(fn);
}

// Value change on one layer. `key` merges repeated edits of one control into one undo step.
// light=true: do not rebuild the controls (used while dragging a slider / typing).
export function editLayer(id, patch, { key, light = false } = {}) {
  if (!state.project || !layerById(id)) return;
  checkpoint(state.project, key || ('layer:' + id + ':' + Object.keys(patch).join(',')));
  const apply = (p) => {
    const i = p.scene.layers.findIndex((l) => l.id === id);
    if (i < 0) return;
    const merged = sanitizeLayer({ ...p.scene.layers[i], ...patch, id });
    if (merged) p.scene.layers[i] = merged;
  };
  if (light) touch(apply); else mutate(apply);
}

export function addLayer(layer) {
  if (!state.project) return null;
  if (layers().length >= MAX_LAYERS) return null;
  const l = sanitizeLayer(layer);
  structural((p) => { p.scene.layers.push(l); }, null);
  state.sel = l.id; state.insp = 'main'; state.tab = 'layers';
  emit();
  return l;
}
export const addWave = (shape) => addLayer(defaultWaveLayer(shape));
export const addText = (text = '') => addLayer(defaultTextLayer(text));

export function removeLayer(id) {
  structural((p) => { p.scene.layers = p.scene.layers.filter((l) => l.id !== id); }, null);
  if (state.sel === id) { state.sel = null; emit(); }
}

export function duplicateLayer(id) {
  const src = layerById(id);
  if (!src || layers().length >= MAX_LAYERS) return null;
  const copy = sanitizeLayer({ ...src, id: newLayerId(src.type) });
  structural((p) => {
    const i = p.scene.layers.findIndex((l) => l.id === id);
    p.scene.layers.splice(i + 1, 0, copy);
  }, null);
  state.sel = copy.id; emit();
  return copy;
}

// dir: +1 = towards the top (drawn later = in front), -1 = towards the bottom
export function moveLayer(id, dir) {
  structural((p) => {
    const a = p.scene.layers; const i = a.findIndex((l) => l.id === id); const j = i + dir;
    if (i < 0 || j < 0 || j >= a.length) return;
    [a[i], a[j]] = [a[j], a[i]];
  }, null);
}

export function toggleVisible(id) { const l = layerById(id); if (l) editLayer(id, { visible: !l.visible }); }

export function editEffects(patch, { key, light = false } = {}) {
  if (!state.project) return;
  checkpoint(state.project, key || ('fx:' + Object.keys(patch).join(',')));
  const apply = (p) => { Object.assign(p.effects, patch); };
  if (light) touch(apply); else mutate(apply);
}

export function applyTemplate(tpl) {
  if (!state.project) return;
  const plan = planTemplate(state.project, tpl);
  structural((p) => { p.scene.layers = plan.layers; Object.assign(p.effects, plan.effects); p.templateId = tpl.id; }, null);
  state.sel = null; emit(); // stay on the templates dock: the preview is the feedback and another template is one tap away
}

// Lyrics: one text layer per non-empty line, spread evenly over [fromMs, toMs]. This is a MANUAL even split, not a sync.
export function addLyrics(lines, fromMs, toMs, styleFrom) {
  const clean = lines.map((s) => s.trim()).filter(Boolean);
  const room = MAX_LAYERS - layers().length;
  const use = clean.slice(0, Math.max(0, room));
  if (!use.length) return { added: 0, dropped: clean.length };
  const span = Math.max(1000 * use.length, toMs - fromMs);
  const per = span / use.length;
  const base = styleFrom ? { ...styleFrom } : defaultTextLayer();
  const made = use.map((t, i) => sanitizeLayer({ ...base, id: newLayerId('text'), text: t,
    startMs: Math.round(fromMs + i * per), endMs: Math.round(fromMs + (i + 1) * per) }));
  structural((p) => { p.scene.layers.push(...made); }, null);
  state.sel = null; emit();
  return { added: made.length, dropped: clean.length - use.length };
}

function restore(s) {
  state.project.scene = { layers: sanitizeLayers(s.scene && s.scene.layers) };
  Object.assign(state.project.effects, s.effects || {});
  state.project.templateId = s.templateId || null;
}
export function undo() { if (state.project && hUndo(state.project, (s) => mutate(() => restore(s)))) fixSelection(); }
export function redo() { if (state.project && hRedo(state.project, (s) => mutate(() => restore(s)))) fixSelection(); }
function fixSelection() { if (state.sel && !layerById(state.sel)) { state.sel = null; emit(); } }
export { emitLight };
