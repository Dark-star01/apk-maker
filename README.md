# 🏭 صناع الـAPK

أداة تحوّل أي تطبيق ويب إلى APK تلقائياً عبر GitHub Actions.

## 🚀 طريقة الاستخدام

### 1. ضع تطبيقك
- اضغط تطبيق الويب في ملف ZIP
- ضعه في مجلد `webapp/`
- تأكد من وجود `index.html` في جذر الـ ZIP

### 2. عدّل الإعدادات
افتح `config.json` وعدّل:
```json
{
  "appName": "اسم تطبيقك",
  "appId": "com.dark.webapp",
  "version": "1.0.0"
}