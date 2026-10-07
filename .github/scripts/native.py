# -*- coding: utf-8 -*-
"""صناع الـAPK 2.0 — سكربت الخطوات (يُستدعى من build-apk.yml)

الاستخدام:  python3 .github/scripts/native.py <أمر>

الأوامر: inject | theme | kotlinfix | version | files | permissions | deps |
         kotlin | compose | plugins | validate | assets | info

القاعدة العامة: كل أمر يتخطى نفسه برسالة واضحة إذا ما فيه شيء يسويه.
الأخطاء الحقيقية تنتهي بـ ❌ ورمز خروج 1 (فيفشل البناء بسبب مفهوم)."""
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gradle_merge  # noqa: E402
import xml_merge  # noqa: E402

ROOT = Path.cwd()
ANDROID = ROOT / 'android'
APP_DIR = ANDROID / 'app'
SRC_MAIN = APP_DIR / 'src' / 'main'
LOG_DIR = ROOT / 'build_logs'
TEXT_EXT = {'.kt', '.java', '.xml', '.gradle', '.json', '.txt', '.properties', '.md'}
SKIP_NAMES = {'.gitkeep', 'README.md', '.DS_Store'}

KOTLIN_DEFAULT = '1.9.24'
# توافق Compose Compiler مع Kotlin (يفشل البناء إذا اختلفا)
COMPOSE_FOR_KOTLIN = {
    '1.9.20': '1.5.4', '1.9.21': '1.5.7', '1.9.22': '1.5.10',
    '1.9.23': '1.5.13', '1.9.24': '1.5.14', '1.9.25': '1.5.15',
}


# ───────────────────────── أدوات مساعدة ─────────────────────────
def log(msg=''):
    print(msg, flush=True)
    LOG_DIR.mkdir(exist_ok=True)
    with open(LOG_DIR / 'native.log', 'a', encoding='utf-8') as f:
        f.write(msg + '\n')


def fail(msg, hint=None):
    log('❌ ' + msg)
    if hint:
        log('💡 ' + hint)
    sys.exit(1)


def config():
    p = ROOT / 'config.json'
    if not p.is_file():
        fail('config.json غير موجود')
    try:
        return json.loads(p.read_text(encoding='utf-8'))
    except ValueError as e:
        fail('config.json فيه خطأ في الصيغة: %s' % e)


def native_cfg():
    n = config().get('native')
    return n if isinstance(n, dict) else {}


def app_id():
    return config().get('appId') or 'com.dark.webapp'


def native_enabled():
    return native_cfg().get('enabled', True) is not False


def need_android():
    if not ANDROID.is_dir():
        fail('مجلد android/ غير موجود', 'هذي الخطوة لازم تجي بعد npx cap add android')


def read(p):
    return Path(p).read_text(encoding='utf-8')


def write(p, s):
    Path(p).write_text(s, encoding='utf-8')


def merge_gradle_text(path, text, title):
    """يدمج نص gradle داخل ملف موجود"""
    if not Path(path).is_file():
        fail('الملف غير موجود: %s' % path)
    tmp = LOG_DIR / ('_src_' + title.replace('/', '_'))
    LOG_DIR.mkdir(exist_ok=True)
    write(tmp, text)
    try:
        gradle_merge.merge_file(tmp, path, lambda m: log(m))
    except ValueError as e:
        fail('تعذّر دمج %s: %s' % (title, e))
    finally:
        tmp.unlink(missing_ok=True)


def java_level():
    """مستوى Java المستخدم في Capacitor (نربط Kotlin به لتفادي تعارض JVM target)"""
    p = ROOT / 'node_modules' / '@capacitor' / 'android' / 'capacitor' / 'build.gradle'
    if p.is_file():
        m = re.search(r'VERSION_(\d+)(?:_(\d+))?', read(p))
        if m:
            return '%s.%s' % (m.group(1), m.group(2)) if m.group(2) else m.group(1)
    return '17'


