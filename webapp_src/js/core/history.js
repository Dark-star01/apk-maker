// Undo / redo for the parts of the project the editor changes (scene, effects, template). Snapshots are JSON strings.
// Small edits of the same control are merged into one step (sliders, typing) with a coalesce key.
const LIMIT = 60;
const past = [];
const future = [];
let lastKey = null;
let lastAt = 0;
let current = null; // snapshot of the state we are in now (so undo can push it onto `future`)

export const snap = (p) => JSON.stringify({ scene: p.scene, effects: p.effects, templateId: p.templateId });

export function resetHistory(project) { past.length = 0; future.length = 0; lastKey = null; current = project ? snap(project) : null; }

// Call BEFORE changing the project. Returns nothing; merges with the previous step when the key repeats within 900 ms.
export function checkpoint(project, key) {
  const now = Date.now();
  const s = snap(project);
  if (key && key === lastKey && now - lastAt < 900) { lastAt = now; return; }
  lastKey = key || null; lastAt = now;
  if (past.length && past[past.length - 1] === s) return;
  past.push(s);
  if (past.length > LIMIT) past.shift();
  future.length = 0;
}

export const canUndo = () => past.length > 0;
export const canRedo = () => future.length > 0;

// `apply(snapshotObject)` writes a snapshot into the project. `now` is the project's CURRENT snapshot string.
export function undo(project, apply) {
  if (!past.length) return false;
  future.push(snap(project));
  apply(JSON.parse(past.pop()));
  lastKey = null;
  return true;
}
export function redo(project, apply) {
  if (!future.length) return false;
  past.push(snap(project));
  apply(JSON.parse(future.pop()));
  lastKey = null;
  return true;
}
