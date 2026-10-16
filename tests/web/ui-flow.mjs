// Drives the REAL web app (webapp_src, same ES modules the APK ships) inside jsdom: no mocks of app code, only a fake
// browser. Native calls are unavailable here (bridge.isNative() === false), so this proves UI -> project data, not native drawing.
import { JSDOM } from 'jsdom';
import { readFileSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';

const ROOT = process.env.WEBAPP_ROOT || path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../webapp_src');
const html = readFileSync(path.join(ROOT, 'index.html'), 'utf8').replace(/<script[^>]*><\/script>/, '');
const dom = new JSDOM(html, { url: 'https://app.local/', pretendToBeVisual: true });
const { window } = dom;
for (const k of ['window', 'document', 'localStorage', 'HTMLElement', 'Node', 'Event', 'MouseEvent', 'requestAnimationFrame', 'getComputedStyle']) {
  Object.defineProperty(globalThis, k, { value: k === 'window' ? window : window[k], configurable: true, writable: true });
}
Object.defineProperty(globalThis, 'navigator', { value: window.navigator, configurable: true });
window.confirm = () => true; globalThis.confirm = window.confirm;
window.HTMLCanvasElement.prototype.getContext = () => ({ clearRect() {}, fillRect() {}, fillStyle: '' });

let pass = 0; let fail = 0;
const ok = (name, cond, info = '') => { if (cond) pass++; else { fail++; console.log('FAIL:', name, info); } };
const tick = (ms = 30) => new Promise((r) => setTimeout(r, ms));
const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const click = (el) => el.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
const byText = (sel, t, root = document) => $$(sel, root).find((e) => e.textContent.includes(t));
const input = (el, v) => { el.value = v; el.dispatchEvent(new window.Event('input', { bubbles: true })); };
const change = (el, v) => { if (v !== undefined) el.value = v; el.dispatchEvent(new window.Event('change', { bubbles: true })); };

await import(pathToFileURL(path.join(ROOT, 'js/main.js')).href);
await tick(80);
const { state } = await import(pathToFileURL(path.join(ROOT, 'js/ui/state.js')).href);
const { projectPayload } = await import(pathToFileURL(path.join(ROOT, 'js/ui/nativePreview.js')).href);
const { normalizeProject, SCHEMA_VERSION } = await import(pathToFileURL(path.join(ROOT, 'js/core/schema.js')).href);

// 1. first start asks for a project
ok('new-project sheet opens on first start', !$('#overlay').hidden && $('.sheet h2').textContent === 'مشروع جديد');
ok('page is RTL Arabic', document.documentElement.dir === 'rtl' && document.documentElement.lang === 'ar');
input($('.sheet input[type=text]'), 'أغنيتي');
click(byText('.aspect-card', '9:16'));
click(byText('.sheet .btn', 'إنشاء')); await tick(80);
ok('project created', state.project && state.project.name === 'أغنيتي' && state.project.aspectRatio === '9:16');
ok('schema v2 with one starter wave', state.project.schemaVersion === SCHEMA_VERSION && state.project.scene.layers.length === 1 && state.project.scene.layers[0].shape === 'mirrored');
ok('sheet closed', $('#overlay').hidden);
ok('layer list shows the layer', $$('.layer-row').length === 1);
ok('timeline shows a bar for it', $$('.layer-bar').length === 1);
ok('project name in top bar', $('#project-name').textContent === 'أغنيتي');

// 2. add every wave shape through the real UI
const shapes = ['neonBars', 'spectrumMirror', 'dots', 'radial', 'ring', 'ribbon', 'glowLine', 'line', 'bars'];
for (const s of shapes) {
  click(byText('.panel-head .btn', 'إضافة')); await tick();
  const labels = { neonBars: 'أعمدة نيون', spectrumMirror: 'طيف مرآة', dots: 'نقاط', radial: 'دائري', ring: 'حلقة', ribbon: 'شريط', glowLine: 'خط متوهج', line: 'خط', bars: 'أعمدة' };
  const card = $$('.sheet .shape-card').find((c) => c.textContent.trim() === labels[s]);
  ok('add-sheet offers ' + s, !!card);
  click(card); await tick();
  const last = state.project.scene.layers.at(-1);
  ok('added shape ' + s, last.shape === s && state.sel === last.id);
  ok('inspector open for ' + s, !!$('.insp'));
  click(byText('.insp-head .btn', 'الطبقات')); await tick();   // back to the list
}
ok('10 layers total', state.project.scene.layers.length === 10);

// 3. inspector edits reach project.scene (the data that is sent to native)
click($$('.layer-main').find((b) => b.textContent.includes('دائري'))); await tick();
const radialId = state.sel;
click(byText('.chip-tab', 'الحجم')); await tick();
const sizeRange = $$('.row', $('.insp-body')).find((r) => r.textContent.includes('الحجم')).querySelector('input[type=range]');
const bodyBefore = $('.insp-body');
input(sizeRange, 40); await tick(40);
const rl = state.project.scene.layers.find((l) => l.id === radialId);
ok('slider writes the layer (size 0.40)', Math.abs(rl.size - 0.4) < 1e-9, String(rl.size));
ok('slider does NOT rebuild the controls (no lost drag)', $('.insp-body') === bodyBefore);
click(byText('.chip-tab', 'اللون')); await tick();
click(byText('.insp-body .seg button', 'تشغيل')); await tick();  // gradient on
ok('gradient toggle writes layer', state.project.scene.layers.find((l) => l.id === radialId).gradient === true);
const caps = $$('.insp-body .row .label').map((e) => e.textContent);
ok('radial shows glow + gradient colour controls', caps.includes('التوهج') && caps.includes('اللون الثاني'));
click(byText('.chip-tab', 'التفاعل')); await tick();
click(byText('.insp-body .seg button', 'الجهير')); await tick();
ok('react-to bass written', state.project.scene.layers.find((l) => l.id === radialId).reactTo === 'bass');
// legacy shape hides controls native ignores
click(byText('.insp-head .btn', 'الطبقات')); await tick();
click($$('.layer-main').find((b) => b.textContent.includes('أعمدة') && !b.textContent.includes('نيون') ) ); await tick();
click(byText('.chip-tab', 'اللون')); await tick();
const lc = $$('.insp-body .row .label').map((e) => e.textContent);
ok('legacy shape offers no glow/gradient (native ignores them)', !lc.includes('التوهج') && !lc.includes('تدرج لوني'), lc.join(','));

// 4. text layer: typing, RTL, timing UI without audio
click(byText('.insp-head .btn', 'الطبقات')); await tick();
click(byText('.panel-head .btn', 'إضافة')); await tick();
click(byText('.sheet .btn', 'نص واحد')); await tick();
const tid = state.sel;
const ta = $('.insp-body textarea');
const taBefore = ta;
input(ta, 'مرحبا بالعالم'); await tick(40);
ok('typing writes text', state.project.scene.layers.find((l) => l.id === tid).text === 'مرحبا بالعالم');
ok('typing keeps the textarea (focus not lost)', $('.insp-body textarea') === taBefore);
ok('timeline label follows the typing', $$('.layer-bar .name').some((e) => e.textContent.includes('مرحبا')));
click(byText('.chip-tab', 'الظهور')); await tick();
click(byText('.insp-body .seg button', 'كلمة بكلمة')); await tick();
ok('reveal=words written', state.project.scene.layers.find((l) => l.id === tid).reveal === 'words');
click(byText('.chip-tab', 'التوقيت')); await tick();
ok('timing explains it needs audio', $('.insp-body').textContent.includes('أضف ملفًا صوتيًا'));

// 5. payload that goes to native Preview AND Export is the same scene
const payload = projectPayload(state.project);
ok('payload carries the scene with all layers', payload.scene.layers.length === 11 && payload.scene.layers.some((l) => l.type === 'text' && l.text === 'مرحبا بالعالم'));
ok('payload no longer sends the legacy wave', payload.wave === undefined);
ok('payload layers are the same objects Export gets (one function)', JSON.stringify(payload.scene.layers) === JSON.stringify(state.project.scene.layers));

// 6. undo / redo
const count = state.project.scene.layers.length;
click($('#btn-undo')); await tick();
ok('undo reverts the last step', state.project.scene.layers.find((l) => l.id === tid)?.reveal !== 'words' || state.project.scene.layers.length < count);
click($('#btn-redo')); await tick();
ok('redo re-applies', state.project.scene.layers.find((l) => l.id === tid)?.reveal === 'words');
// delete + undo
click(byText('.insp-head', '')); 
click($$('.insp-head .icon-btn').find((b) => b.getAttribute('aria-label') === 'حذف الطبقة')); await tick();
ok('delete removes layer', state.project.scene.layers.length === count - 1 && state.sel === null);
click($('#btn-undo')); await tick();
ok('undo restores deleted layer', state.project.scene.layers.length === count);

// 7. reorder + visibility
const ids = () => state.project.scene.layers.map((l) => l.id);
const before = ids();
const topRow = $$('.layer-row')[0];
click($$('.icon-btn', topRow).find((b) => b.getAttribute('aria-label') === 'للأسفل')); await tick();
ok('move down reorders (top layer goes one step lower)', ids()[count - 2] === before[count - 1] && ids()[count - 1] === before[count - 2]);
click($$('.icon-btn', $$('.layer-row')[0]).find((b) => ['إخفاء', 'إظهار'].includes(b.getAttribute('aria-label')))); await tick();
ok('visibility toggle writes layer', state.project.scene.layers.some((l) => l.visible === false));

// 8. templates: apply, keeps the user's words, restyles, effects change
click($('#tabs [data-tab=templates]')); await tick();
ok('8 template cards', $$('.tpl-card').length === 8);
const fxBefore = JSON.stringify(state.project.effects);
click(byText('.tpl-card', 'إيقاعي حيوي')); await tick();
ok('template applied', state.project.templateId === 'rhythmic');
ok('template effects applied', state.project.effects.preset === 'beat' && state.project.effects.motion === 'pulseZoom' && JSON.stringify(state.project.effects) !== fxBefore);
const wavesNow = state.project.scene.layers.filter((l) => l.type === 'wave');
ok('template replaced the waves (1 spectrumMirror)', wavesNow.length === 1 && wavesNow[0].shape === 'spectrumMirror');
const myText = state.project.scene.layers.find((l) => l.type === 'text');
ok('user words kept, template style applied', myText.text === 'مرحبا بالعالم' && myText.font === 'condensed' && myText.animIn === 'pop');
ok('exactly one text layer (no duplicate sample)', state.project.scene.layers.filter((l) => l.type === 'text').length === 1);
ok('card marked selected', $$('.tpl-card.on').length === 1);
click($('#btn-undo')); await tick();
ok('undo of a template restores the previous scene', state.project.templateId !== 'rhythmic' && state.project.scene.layers.length === count);

// 9. lyrics: one layer per line, even split (no audio => 4 s per line)
click($('#tabs [data-tab=layers]')); await tick();
click(byText('.panel-head .btn', 'إضافة')); await tick();
click(byText('.sheet .btn', 'كلمات أغنية')); await tick();
input($('.sheet textarea'), 'السطر الأول\nالسطر الثاني\n\nالسطر الثالث');
await tick();
ok('lyrics sheet counts non-empty lines', $('.sheet').textContent.includes('سيُنشأ 3 سطر'));
ok('lyrics sheet says it is not auto-sync', $('.sheet').textContent.includes('وليس مزامنة تلقائية'));
const nBefore = state.project.scene.layers.length;
click(byText('.sheet .btn', 'إضافة الأسطر')); await tick();
const added = state.project.scene.layers.slice(nBefore);
ok('3 lyric layers added', added.length === 3 && added.every((l) => l.type === 'text'));
ok('lyric timings are consecutive', added[0].startMs === 0 && added[0].endMs === added[1].startMs && added[1].endMs === added[2].startMs && added[2].endMs === 12000, JSON.stringify(added.map((l) => [l.startMs, l.endMs])));

// 10. media / motion docks
click($('#tabs [data-tab=media]')); await tick();
ok('media dock offers choosing audio + image', !!byText('#panel .btn', 'اختيار أغنية') && !!byText('#panel .btn', 'اختيار صورة'));
click($('#tabs [data-tab=motion]')); await tick();
const intensity = $$('#panel .row').find((r) => r.textContent.includes('الشدة')).querySelector('input[type=range]');
input(intensity, 150); await tick();
ok('motion slider writes effects', state.project.effects.intensity === 1.5);

// 11. persistence + migration
await new Promise((r) => setTimeout(r, 450));
const saved = JSON.parse(window.localStorage.getItem('mvm.project.' + state.project.id));
ok('project saved with scene', saved.schemaVersion === 2 && saved.scene.layers.length === state.project.scene.layers.length);
const v1 = { schemaVersion: 1, id: 'old', name: 'قديم', aspectRatio: '16:9', wave: { enabled: true, style: 'bars', colorMode: 'manual', color: '#ff0000', position: 'custom', customY: 0.3, reactive: false, reactTo: 'bass', height: 0.25 }, effects: { preset: 'beat', motion: 'float', intensity: 1.4, smoothing: 0.2, seed: 7 } };
const mig = normalizeProject(v1);
ok('v1 migrates to v2', mig.schemaVersion === 2 && mig.scene.layers.length === 1);
const w = mig.scene.layers[0];
ok('v1 wave becomes the same-looking layer', w.shape === 'bars' && w.color === '#ff0000' && w.position === 'custom' && w.customY === 0.3 && w.height === 0.25 && w.reactive === false && w.reactTo === 'bass');
ok('v1 effects untouched', mig.effects.preset === 'beat' && mig.effects.seed === 7);
ok('v1 legacy wave key preserved', mig.wave.style === 'bars');
ok('v1 with wave disabled => no layers', normalizeProject({ ...v1, wave: { ...v1.wave, enabled: false } }).scene.layers.length === 0);
ok('v2 round trip is stable', JSON.stringify(normalizeProject(JSON.parse(JSON.stringify(mig))).scene) === JSON.stringify(mig.scene));
const junk = normalizeProject({ scene: { layers: [{ type: 'text', text: 'x'.repeat(900), size: 9, animIn: 'zzz' }, { type: 'alien' }, 5, null, { type: 'wave', shape: 'nope', glow: -4 }] } });
ok('garbage layers are sanitized', junk.scene.layers.length === 2 && junk.scene.layers[0].text.length === 600 && junk.scene.layers[0].size === 0.25 && junk.scene.layers[0].animIn === 'fade' && junk.scene.layers[1].shape === 'mirrored' && junk.scene.layers[1].glow === 0);
ok('layer cap 160', normalizeProject({ scene: { layers: Array.from({ length: 400 }, () => ({ type: 'text', text: 'a' })) } }).scene.layers.length === 160);

console.log(`web flow: passed=${pass} failed=${fail}`);
process.exit(fail ? 1 : 0);
