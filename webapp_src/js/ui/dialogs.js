// Bottom-sheet dialogs: project list, new project, project settings / diagnostics.
import { h, clear, $, toast } from './dom.js';
import { state, setProject } from './state.js';
import { store } from '../core/store.js';
import { bridge } from '../core/bridge.js';
import { MSG } from './messages.js';
import { ASPECTS, newProject, defaultWaveLayer, outputSize } from '../core/schema.js';
import { rendererLine, waveLine, rendererRepaint, runSelfTest } from './nativePreview.js';

export function closeSheet() {
  const o = $('#overlay');
  o.hidden = true;
  state.diagOpen = false;
  clear(o);
}

export function openSheet(...children) {
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

const ASPECT_NAMES = { '16:9': 'أفقي', '9:16': 'عمودي', '1:1': 'مربع' };

// ── New project ──
export function openNewProject({ cancelable = true } = {}) {
  let aspect = '16:9';
  const nameInput = h('input', { type: 'text', placeholder: 'اسم المشروع', maxlength: 60, value: '', dir: 'auto' });
  const cards = h('div', { class: 'aspect-grid' });

  const renderCards = () => {
    clear(cards);
    Object.keys(ASPECTS).forEach((key) => {
      cards.append(h('button', {
        type: 'button', class: 'aspect-card' + (key === aspect ? ' on' : ''),
        onclick: () => { aspect = key; renderCards(); },
      }, aspectShape(key), h('span', { dir: 'ltr' }, key), h('small', {}, ASPECT_NAMES[key])));
    });
  };
  renderCards();

  const create = async () => {
    const p = newProject({ name: nameInput.value.trim() || 'مشروع جديد', aspectRatio: aspect });
    p.scene.layers = [defaultWaveLayer('mirrored')]; // something to see as soon as media is added
    const ok = await store.save(p);
    if (!ok) toast(MSG.saveFailed);
    setProject(p);
    closeSheet();
  };

  openSheet(
    h('h2', {}, 'مشروع جديد'),
    nameInput,
    h('div', { class: 'hint', style: { marginTop: '12px' } }, 'أبعاد الفيديو'),
    cards,
    h('div', { class: 'actions' },
      cancelable ? h('button', { class: 'btn', type: 'button', onclick: closeSheet }, 'إلغاء') : null,
      h('button', { class: 'btn accent', type: 'button', onclick: create }, 'إنشاء')));
}

// ── Project list ──
export async function openProjects() {
  const items = await store.list();
  const list = h('div', {});
  if (!items.length) list.append(h('div', { class: 'hint' }, 'لا توجد مشاريع محفوظة.'));

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
      }, h('div', { dir: 'auto' }, it.name), h('small', { dir: 'ltr' }, it.aspectRatio)),
      h('button', {
        class: 'del', type: 'button', 'aria-label': 'حذف المشروع',
        onclick: async () => {
          if (!confirm(`حذف «${it.name}»؟`)) return;
          await store.remove(it.id);
          try { await bridge.deleteProject(it.id); } catch (e) { /* leftover files are harmless */ }
          if (state.project && state.project.id === it.id) {
            const rest = await store.list();
            const next = rest.length ? await store.load(rest[0].id) : null;
            if (next) setProject(next); else { closeSheet(); openNewProject({ cancelable: false }); return; }
          }
          openProjects();
        },
      }, 'حذف')));
  }

  openSheet(
    h('h2', {}, 'المشاريع'),
    list,
    h('div', { class: 'actions' },
      h('button', { class: 'btn', type: 'button', onclick: openSettings }, 'الإعدادات'),
      h('button', { class: 'btn accent', type: 'button', onclick: () => openNewProject() }, 'مشروع جديد')));
}

// ── Project settings + diagnostics ──
export function openSettings() {
  const p = state.project;
  const size = outputSize(p.aspectRatio, p.resolution);
  const nativeRow = h('span', { class: 'value', dir: 'auto' }, '…');
  bridge.getInfo().then((info) => {
    nativeRow.textContent = info.native ? `يعمل · Android ${info.sdk} · ${info.totalMemMb} MB` : 'غير متاح (متصفح)';
  }).catch((e) => { nativeRow.textContent = 'خطأ: ' + (e && e.message ? e.message : e); });
  const diagNative = h('div', { style: { whiteSpace: 'pre-wrap' } }, '…');
  bridge.nativeReport().then((t) => { diagNative.textContent = t; });
  const r = (label, v) => h('div', { class: 'row' }, h('span', { class: 'label' }, label), v);
  state.diagOpen = true;
  openSheet(
    h('h2', {}, 'إعدادات المشروع والتشخيص'),
    r('الأبعاد', h('span', { class: 'value', dir: 'ltr' }, p.aspectRatio)),
    r('حجم التصدير', h('span', { class: 'value', dir: 'ltr' }, `${p.resolution} · ${size.width}×${size.height} · ${p.fps} fps`)),
    r('المحرك الأصلي', nativeRow),
    h('div', { class: 'hint diag', 'data-rd': 'text', dir: 'ltr' }, rendererLine()),
    h('div', { class: 'hint diag', 'data-rd': 'wave', dir: 'ltr' }, waveLine()),
    h('div', { class: 'row' }, h('span', { class: 'hint' }, 'يرسم اللحظة نفسها مرتين ويقارن البكسلات'),
      h('button', { class: 'btn', type: 'button', onclick: runSelfTest }, 'اختبار المعاينة')),
    h('div', { class: 'hint diag', 'data-rd': 'self', dir: 'ltr' }, '—'),
    h('div', { class: 'row' }, h('span', { class: 'hint' }, 'يختبر الاتصال بين الواجهة والمحرك'),
      h('button', { class: 'btn', type: 'button', onclick: async () => {
        try { const x = await bridge.ping('hello'); toast(x.native ? `الجسر يعمل (${x.thread})` : 'لا يوجد محرك أصلي (متصفح)'); } catch (e) { toast('خطأ في الجسر: ' + (e && e.message ? e.message : e)); }
      } }, 'اختبار الجسر')),
    h('div', { class: 'hint diag', dir: 'ltr' }, bridge.diagnose().map((l) => h('div', {}, l)), diagNative),
    h('div', { class: 'actions' }, h('button', { class: 'btn', type: 'button', onclick: closeSheet }, 'إغلاق')));
  rendererRepaint();
}