def balanced(text):
    """فحص أولي لتوازن الأقواس (يتجاهل النصوص والتعليقات)"""
    stack = []
    pairs = {')': '(', ']': '[', '}': '{'}
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if text.startswith('"""', i):
            j = text.find('"""', i + 3)
            i = n if j < 0 else j + 3
            continue
        if c in '"\'':
            i += 1
            while i < n and text[i] != c and text[i] != '\n':
                i += 2 if text[i] == '\\' else 1
            i += 1
            continue
        if text.startswith('//', i):
            j = text.find('\n', i)
            i = n if j < 0 else j
            continue
        if text.startswith('/*', i):
            j = text.find('*/', i + 2)
            i = n if j < 0 else j + 2
            continue
        if c in '([{':
            stack.append(c)
        elif c in ')]}':
            if not stack or stack.pop() != pairs[c]:
                return False
        i += 1
    return not stack


def render(src, tmp_name):
    """يستبدل رموز __APP_ID__ و __APP_NAME__ داخل الملفات النصية"""
    if src.suffix.lower() not in TEXT_EXT:
        return src
    s = read(src)
    s = s.replace('__APP_ID__', app_id()).replace('__APP_NAME__', config().get('appName', 'App'))
    LOG_DIR.mkdir(exist_ok=True)
    out = LOG_DIR / ('_r_' + tmp_name)
    write(out, s)
    return out


# ───────────────────────── الأوامر ─────────────────────────
def cmd_inject():
    """يحقن env.js و splash في أول <head> (يمنع الوميض ويضمن الترتيب)"""
    f = ROOT / 'www' / 'index.html'
    if not f.is_file():
        fail('www/index.html غير موجود')
    tags = []
    for name in ('env.js', 'splash-config.js', 'splash.js'):
        if (ROOT / 'www' / name).is_file():
            tags.append('<script src="%s"></script>' % name)
    if not tags:
        log('⏭️ لا يوجد env.js أو splash للحقن')
        return
    s = read(f)
    tags = [t for t in tags if t not in s]
    if not tags:
        log('⏭️ السكربتات محقونة مسبقاً')
        return
    block = ''.join(tags)
    m = re.search(r'<head[^>]*>', s, re.I)
    s = (s[:m.end()] + block + s[m.end():]) if m else (block + s)
    write(f, s)
    log('✅ تم الحقن في <head>: ' + ', '.join(re.findall(r'src="([^"]+)"', block)))


def cmd_theme():
    """ثيم أندرويد الأصلي بنفس لون السبلاش (ينهي الوميض الأبيض)"""
    need_android()
    bg = (config().get('splash') or {}).get('backgroundColor', '#0f0f1a')
    res = SRC_MAIN / 'res' / 'values'
    write(res / 'dark_colors.xml',
          '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
          '    <color name="dark_splash_bg">%s</color>\n</resources>\n' % bg)
    p = res / 'styles.xml'
    if not p.is_file():
        log('⚠️ styles.xml غير موجود — تخطي تعديل الثيم')
        return
    s = read(p).replace('@drawable/splash', '@color/dark_splash_bg')

    def add(style, items):
        nonlocal s
        m = re.search(r'<style\s+name="%s"[^>]*>.*?</style>' % re.escape(style), s, re.S)
        if not m:
            log('   ⏭️ الستايل غير موجود: ' + style)
            return
        block = m.group(0)
        new = [i for i in items if re.search(r'name="([^"]+)"', i).group(1) not in block]
        if not new:
            return
        head = re.match(r'<style[^>]*>', block).group(0)
        s = s[:m.start()] + head + ''.join('\n        ' + i for i in new) + block[len(head):] + s[m.end():]

    add('AppTheme.NoActionBarLaunch', [
        '<item name="windowSplashScreenBackground">@color/dark_splash_bg</item>',
        '<item name="windowSplashScreenAnimatedIcon">@android:color/transparent</item>'])
    add('AppTheme.NoActionBar', ['<item name="android:windowBackground">@color/dark_splash_bg</item>'])
    write(p, s)
    log('✅ الثيم الأصلي مضبوط على اللون ' + bg)


def kotlin_version():
    return str(native_cfg().get('kotlinVersion') or KOTLIN_DEFAULT)


