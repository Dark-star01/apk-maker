// Bottom-sheet dialogs: project list, new project, export options.
import { h, clear, $, toast } from './dom.js';
import { state, mutate, setProject } from './state.js';
import { store } from '../core/store.js';
import { bridge } from '../core/bridge.js';
import { MSG } from './messages.js';
import { ASPECTS, RESOLUTIONS, newProject, outputSize } from '../core/schema.js';

export function closeSheet() {
  const o = $('#overlay');
  o.hidden = true;
  clear(o);
}

function openSheet(...children) {
  const o = $('#overlay');
  clear(o);
  o.onclick = (e) => { if (e.target === o) closeSheet(); };
  o.append(h('div', { class: 'sheet' }, ...children));
  o.hidden = false;
}

function aspectShape(key) {
  const [w, hgt] = ASPECTS[key];
  const k = 40 / Math.max(w, hgt);
  return h('div', { class: 'shape', style: { width: w * k + 'px', height: hgt * k + 'px' } });
}

// ── New project ──
export function openNewProject({ cancelable = true } = {}) {
  let aspect = '16:9';
  const nameInput = h('input', { type: 'text', placeholder: 'Project name', maxlength: 60, value: '' });
  const cards = h('div', { class: 'aspect-grid' });

  const renderCards = () => {
    clear(cards);
    Object.keys(ASPECTS).forEach((key) => {
      cards.append(h('button', {
        type: 'button', class: 'aspect-card' + (key === aspect ? ' on' : ''),
        onclick: () => { aspect = key; renderCards(); },
      }, aspectShape(key), h('span', {}, key)));
    });
  };
  renderCards();

  const create = async () => {
    const p = newProject({ name: nameInput.value.trim() || 'Untitled', aspectRatio: aspect });
    const ok = await store.save(p);
    if (!ok) toast(MSG.saveFailed);
    setProject(p);
    closeSheet();
  };

  openSheet(
    h('h2', {}, 'New project'),
    nameInput,
    h('div', { class: 'hint', style: { marginTop: '12px' } }, 'Aspect ratio'),
    cards,
    h('div', { class: 'actions' },
      cancelable ? h('button', { class: 'btn', type: 'button', onclick: closeSheet }, 'Cancel') : null,
      h('button', { class: 'btn accent', type: 'button', onclick: create }, 'Create')));
}

// ── Project list ──
export async function openProjects() {
  const items = await store.list();
  const list = h('div', {});
  if (!items.length) list.append(h('div', { class: 'hint' }, 'No saved projects yet.'));

  for (const it of items) {
    list.append(h('div', { class: 'proj-item' },
      h('button', {
        class: 'open', type: 'button',
        onclick: async () => {
          const p = await store.load(it.id);
          if (!p) { toast(MSG.projectDamaged); return; }
          setProject(p);
          closeSheet();
        },
      }, h('div', {}, it.name), h('small', {}, it.aspectRatio)),
      h('button', {
        class: 'del', type: 'button', 'aria-label': 'Delete project',
        onclick: async () => {
          if (!confirm(`Delete "${it.name}"?`)) return;
          await store.remove(it.id);
          try { await bridge.deleteProject(it.id); } catch (e) { /* leftover files are harmless */ }
          if (state.project && state.project.id === it.id) {
            const rest = await store.list();
            const next = rest.length ? await store.load(rest[0].id) : null;
            if (next) setProject(next); else { closeSheet(); openNewProject({ cancelable: false }); return; }
          }
          openProjects();
        },
      }, 'Delete')));
  }

  openSheet(
    h('h2', {}, 'Projects'),
    list,
    h('div', { class: 'actions' },
      h('button', { class: 'btn', type: 'button', onclick: closeSheet }, 'Close'),
      h('button', { class: 'btn accent', type: 'button', onclick: () => openNewProject() }, 'New project')));
}

// ── Export options (the actual export arrives in a later phase) ──
export function openExport() {
  const p = state.project;
  const body = h('div', {});
  const render = () => {
    clear(body);
    const size = outputSize(p.aspectRatio, p.resolution);
    body.append(
      h('div', { class: 'hint' }, 'Resolution'),
      h('div', { class: 'seg' }, RESOLUTIONS.map((r) =>
        h('button', {
          type: 'button', class: r === p.resolution ? 'on' : '',
          onclick: () => { mutate((x) => { x.resolution = r; }); render(); },
        }, r))),
      h('div', { class: 'hint', style: { marginTop: '10px' } },
        `${p.aspectRatio} · ${size.width}×${size.height} · ${p.fps} fps · H.264 + AAC (MP4)`));
  };
  render();

  openSheet(
    h('h2', {}, 'Export'),
    body,
    h('div', { class: 'actions' },
      h('button', { class: 'btn', type: 'button', onclick: closeSheet }, 'Close'),
      h('button', { class: 'btn accent', type: 'button', onclick: () => toast(MSG.exportLater) }, 'Export')));
}
