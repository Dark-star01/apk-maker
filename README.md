# صناع الـAPK 2.0

حوّل تطبيق ويب إلى APK عبر GitHub Actions + Capacitor، مع دعم اختياري لكود Native (Kotlin) وبلاجنات Capacitor وJetpack Compose.

## الاستخدام السريع (مبتدئ)
1. ضع ملف ZIP للتطبيق داخل `webapp/`
2. عدّل `config.json` (الاسم، المعرّف، الألوان)
3. ادفع إلى `main` ← حمّل الـAPK من Artifacts (ومعه `BUILD_INFO.md`)

كل شي تحت اختياري: إذا ما حطيت `native/` أو `plugins/` يشتغل مثل قبل.

## هيكل المستودع
```
.github/workflows/build-apk.yml   الـworkflow (نظيف، بدون heredocs)
.github/scripts/                  المنطق: native.py + xml_merge.py + gradle_merge.py
webapp/            ZIP التطبيق
splash/            splash.js (+ logo.png اختياري)
native/android/    تعديلات Native (اختياري)
plugins/           Capacitor Plugins مخصصة (اختياري)
assets_inject/     ملفات تُنسخ إلى assets أندرويد (اختياري)
hooks/             pre-build.sh و post-build.sh (اختياري)
examples/          أمثلة جاهزة (لا تُطبَّق تلقائياً، انسخها لتفعيلها)
```

## Native (`native/android/`)
انسخ المجلد من `examples/native/` وعدّل عليه. الملفات تُطبَّق بنفس مساراتها داخل `android/`.
- `AndroidManifest.xml` و `build.gradle` ← **دمج ذكي** (يضيف فقط، ولا يحذف شيئاً)
- `res/values/*.xml` ← تُدمج إذا كتبتها في `customFiles.merge`، وتُستبدل إذا كتبتها في `replace`
- غيرها (مثل `.kt`) ← استبدال/نسخ
- `mode`: `merge` (الافتراضي) أو `replace` (يستبدل كل شي)
- رموز مدعومة داخل المسارات والملفات النصية: `__APP_ID__` و `__APP_ID_PATH__` (مثل `com/moonbook/dark`) و `__APP_NAME__`
- **مهم:** `MainActivity` لازم تكون داخل حزمة `appId`. استخدم `__APP_ID_PATH__` و `package __APP_ID__`، وإلا يفشل البناء برسالة واضحة.
- ملفات `.kt` تفعّل Kotlin تلقائياً، و`MainActivity.kt` تحذف `MainActivity.java` الافتراضي.

## config.json (القسم native)
انظر `examples/config.full.json` لكل الخيارات: `permissions`, `extraDependencies`, `kotlinVersion`, `compose`, `customFiles`, وأيضاً `capacitorVersion` لتثبيت إصدار Capacitor.

## Plugins (`plugins/<اسم>/`)
انسخ `examples/plugins/my-plugin/`. يلزم `package.json` فيه `capacitor.android.src` ومجلد `android/` فيه `build.gradle`. من JavaScript العادي: `Capacitor.Plugins.MyPlugin.echo({...})`.

## Compose
`"compose": {"enabled": true}`. إصدار Compose Compiler لازم يطابق Kotlin (الافتراضي 1.5.14 لـKotlin 1.9.24)، وإذا وضعت غير متوافق تصحّحه الأداة وتكتب تحذيراً.

## Hooks و assets
- `hooks/pre-build.sh` قبل بناء Gradle، و`hooks/post-build.sh` بعده (المتغيرات: `APP_NAME` `APP_ID` `VERSION` و`APK_PATH` في post)
- `assets_inject/**` ← `android/app/src/main/assets/`

## شاشة البداية
`splash/splash.js` ملف واحد يقرأ الإعدادات من `config.json` (`duration`, `backgroundColor`, `textColor`, `accentColor`, `subtitle`, `footer`, `logoFile`). ضع `splash/logo.png` (يفضّل 512×512) وإلا يظهر أول حرف من الاسم.

## عند الفشل
تبويب Summary في GitHub Actions يعرض الخطوة الفاشلة والسبب المحتمل وأهم أسطر خطأ Gradle، وسجلات كاملة في Artifact باسم `build-logs`.