def cmd_kotlinfix():
    """إصلاح تعارض Kotlin stdlib (نفس سلوك النسخة القديمة، والإصدار قابل للتعديل)"""
    need_android()
    p = ANDROID / 'build.gradle'
    s = read(p)
    if 'إصلاح تعارض Kotlin' in s:
        log('⏭️ إصلاح Kotlin موجود مسبقاً')
        return
    v = kotlin_version()
    block = (
        '\n\n// ===== إصلاح تعارض Kotlin =====\n'
        'allprojects {\n    configurations.all {\n        resolutionStrategy {\n'
        '            force "org.jetbrains.kotlin:kotlin-stdlib:%(v)s"\n'
        '            force "org.jetbrains.kotlin:kotlin-stdlib-jdk7:%(v)s"\n'
        '            force "org.jetbrains.kotlin:kotlin-stdlib-jdk8:%(v)s"\n'
        '        }\n    }\n}\n' % {'v': v})
    write(p, s + block)
    log('✅ تم إضافة إصلاح Kotlin (%s)' % v)


def cmd_version():
    """يطبّق version من config.json على versionName و versionCode"""
    need_android()
    p = APP_DIR / 'build.gradle'
    cfg = config()
    ver = str(cfg.get('version') or '1.0.0')
    code = cfg.get('versionCode')
    if code is None:
        m = re.match(r'^(\d+)\.(\d+)\.(\d+)', ver)
        code = int(m.group(1)) * 10000 + int(m.group(2)) * 100 + int(m.group(3)) if m else 1
    s = read(p)
    s2 = re.sub(r'versionName\s+["\'][^"\']*["\']', 'versionName "%s"' % ver, s, count=1)
    s2 = re.sub(r'versionCode\s+\d+', 'versionCode %d' % int(code), s2, count=1)
    write(p, s2)
    log('🏷️ versionName=%s | versionCode=%s' % (ver, code))


def _native_files(base):
    return sorted(p for p in base.rglob('*') if p.is_file() and p.name not in SKIP_NAMES)


def cmd_files():
    """يطبّق native/android/** على مشروع أندرويد (دمج ذكي أو استبدال)"""
    if not native_enabled():
        log('⏭️ native معطّل في config.json')
        return
    base = ROOT / 'native' / 'android'
    files = _native_files(base) if base.is_dir() else []
    if not files:
        log('⏭️ لا توجد ملفات في native/android — تخطي (التطبيق البسيط يعمل كالمعتاد)')
        return
    need_android()
    n = native_cfg()
    mode = n.get('mode', 'merge')
    cf = n.get('customFiles') or {}
    merge_names = set(cf.get('merge') or ['AndroidManifest.xml', 'build.gradle'])
    replace_names = set(cf.get('replace') or [])
    aid = app_id()
    pkg_dir = SRC_MAIN / 'java' / Path(*aid.split('.'))
    log('🔧 تطبيق %d ملف من native/android (الوضع: %s)' % (len(files), mode))

    for src in files:
        rel = src.relative_to(base).as_posix().replace('__APP_ID_PATH__', aid.replace('.', '/'))
        dst = ANDROID / rel
        name = src.name
        if name in replace_names or mode == 'replace' or name not in merge_names:
            action = 'replace'
        else:
            action = 'merge'

        # حماية: MainActivity لازم تكون في حزمة التطبيق وإلا تُتجاهل بصمت أو ينهار التطبيق
        if name.startswith('MainActivity.') and 'java' in dst.parts:
            m = re.search(r'^\s*package\s+([\w.]+)', render(src, name).read_text(encoding='utf-8'), re.M)
            if dst.parent != pkg_dir or (m and m.group(1) != aid):
                fail('MainActivity في حزمة مختلفة عن appId (%s)' % aid,
                     'ضعها في native/android/app/src/main/java/__APP_ID_PATH__/ '
                     'واكتب في أول الملف: package __APP_ID__')

        rendered = render(src, rel.replace('/', '_'))
        dst.parent.mkdir(parents=True, exist_ok=True)

        if action == 'merge' and dst.is_file():
            log('🔀 دمج: ' + rel)
            try:
                if name == 'AndroidManifest.xml':
                    xml_merge.merge_file(rendered, dst, lambda m: log(m))
                elif name.endswith('.gradle'):
                    gradle_merge.merge_file(rendered, dst, lambda m: log(m))
                elif dst.suffix == '.xml' and dst.parent.name.startswith('values'):
                    xml_merge.merge_file(rendered, dst, lambda m: log(m))
                else:
                    log('   ℹ️ لا يوجد دمج لهذا النوع — استبدال')
                    shutil.copyfile(rendered, dst)
            except Exception as e:  # noqa: BLE001
                fail('فشل دمج %s: %s' % (rel, e), 'تأكد أن صيغة الملف صحيحة')
        else:
            if action == 'merge':
                log('📝 نسخ (لا يوجد أصل للدمج): ' + rel)
            else:
                log('📝 استبدال: ' + rel)
            shutil.copyfile(rendered, dst)

        # MainActivity.kt يستبدل MainActivity.java الافتراضي (وإلا class مكرر)
        if dst.suffix in ('.kt', '.java'):
            other = dst.with_suffix('.java' if dst.suffix == '.kt' else '.kt')
            if other.is_file():
                other.unlink()
                log('   🗑️ حذف %s (استُبدل بـ %s)' % (other.name, dst.name))
    for tmp in LOG_DIR.glob('_r_*'):
        tmp.unlink(missing_ok=True)
    log('✅ انتهى تطبيق ملفات Native')


