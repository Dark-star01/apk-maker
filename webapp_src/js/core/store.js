// ProjectStore — local persistence behind a small async interface.
// Phase 1 keeps project JSON in localStorage. From Phase 2 on, this can be swapped for
// a native-backed store (project folder on disk) without touching the UI.
import { normalizeProject } from './schema.js';

const INDEX_KEY = 'mvm.index';
const LAST_KEY = 'mvm.last';
const projKey = (id) => 'mvm.project.' + id;

function read(key) {
  try { return localStorage.getItem(key); } catch (e) { return null; }
}
function write(key, value) {
  try { localStorage.setItem(key, value); return true; } catch (e) { return false; }
}
function remove(key) {
  try { localStorage.removeItem(key); } catch (e) { /* ignore */ }
}
function parse(text, fallback) {
  if (!text) return fallback;
  try { return JSON.parse(text); } catch (e) { return fallback; }
}

export const store = {
  async list() {
    const idx = parse(read(INDEX_KEY), []);
    return Array.isArray(idx) ? idx.sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0)) : [];
  },

  async load(id) {
    const raw = parse(read(projKey(id)), null);
    return raw ? normalizeProject(raw) : null;
  },

  // Returns false if storage failed (full/blocked) so the UI can tell the user.
  async save(project) {
    const ok = write(projKey(project.id), JSON.stringify(project));
    if (!ok) return false;
    const idx = parse(read(INDEX_KEY), []).filter((e) => e.id !== project.id);
    idx.push({ id: project.id, name: project.name, aspectRatio: project.aspectRatio, updatedAt: project.updatedAt });
    write(INDEX_KEY, JSON.stringify(idx));
    write(LAST_KEY, project.id);
    return true;
  },

  async remove(id) {
    remove(projKey(id));
    const idx = parse(read(INDEX_KEY), []).filter((e) => e.id !== id);
    write(INDEX_KEY, JSON.stringify(idx));
    if (read(LAST_KEY) === id) remove(LAST_KEY);
  },

  lastId() { return read(LAST_KEY); },
};
