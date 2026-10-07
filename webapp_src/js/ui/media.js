// Media actions: pick audio/background, verify the files still exist, load the thumbnail.
// UI modules call these; they talk to Kotlin only through bridge.js.
import { bridge } from '../core/bridge.js';
import { sanitizeAudio, sanitizeBackground } from '../core/schema.js';
import { state, mutate, emit } from './state.js';
import { toast } from './dom.js';
import { errorMessage } from './messages.js';

const FIELD = { audio: 'audio', image: 'background' };

// kind: 'audio' | 'image'
export async function pick(kind) {
  const project = state.project;
  if (!project || state.busy[kind]) return;

  state.busy[kind] = true;
  emit();
  try {
    const res = await bridge.pickMedia(kind, project.id);
    if (res.cancelled) return; // user backed out of the picker: not an error
    if (!state.project || state.project.id !== project.id) return;

    if (kind === 'audio') {
      const audio = sanitizeAudio(res.media);
      if (!audio) throw Object.assign(new Error('Bad audio result'), { code: 'BAD_AUDIO' });
      mutate((p) => { p.media.audio = audio; });
    } else {
      const bg = sanitizeBackground(res.media);
      if (!bg) throw Object.assign(new Error('Bad image result'), { code: 'BAD_IMAGE' });
      mutate((p) => { p.media.background = bg; });
    }
    state.mediaOk[FIELD[kind]] = true;
    if (kind === 'image') await loadThumb(project.id);
  } catch (e) {
    // The previous file (if any) is untouched: native swaps files only after validation.
    toast(errorMessage(e));
  } finally {
    state.busy[kind] = false;
    emit();
  }
}

// Removes the audio or background from the project and deletes its files.
export async function remove(kind) {
  const project = state.project;
  if (!project || state.busy[kind]) return;
  if (!confirm(kind === 'audio' ? 'Remove the audio?' : 'Remove the background?')) return;
  mutate((p) => { if (kind === 'audio') p.media.audio = null; else p.media.background = null; });
  state.mediaOk[FIELD[kind]] = true;
  if (kind === 'image') state.thumb = null;
  emit();
  try { await bridge.removeMedia(project.id, kind); } catch (e) { /* leftover file is harmless; the project no longer references it */ }
}

// Called whenever a project is opened: flags missing files and loads the thumbnail.
export async function verify() {
  const project = state.project;
  if (!project) return;
  const { audio, background } = project.media;
  state.mediaOk = { audio: true, background: true };
  state.thumb = null;
  emit();

  if (!bridge.isNative() || (!audio && !background)) return; // nothing to check / can't check in a browser
  try {
    const r = await bridge.checkMedia(project.id, {
      audio: audio ? audio.file : undefined,
      background: background ? background.file : undefined,
    });
    if (!state.project || state.project.id !== project.id) return;
    state.mediaOk = {
      audio: !audio || r.audio !== false,
      background: !background || r.background !== false,
    };
    emit();
    if (background && state.mediaOk.background) await loadThumb(project.id);
  } catch (e) {
    // Unknown status: keep the optimistic default instead of blocking the user.
  }
}

async function loadThumb(projectId) {
  const project = state.project;
  if (!project || project.id !== projectId || !project.media.background || !project.media.background.thumb) return;
  const file = project.media.background.thumb;
  try {
    const url = await bridge.getThumbnail(projectId, file);
    if (!state.project || state.project.id !== projectId) return;
    state.thumb = url ? { file, url } : null;
  } catch (e) {
    state.thumb = null; // the thumbnail is cosmetic
  }
  emit();
}