def cmd_permissions():
    """يضيف native.permissions إلى AndroidManifest.xml بدون تكرار"""
    if not native_enabled():
        log('⏭️ native معطّل')
        return
    perms = native_cfg().get('permissions') or []
    if not perms:
        log('⏭️ لا توجد أذونات في config.json — تخطي')
        return
    need_android()
    ok = []
    for p in perms:
        if re.match(r'^[A-Za-z0-9_.]+$', str(p)):
            ok.append(str(p))
        else:
            log('⚠️ إذن غير صالح تم تجاهله: %s' % p)
    log('🔐 إضافة %d إذن' % len(ok))
    xml_merge.add_permissions(str(SRC_MAIN / 'AndroidManifest.xml'), ok, lambda m: log(m))
    log('✅ الأذونات جاهزة')


def cmd_deps():
    """يضيف native.extraDependencies إلى dependencies في app/build.gradle"""
    if not native_enabled():
        log('⏭️ native معطّل')
        return
    deps = native_cfg().get('extraDependencies') or []
    if not deps:
        log('⏭️ لا توجد مكتبات إضافية في config.json — تخطي')
        return
    need_android()
    lines = []
    for d in deps:
        d = str(d).strip()
        if re.match(r'^[\w.\-]+:[\w.\-]+(:[\w.\-+$]+)?$', d):
            lines.append("    implementation '%s'" % d)
        elif re.match(r'^(implementation|api|compileOnly|runtimeOnly|kapt|ksp|debugImplementation)\s+\S', d):
            lines.append('    ' + d)
        else:
            log('⚠️ مكتبة بصيغة غير صالحة تم تجاهلها: %s' % d)
    if not lines:
        return
    log('📚 إضافة %d مكتبة' % len(lines))
    merge_gradle_text(APP_DIR / 'build.gradle', 'dependencies {\n' + '\n'.join(lines) + '\n}\n', 'app/deps')
    log('✅ المكتبات جاهزة')


def needs_kotlin():
    n = native_cfg()
    if (n.get('compose') or {}).get('enabled'):
        return True
    if n.get('kotlin') is True:
        return True
    if n.get('kotlin') is False:
        return False
    for d in (ROOT / 'native' / 'android', ROOT / 'plugins'):
        if d.is_dir() and any(d.rglob('*.kt')):
            return True
    return False


def cmd_kotlin():
    """يفعّل دعم Kotlin (تلقائي إذا وُجدت ملفات .kt أو Compose)"""
    if not native_enabled():
        log('⏭️ native معطّل')
        return
    if not needs_kotlin():
        log('⏭️ لا يوجد كود Kotlin — تخطي')
        return
    need_android()
    v, j = kotlin_version(), java_level()
    log('🟣 تفعيل Kotlin %s (JVM target %s)' % (v, j))
    merge_gradle_text(
        ANDROID / 'build.gradle',
        "buildscript {\n    dependencies {\n        classpath 'org.jetbrains.kotlin:kotlin-gradle-plugin:%s'\n    }\n}\n"
        "ext {\n    darkJava = JavaVersion.VERSION_%s\n}\n" % (v, j.replace('.', '_')),
        'root/kotlin')
    merge_gradle_text(
        APP_DIR / 'build.gradle',
        "apply plugin: 'org.jetbrains.kotlin.android'\n"
        "android {\n    kotlinOptions {\n        jvmTarget = '%s'\n    }\n}\n" % j,
        'app/kotlin')
    log('✅ Kotlin جاهز')


