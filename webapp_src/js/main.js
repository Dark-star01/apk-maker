// App bootstrap: wires state -> views and the static controls.
import { $, formatTime } from './ui/dom.js';
import { state, subscribe, setProject, setTab, isBusy } from './ui/state.js';
import { renderTracks, renderPanel, renderTabs } from './ui/panels.js';
import { renderPreview, fitPreview } from './ui/preview.js';
import { openNewProject, openProjects, openExport } from './ui/dialogs.js';
import { verify } from './ui/media.js';
import { store } from './core/store.js';

let lastProjectId = null;

function render() {
  if (!state.project) return;
  $('#project-name').textContent = state.project.name;
  // Project switching / export are locked while a file is being imported.
  const busy = isBusy();
  $('#btn-project').disabled = busy;
  $('#btn-export').disabled = busy;

  // Timeline values (the transport itself is enabled in Phase 3).
  const audio = state.project.media.audio;
  $('#time-current').textContent = formatTime(0);
  $('#time-total').textContent = formatTime(audio ? audio.durationMs : 0);

  renderTabs();
  renderTracks();
  renderPanel();
  renderPreview();
  fitPreview();

  // A different project was opened: check that its files still exist.
  if (state.project.id !== lastProjectId) {
    lastProjectId = state.project.id;
    verify();
  }
}

async function start() {
  subscribe(render);

  $('#btn-project').addEventListener('click', openProjects);
  $('#btn-export').addEventListener('click', () => { if (state.project) openExport(); });
  $('#tabs').addEventListener('click', (e) => {
    const b = e.target.closest('button[data-tab]');
    if (b) setTab(b.dataset.tab);
  });

  if (typeof ResizeObserver === 'function') new ResizeObserver(fitPreview).observe($('#stage'));
  window.addEventListener('resize', fitPreview);

  // Open the last project; if none (or it is damaged), ask for a new one.
  let project = null;
  const lastId = store.lastId();
  if (lastId) project = await store.load(lastId);
  if (!project) {
    const all = await store.list();
    for (const it of all) { project = await store.load(it.id); if (project) break; }
  }
  if (project) setProject(project);
  else openNewProject({ cancelable: false });
}

start();
