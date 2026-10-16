// Writes the scene payloads the web app would send to native (Preview and Export use the same function) to a JSON file,
// so the Kotlin side can parse and render exactly what the UI produces.
import { writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
const ROOT = process.env.WEBAPP_ROOT || path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../webapp_src');
const m = (f) => import(pathToFileURL(path.join(ROOT, f)).href);
const { normalizeProject, defaultWaveLayer, defaultTextLayer, WAVE_SHAPES, sanitizeLayers } = await m('js/core/schema.js');
const { TEMPLATES, planTemplate } = await m('js/core/templates.js');

const out = [];
for (const t of TEMPLATES) {
  const p = normalizeProject({ scene: { layers: [] } });
  const plan = planTemplate(p, t);
  out.push({ name: 'template:' + t.id, layers: plan.layers });
}
out.push({ name: 'every-shape', layers: sanitizeLayers(WAVE_SHAPES.map((s) => ({ ...defaultWaveLayer(s), gradient: true, glow: 0.7, thickness: 1.7, intensity: 1.3, speed: 1.4, size: 0.3, cx: 0.3, cy: 0.6, opacity: 0.8 }))) });
const ar = { ...defaultTextLayer('مرحبا بالعالم الجميل'), reveal: 'words', animIn: 'slideUp', font: 'serif', box: true, outline: true, startMs: 1000, endMs: 6000, x: 0.3, y: 0.7, align: 'right', size: 0.08, color: '#ffcc00' };
out.push({ name: 'text-mix', layers: sanitizeLayers([ar, { ...defaultTextLayer('Hello world again'), reveal: 'chars', animIn: 'pop', animOut: 'slideDown', startMs: 0, endMs: 9000 }]) });
const lyr = Array.from({ length: 150 }, (_, i) => ({ ...defaultTextLayer('سطر رقم ' + i), startMs: i * 60, endMs: (i + 1) * 60 }));
out.push({ name: 'lyrics-150', layers: sanitizeLayers(lyr) });
writeFileSync(process.argv[2], JSON.stringify(out));
console.log('dumped', out.length, 'payloads');