def cmd_compose():
    """يفعّل Jetpack Compose (اختياري من config.json)"""
    if not native_enabled():
        log('⏭️ native معطّل')
        return
    c = native_cfg().get('compose') or {}
    if not c.get('enabled'):
        log('⏭️ Compose غير مفعّل — تخطي')
        return
    need_android()
    kv = kotlin_version()
    comp = str(c.get('compilerVersion') or COMPOSE_FOR_KOTLIN.get(kv, '1.5.14'))
    expected = COMPOSE_FOR_KOTLIN.get(kv)
    if expected and comp != expected:
        log('⚠️ Compose Compiler %s لا يناسب Kotlin %s (يفشل البناء) — تم استخدام %s' % (comp, kv, expected))
        comp = expected
    bom = str(c.get('bomVersion') or '2024.06.00')
    log('🎨 تفعيل Compose (compiler %s | BOM %s)' % (comp, bom))
    merge_gradle_text(
        APP_DIR / 'build.gradle',
        'android {\n    buildFeatures {\n        compose true\n    }\n'
        '    composeOptions {\n        kotlinCompilerExtensionVersion "%s"\n    }\n}\n'
        'dependencies {\n'
        '    implementation platform("androidx.compose:compose-bom:%s")\n'
        '    implementation "androidx.activity:activity-compose:1.9.0"\n'
        '    implementation "androidx.compose.ui:ui"\n'
        '    implementation "androidx.compose.ui:ui-graphics"\n'
        '    implementation "androidx.compose.ui:ui-tooling-preview"\n'
        '    implementation "androidx.compose.material3:material3"\n'
        '    debugImplementation "androidx.compose.ui:ui-tooling"\n'
        '}\n' % (comp, bom),
        'app/compose')
    log('✅ Compose جاهز')


def cmd_plugins():
    """يثبّت Capacitor Plugins المخصصة من plugins/*/ (قبل cap sync)"""
    base = ROOT / 'plugins'
    dirs = sorted(d for d in base.iterdir() if d.is_dir() and (d / 'package.json').is_file()) if base.is_dir() else []
    if not dirs:
        log('⏭️ لا توجد plugins/ — تخطي')
        return
    for d in dirs:
        try:
            pj = json.loads(read(d / 'package.json'))
        except ValueError as e:
            fail('package.json فيه خطأ في %s: %s' % (d.name, e))
        src = ((pj.get('capacitor') or {}).get('android') or {}).get('src')
        if not src or not (d / src).is_dir():
            fail('البلاجن %s غير معرّف لأندرويد' % d.name,
                 'أضف في package.json: "capacitor": {"android": {"src": "android"}} '
                 'وتأكد أن المجلد android/ موجود')
        log('📦 تثبيت: %s (%s)' % (d.name, pj.get('name', '?')))
    cmd = ['npm', 'install', '--no-audit', '--no-fund'] + [str(d.resolve()) for d in dirs]
    r = subprocess.run(cmd)
    if r.returncode != 0:
        fail('فشل npm install للبلاجنات', 'راجع اللوق أعلاه (غالباً package.json أو الاسم مكرر)')
    log('✅ تم تثبيت %d بلاجن' % len(dirs))


def cmd_validate():
    """فحص أولي (ليس مترجماً) لملفات Kotlin/Java: توازن الأقواس ووجود package"""
    dirs = [d for d in (ROOT / 'native' / 'android', ROOT / 'plugins') if d.is_dir()]
    files = [p for d in dirs for p in d.rglob('*') if p.suffix in ('.kt', '.java') and p.is_file()]
    if not files:
        log('⏭️ لا توجد ملفات Kotlin/Java للفحص')
        return
    warns = 0
    for p in sorted(files):
        s = read(p)
        rel = p.relative_to(ROOT).as_posix()
        if not balanced(s):
            log('⚠️ أقواس غير متوازنة في %s (غالباً خطأ syntax)' % rel)
            warns += 1
        elif not re.search(r'^\s*package\s+[\w.]+', s, re.M):
            log('⚠️ لا يوجد package في %s' % rel)
            warns += 1
        else:
            log('   ✔ %s' % rel)
    log('✅ الفحص الأولي انتهى (%d تحذير) — الترجمة الفعلية تتم في Gradle' % warns)


