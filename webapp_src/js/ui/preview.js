// Preview area. NOTE: the picture shown here from Phase 2 is only a static stand-in
// (the background thumbnail). From Phase 6 the native FrameRenderer view is placed over
// #preview and this element only provides its position/size.
import { $, h } from './dom.js';
import { state } from './state.js';
import { ASPECTS } from '../core/schema.js';

// Fit the preview frame (project aspect ratio) inside the stage area.
export function fitPreview() {
  if (!state.project) return;
  const stage = $('#stage');
  const frame = $('#preview');
  const [aw, ah] = ASPECTS[state.project.aspectRatio];
  const availW = stage.clientWidth - 24;
  const availH = stage.clientHeight - 8;
  if (availW <= 0 || availH <= 0) return;
  const k = Math.min(availW / aw, availH / ah);
  frame.style.width = Math.floor(aw * k) + 'px';
  frame.style.height = Math.floor(ah * k) + 'px';
}

export function renderPreview() {
  if (!state.project) return;
  const frame = $('#preview');
  const url = state.thumb ? state.thumb.url : null;
  frame.classList.toggle('has-image', !!url);

  // Only touch the DOM when the image actually changes (avoids flicker on every state change).
  const current = frame.querySelector('img');
  if (url && (!current || current.src !== url)) {
    if (current) current.remove();
    frame.prepend(h('img', { src: url, alt: '', draggable: 'false' }));
  } else if (!url && current) {
    current.remove();
  }
  $('#preview-info').textContent = state.project.aspectRatio;
}
