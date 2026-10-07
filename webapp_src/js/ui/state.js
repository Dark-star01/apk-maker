// App state + debounced autosave. UI modules subscribe to changes; they never write
// to the store directly.
import { store } from '../core/store.js';
import { toast } from './dom.js';
import { MSG } from './messages.js';

export const state = {
  project: null,
  tab: 'media',
  // Volatile (never saved) media status for the open project:
  busy: { audio: false, image: false }, // an import is in progress
  mediaOk: { audio: true, background: true }, // false => referenced file is missing on disk
  thumb: null, // { file, url } data URL of the background thumbnail
  play: freshPlay(), // playback mirror of the native AudioEngine (see ui/playback.js)
};

export function freshPlay() {
  // status: idle | loading | ready | playing | paused | ended | error
  return { status: 'idle', positionMs: 0, durationMs: 0, at: 0, error: null };
}

const listeners = new Set();
export function subscribe(fn) { listeners.add(fn); return () => listeners.delete(fn); }
export function emit() { listeners.forEach((fn) => fn(state)); }

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
  state.project = project;
  state.busy = { audio: false, image: false };
  state.mediaOk = { audio: true, background: true };
  state.thumb = null;
  state.play = freshPlay();
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
  emit();
}

export function isBusy() {
  return state.busy.audio || state.busy.image;
}