def cmd_assets():
    """ينسخ assets_inject/** إلى android/app/src/main/assets/"""
    base = ROOT / 'assets_inject'
    files = _native_files(base) if base.is_dir() else []
    if not files:
        log('⏭️ لا توجد assets_inject/ — تخطي')
        return
    need_android()
    dst_base = SRC_MAIN / 'assets'
    for f in files:
        rel = f.relative_to(base)
        (dst_base / rel).parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(f, dst_base / rel)
        log('🗂️ ' + rel.as_posix())
    log('✅ تم نسخ %d ملف إلى assets' % len(files))


def cmd_info():
    """يكتب BUILD_INFO.md (يدخل في الـ Artifact ويظهر في ملخص التشغيل)"""
    cfg = config()
    n = cfg.get('native') if isinstance(cfg.get('native'), dict) else {}
    plugins = sorted(d.name for d in (ROOT / 'plugins').iterdir()
                     if d.is_dir() and (d / 'package.json').is_file()) if (ROOT / 'plugins').is_dir() else []
    nat = _native_files(ROOT / 'native' / 'android') if (ROOT / 'native' / 'android').is_dir() else []
    assets = _native_files(ROOT / 'assets_inject') if (ROOT / 'assets_inject').is_dir() else []
    hooks = [h for h in ('pre-build.sh', 'post-build.sh') if (ROOT / 'hooks' / h).is_file()]
    cap = '?'
    try:
        cap = json.loads(read(ROOT / 'node_modules' / '@capacitor' / 'core' / 'package.json'))['version']
    except Exception:  # noqa: BLE001
        pass
    apk = APP_DIR / 'build' / 'outputs' / 'apk' / 'debug' / 'app-debug.apk'
    size = '%.2f MB' % (apk.stat().st_size / 1048576) if apk.is_file() else '—'
    on = lambda b: '✅' if b else '—'  # noqa: E731
    lines = [
        '# 📱 BUILD_INFO', '',
        '| | |', '|---|---|',
        '| التطبيق | %s |' % cfg.get('appName', ''),
        '| appId | `%s` |' % app_id(),
        '| الإصدار | %s |' % cfg.get('version', '1.0.0'),
        '| حجم APK | %s |' % size,
        '| Capacitor | %s |' % cap,
        '| Java (Capacitor) | %s |' % java_level(),
        '| Commit | `%s` |' % os.environ.get('GITHUB_SHA', '')[:7],
        '| وقت البناء (UTC) | %s |' % os.popen('date -u +"%Y-%m-%d %H:%M"').read().strip(),
        '', '## الميزات', '',
        '- Splash: %s' % on((cfg.get('splash') or {}).get('enabled', True)),
        '- env.js: %s' % on((cfg.get('env') or {}).get('enabled', False)),
        '- Native: %s (%d ملف)' % (on(nat and n.get('enabled', True) is not False), len(nat)),
        '- Kotlin: %s' % on(needs_kotlin() and native_enabled()),
        '- Compose: %s' % on((n.get('compose') or {}).get('enabled')),
        '- Plugins: %s' % (', '.join(plugins) if plugins else '—'),
        '- assets_inject: %s (%d ملف)' % (on(assets), len(assets)),
        '- Hooks: %s' % (', '.join(hooks) if hooks else '—'),
        '- أذونات إضافية: %s' % (', '.join(n.get('permissions') or []) or '—'),
        '- مكتبات إضافية: %s' % (', '.join(n.get('extraDependencies') or []) or '—'),
    ]
    log_file = LOG_DIR / 'native.log'
    if log_file.is_file():
        lines += ['', '## سجل خطوات Native', '', '```', read(log_file).rstrip(), '```']
    write(ROOT / 'BUILD_INFO.md', '\n'.join(lines) + '\n')
    log('📝 تم إنشاء BUILD_INFO.md')


