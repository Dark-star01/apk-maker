# my-plugin

بلاجن Capacitor مخصص بـ Kotlin. ضعه في `plugins/my-plugin/` وأداة البناء تثبّته تلقائياً.

## الاستخدام من JavaScript (بدون bundler)
```js
const { MyPlugin } = Capacitor.Plugins;
const r = await MyPlugin.echo({ value: 'مرحبا' });
console.log(r.value);
```

## شروط لازم تتحقق
- `package.json` فيه `"capacitor": { "android": { "src": "android" } }`
- `android/build.gradle` + `android/src/main/AndroidManifest.xml` موجودين
- `name` في package.json فريد بين البلاجنات
