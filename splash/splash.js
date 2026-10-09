(function () {
  'use strict';
  var d = document, root = d.documentElement, t0 = Date.now();

  // الإعدادات تأتي من splash-config.js (تولّده الأداة من config.json)
  var C = Object.assign({
    appName: 'App', subtitle: '', footer: '', duration: 2500,
    bg: '#0f0f1a', text: '#a29bfe', accent: '#6c5ce7', logo: 'splash-logo.png'
  }, window.__SPLASH__ || {});

  function rgba(h, a) {
    h = String(h).replace('#', '');
    if (h.length === 3) h = h.replace(/./g, '$&$&');
    var n = parseInt(h, 16);
    if (isNaN(n)) return 'rgba(108,92,231,' + a + ')';
    return 'rgba(' + (n >> 16 & 255) + ',' + (n >> 8 & 255) + ',' + (n & 255) + ',' + a + ')';
  }
  function el(tag, cls, parent) {
    var e = d.createElement(tag);
    if (cls) e.className = cls;
    if (parent) parent.appendChild(e);
    return e;
  }

  var arabic = /[\u0600-\u06FF]/.test(C.appName); // التباعد بين الحروف يفصل الحروف العربية
  var D = Math.max(800, +C.duration || 2500);

  var css =
    'html{background:' + C.bg + '}' +
    '#ds{position:fixed;inset:0;z-index:2147483647;display:flex;flex-direction:column;align-items:center;justify-content:center;' +
    'background:radial-gradient(circle at 50% 38%,' + rgba(C.accent, .28) + ',transparent 62%),' +
    'radial-gradient(circle at 50% 110%,' + rgba(C.accent, .16) + ',transparent 55%),' + C.bg + ';' +
    'font-family:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;touch-action:none;overflow:hidden;' +
    'transition:opacity .6s ease,transform .6s ease}' +
    '#ds.ds-out{opacity:0;transform:scale(1.05);pointer-events:none}' +
    '#ds .ds-star{position:absolute;border-radius:50%;background:' + C.text + ';opacity:.2;animation:dsTw 3s ease-in-out infinite}' +
    '#ds .ds-logo{position:relative;width:128px;height:128px;display:flex;align-items:center;justify-content:center;' +
    'animation:dsPop .9s cubic-bezier(.2,.9,.3,1.2) both}' +
    '#ds .ds-glow{position:absolute;inset:-34px;border-radius:50%;background:radial-gradient(circle,' + rgba(C.accent, .55) + ',transparent 68%);animation:dsGlow 2.4s ease-in-out infinite}' +
    '#ds .ds-ring{position:absolute;inset:-12px;border-radius:50%;border:2px solid ' + rgba(C.accent, .18) + ';border-top-color:' + C.accent + ';animation:dsSpin 2.2s linear infinite}' +
    '#ds img,#ds .ds-mono{position:relative;width:100%;height:100%;object-fit:contain}' +
    '#ds .ds-mono{display:flex;align-items:center;justify-content:center;border-radius:50%;font-size:3.4rem;font-weight:800;color:#fff;' +
    'background:linear-gradient(135deg,' + C.accent + ',' + rgba(C.accent, .45) + ')}' +
    '#ds .ds-title{margin-top:40px;font-size:1.9rem;font-weight:700;color:' + C.text + ';text-align:center;padding:0 24px;' +
    (arabic ? 'animation:dsUp .8s ease .35s both}' : 'letter-spacing:.12em;animation:dsTrack 1.1s cubic-bezier(.2,.8,.3,1) .35s both}') +
    '#ds .ds-sub{margin-top:8px;font-size:.95rem;color:' + C.text + ';opacity:.6;text-align:center;padding:0 24px;animation:dsUp .8s ease .6s both}' +
    '#ds .ds-bar{margin-top:34px;width:150px;height:3px;border-radius:3px;overflow:hidden;background:' + rgba(C.accent, .2) + ';animation:dsUp .6s ease .5s both}' +
    '#ds .ds-fill{height:100%;width:100%;border-radius:3px;transform:scaleX(0);transform-origin:left;' +
    'background:linear-gradient(90deg,' + C.accent + ',' + C.text + ')}' +
    '#ds .ds-foot{position:absolute;bottom:calc(24px + env(safe-area-inset-bottom,0px));left:0;right:0;text-align:center;' +
    'font-size:.8rem;color:' + C.text + ';opacity:.5;animation:dsUp .8s ease .9s both}' +
    '@keyframes dsPop{from{opacity:0;transform:scale(.55) translateY(12px)}to{opacity:1;transform:none}}' +
    '@keyframes dsGlow{0%,100%{opacity:.45;transform:scale(.9)}50%{opacity:1;transform:scale(1.1)}}' +
    '@keyframes dsSpin{to{transform:rotate(360deg)}}' +
    '@keyframes dsUp{from{opacity:0;transform:translateY(12px)}to{opacity:1;transform:none}}' +
    '@keyframes dsTrack{from{opacity:0;letter-spacing:.5em}to{opacity:1;letter-spacing:.12em}}' +
    '@keyframes dsTw{0%,100%{opacity:.12}50%{opacity:.85}}' +
    '@media(prefers-reduced-motion:reduce){#ds *{animation:none!important}}';

  var style = el('style');
  style.textContent = css;
  root.appendChild(style);

  var s = el('div');
  s.id = 'ds';
  root.appendChild(s);

  for (var i = 0; i < 26; i++) {
    var st = el('span', 'ds-star', s), z = 1 + Math.random() * 1.6;
    st.style.cssText = 'left:' + (Math.random() * 100) + '%;top:' + (Math.random() * 100) + '%;width:' + z + 'px;height:' + z + 'px;' +
      'animation-duration:' + (2 + Math.random() * 3) + 's;animation-delay:' + (Math.random() * 3) + 's';
  }

  var logo = el('div', 'ds-logo', s);
  el('div', 'ds-glow', logo);
  el('div', 'ds-ring', logo);
  var img = el('img', '', logo);
  img.alt = '';
  img.onerror = function () {
    var m = el('div', 'ds-mono');
    m.textContent = Array.from(C.appName)[0] || '•';
    if (img.parentNode) img.parentNode.replaceChild(m, img);
  };
  img.src = C.logo;

  var title = el('div', 'ds-title', s);
  title.dir = 'auto';
  title.textContent = C.appName;
  if (C.subtitle) { var sub = el('div', 'ds-sub', s); sub.dir = 'auto'; sub.textContent = C.subtitle; }
  var bar = el('div', 'ds-bar', s), fill = el('div', 'ds-fill', bar);
  if (C.footer) { var ft = el('div', 'ds-foot', s); ft.dir = 'auto'; ft.textContent = C.footer; }

  // شريط التقدم يمشي طوال المدة الدنيا
  requestAnimationFrame(function () {
    requestAnimationFrame(function () {
      fill.style.transition = 'transform ' + D + 'ms cubic-bezier(.3,.6,.3,1)';
      fill.style.transform = 'scaleX(.92)';
    });
  });

  // الإخفاء: بعد اكتمال تحميل التطبيق + مرور المدة الدنيا
  var loaded = d.readyState === 'complete', done = false;
  function remove() {
    if (s.parentNode) s.parentNode.removeChild(s);
    if (style.parentNode) style.parentNode.removeChild(style);
  }
  function tryHide() {
    if (done || !loaded) return;
    var left = D - (Date.now() - t0);
    if (left > 0) { setTimeout(tryHide, left); return; }
    done = true;
    fill.style.transition = 'transform .25s ease';
    fill.style.transform = 'scaleX(1)';
    setTimeout(function () { s.classList.add('ds-out'); setTimeout(remove, 700); }, 250);
  }
  window.addEventListener('load', function () { loaded = true; tryHide(); });
  setTimeout(function () { loaded = true; tryHide(); }, D + 5000); // أمان: لا تعلق أبدا
  tryHide();
})();