# ───────────── تشخيص مسار ملفات Native (مؤقت — لتتبع سبب غياب Kotlin من الـAPK) ─────────────
_DIAG_NAMES = ('MainActivity.kt', 'MainActivity.java', 'MvmBridgePlugin.kt', 'MvmDiagPlugin.kt', 'MediaManager.kt', 'AudioEngine.kt')


def _find(root, names, skip=()):
    # skip = أسماء مجلدات على المستوى الأول فقط (مثل android/ و node_modules/)
    out = []
    if not root.is_dir():
        return out
    for p in root.rglob('*'):
        if not p.is_file() or p.name not in names:
            continue
        rel = p.relative_to(root).parts
        if rel and rel[0] in skip:
            continue
        out.append(p)
    return sorted(out)


def _diag_report():
    log('════════ NATIVE DIAG ════════')
    log('cwd: %s' % ROOT)
    log('native.enabled: %s' % native_enabled())
    base = ROOT / 'native' / 'android'
    src = _native_files(base) if base.is_dir() else []
    log('NATIVE SOURCE: native/android exists=%s files=%d' % (base.is_dir(), len(src)))
    for f in src:
        log('   src  ' + f.relative_to(ROOT).as_posix())
    strays = [p for p in _find(ROOT, _DIAG_NAMES, skip=('node_modules', 'android', 'build_logs', 'workspace', '.git'))
              if base not in p.parents]
    for p in strays:
        log('   ⚠️ ملف Native في مكان غير متوقع: ' + p.relative_to(ROOT).as_posix())
    log('ANDROID TARGET: android/app/src/main (java + manifest)')
    if SRC_MAIN.is_dir():
        for p in sorted(SRC_MAIN.rglob('*')):
            if p.is_file() and (p.suffix in ('.kt', '.java') or p.name == 'AndroidManifest.xml'):
                log('   dst  ' + p.relative_to(ROOT).as_posix())
    else:
        log('   (android/app/src/main غير موجود بعد)')
    found = {}
    for n in _DIAG_NAMES:
        hits = _find(SRC_MAIN, (n,))
        found[n] = hits
        if hits:
            for h in hits:
                m = re.search(r'^\s*package\s+([\w.]+)', read(h), re.M)
                log('FOUND %s -> %s (package %s)' % (n, h.relative_to(ROOT).as_posix(), m.group(1) if m else '?'))
        else:
            log('MISSING %s in android/' % n)
    log('════════════════════════════')
    return src, found


def cmd_diag():
    """يطبع حالة ملفات native (المصدر والهدف) بدون فشل"""
    _diag_report()


def cmd_diagcheck():
    """يفشل البناء إذا كانت ملفات native مصدرها موجود/مطلوب لكنها غير موجودة في android/ النهائي"""
    src, found = _diag_report()
    if not native_enabled():
        return
    if not src:
        fail('native/android غير موجود أو فارغ في الـrepository عند بدء الـworkflow',
             'تأكد أن المجلد native/ في جذر الـrepo (بجانب config.json وليس داخل مجلد فرعي) وأنه مرفوع للفرع main')
    if not found['MainActivity.kt']:
        fail('MainActivity.kt لم يصل إلى android/ (المصدر موجود لكن النسخ لم يحدث)', 'راجع سجل خطوة 🧩 تطبيق ملفات Native')
    if found['MainActivity.java']:
        fail('MainActivity.java الافتراضي ما زال موجودًا بجانب MainActivity.kt')
    for need in ('MvmBridgePlugin.kt', 'MvmDiagPlugin.kt', 'MediaManager.kt', 'AudioEngine.kt'):
        if not found[need]:
            fail('%s غير موجود في android/ النهائي' % need)
    log('✅ DIAG: ملفات native موجودة في android/ النهائي')


COMMANDS = {k[4:]: v for k, v in globals().items() if k.startswith('cmd_')}

if __name__ == '__main__':
    if len(sys.argv) != 2 or sys.argv[1] not in COMMANDS:
        print('الاستخدام: native.py <%s>' % '|'.join(sorted(COMMANDS)))
        sys.exit(2)
    COMMANDS[sys.argv[1]]()
