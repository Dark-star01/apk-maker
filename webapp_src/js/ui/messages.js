// All user-facing messages live here so wording/language can be changed in one place.
// Native errors arrive as stable codes (see MediaException.kt) and are mapped below.

export const MSG = {
  saveFailed: 'تعذر حفظ المشروع. تحقق من مساحة التخزين وحاول مرة أخرى.',
  projectDamaged: 'ملف هذا المشروع مفقود أو تالف.',
  exportLater: 'التصدير سيُضاف في مرحلة لاحقة.',
  needNative: 'هذه الميزة تعمل داخل التطبيق فقط وليس في المتصفح.',
  unexpected: 'حدث خطأ غير متوقع. حاول مرة أخرى.',
  audioMissing: 'ملف الصوت مفقود. اختر الملف مرة أخرى.',
  backgroundMissing: 'ملف الصورة مفقود. اختر الصورة مرة أخرى.',
};

const ERRORS = {
  UNSUPPORTED_AUDIO: 'صيغة الصوت غير مدعومة. استخدم MP3 أو WAV أو M4A أو AAC.',
  BAD_AUDIO: 'ملف الصوت تالف أو لا يمكن قراءته. جرّب ملفًا آخر.',
  UNSUPPORTED_IMAGE: 'صيغة الصورة غير مدعومة. استخدم JPG أو PNG أو WEBP.',
  BAD_IMAGE: 'الصورة تالفة أو لا يمكن قراءتها. جرّب صورة أخرى.',
  IMAGE_TOO_LARGE: 'الصورة كبيرة جدًا على هذا الجهاز. اختر صورة بأبعاد أصغر.',
  NO_SPACE: 'مساحة التخزين غير كافية. احذف بعض الملفات وحاول مرة أخرى.',
  READ_FAILED: 'تعذر قراءة الملف المحدد. اختر الملف مرة أخرى.',
  STORAGE_FAILED: 'تعذر حفظ الملف داخل المشروع. تحقق من مساحة التخزين.',
  NO_NATIVE: MSG.needNative,
};

export function errorMessage(e) {
  const code = e && e.code;
  return (code && ERRORS[code]) || MSG.unexpected;
}
