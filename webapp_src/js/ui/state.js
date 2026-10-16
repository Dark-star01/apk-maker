// App state + debounced autosave. UI modules subscribe to changes; they never write
// to the store directly.
import { store } from '../core/store.js';
import { toast } from './dom.js';
import { MSG } from './messages.js';
import { resetHistory } from '../core/history.js';

export const state = {
  project: null,
  tab: 'layers', // dock: layers | templates | media | motion
  sel: null, // selected layer id (null = layer list)
  insp: 'main', // inspector sub-tab of the selected layer
  // Volatile (never saved) media status for the open project:
  busy: { audio: false, image: false }, // an import is in progress
  mediaOk: { audio: true, background: true }, // false => referenced file is missing on disk
  thumb: null, // { file, url } data URL of the background thumbnail
  play: freshPlay(), // playback mirror of the native AudioEngine (see ui/playback.js)
  wave: freshWave(), // analysis state + loaded wave data (see ui/analysis.js)
  pv: { status: 'none', error: null, info: null }, // native preview renderer (see ui/nativePreview.js)
  fx: freshFx(), // last EffectState received from native (see ui/effects.js)
};

export function freshWave() {
  // status: none | loading | analyzing | ready | error
  return { status: 'none', progress: 0, data: null, error: null };
}

export function freshFx() {
  return { values: null, error: null, busy: false, at: 0, sig: null, pos: 0, dur: 0 };
}

export function freshPlay() {
  // status: idle | loading | ready | playing | paused | ended | error
  return { status: 'idle', positionMs: 0, durationMs: 0, at: 0, error: null };
}

const listeners = new Set();
export function subscribe(fn) { listeners.add(fn); return () => listeners.delete(fn); }
export function emit() { listeners.forEach((fn) => fn(state)); }

// "Light" updates: the project changed but the controls on screen must NOT be rebuilt (a slider is being dragged,
// text is being typed). Subscribers here refresh only what depends on the data (native preview, timeline bars).
const lightListeners = new Set();
export function subscribeLight(fn) { lightListeners.add(fn); return () => lightListeners.delete(fn); }
export function emitLight() { lightListeners.forEach((fn) => fn(state)); }

let saveTimer = 0;
let saveWarned = false;
let dirty = false;

export async function flushSave() { await saveNow(); }

async function saveNow() {
  clearTimeout(saveTimer);
  if (!state.project || !dirty) return;
  dirty = false;
  const ok = await store.save(state.project);
  if (!ok && !saveWarned) {
    saveWarned = true;
    toast(MSG.saveFailed);
  }
}

export function scheduleSave() {
  dirty = true;
  clearTimeout(saveTimer);
  saveTimer = setTimeout(saveNow, 300);
}

// Don't lose the last edit if the app is backgrounded or closed within the debounce window.
// (localStorage writes inside store.save run synchronously, so this is safe in pagehide.)
document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'hidden') saveNow(); });
window.addEventListener('pagehide', saveNow);

export function setProject(project) {
  const same = !!state.project && state.project.id === project.id;
  state.project = project;
  state.busy = { audio: false, image: false };
  state.mediaOk = { audio: true, background: true };
  state.thumb = null;
  if (!same) { state.play = freshPlay(); state.wave = freshWave(); state.fx = freshFx(); state.sel = null; state.insp = 'main'; resetHistory(project); }
  emit();
}

// All project edits go through here so saving + re-rendering can't be forgotten.
export function mutate(fn) {
  if (!state.project) return;
  fn(state.project);
  state.project.updatedAt = Date.now();
  scheduleSave();
  emit();
}

export function setTab(tab) {
  state.tab = tab;
  state.sel = null;
  emit();
}

export function select(id, insp = 'main') {
  state.sel = id;
  state.insp = insp;
  emit();
}

// Like mutate() but without re-rendering the controls (see emitLight).
export function touch(fn) {
  if (!state.project) return;
  fn(state.project);
  state.project.updatedAt = Date.now();
  scheduleSave();
  emitLight();
}

export function isBusy() {
  return state.busy.audio || state.busy.image;
}
