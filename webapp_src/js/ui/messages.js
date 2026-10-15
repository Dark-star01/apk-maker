// All user-facing messages live here so wording/language can be changed in one place.
// Native errors arrive as stable codes (see MediaException.kt) and are mapped below.

export const MSG = {
  saveFailed: 'تعذر حفظ المشروع. تحقق من مساحة التخزين وحاول مرة أخرى.',
  projectDamaged: 'ملف هذا المشروع مفقود أو تالف.',
  exportCancelled: 'تم إلغاء التصدير.',
  needNative: 'هذه الميزة تعمل داخل التطبيق فقط وليس في المتصفح.',
  unexpected: 'حدث خطأ غير متوقع. حاول مرة أخرى.',
  audioMissing: 'ملف الصوت مفقود. اختر الملف مرة أخرى.',
  noAudio: 'لا يوجد ملف صوت. اختر ملفًا صوتيًا أولًا.',
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
  AUDIO_MISSING: MSG.audioMissing,
  AUDIO_UNPLAYABLE: 'تعذر تشغيل ملف الصوت. جرّب ملفًا آخر.',
  PLAYER_FAILED: 'توقف المشغّل بسبب خطأ. اضغط تشغيل للمحاولة مرة أخرى.',
  NO_AUDIO: MSG.noAudio,
  ANALYSIS_UNSUPPORTED: 'تعذر تحليل هذا الملف الصوتي، لذلك لن تظهر الموجة. التشغيل يعمل بشكل طبيعي.',
  ANALYSIS_FAILED: 'فشل تحليل الصوت. يمكنك إعادة المحاولة من تبويب Wave.',
  NO_NATIVE: MSG.needNative,
  EXPORT_BUSY: 'هناك عملية تصدير قيد التنفيذ بالفعل.',
  EXPORT_BAD_REQUEST: 'تعذر بدء التصدير: بيانات المشروع غير صالحة.',
  EXPORT_NO_AUDIO: 'لا يوجد ملف صوت في المشروع. أضف ملفًا صوتيًا قبل التصدير.',
  EXPORT_AUDIO_MISSING: 'ملف الصوت مفقود. اختر الملف مرة أخرى ثم صدّر.',
  EXPORT_NO_BACKGROUND: 'لا توجد صورة خلفية. أضف صورة قبل التصدير.',
  EXPORT_BACKGROUND_MISSING: 'ملف الصورة مفقود. اختر الصورة مرة أخرى ثم صدّر.',
  EXPORT_BACKGROUND_UNREADABLE: 'تعذر قراءة صورة الخلفية. جرّب صورة أخرى.',
  EXPORT_NO_SPACE: 'مساحة التخزين غير كافية للتصدير. احذف بعض الملفات وحاول مرة أخرى.',
  EXPORT_ENCODER_UNSUPPORTED: 'هذا الجهاز لا يدعم ترميز H.264 بهذا الحجم. جرّب دقة أقل.',
  EXPORT_AUDIO_UNSUPPORTED: 'تعذر تحويل هذا الملف الصوتي (صيغة أو عدد قنوات أو معدل عينات غير مدعوم). جرّب ملفًا آخر.',
  EXPORT_AUDIO_FAILED: 'فشل تحويل الصوت أثناء التصدير. جرّب ملفًا صوتيًا آخر.',
  EXPORT_VIDEO_FAILED: 'فشل ترميز الفيديو على هذا الجهاز. جرّب دقة أقل.',
  EXPORT_MUXER_FAILED: 'تعذر كتابة ملف الفيديو. تحقق من مساحة التخزين وحاول مرة أخرى.',
  EXPORT_VALIDATION_FAILED: 'الملف الناتج لم يجتز الفحص فلم يُحفظ. حاول مرة أخرى.',
  EXPORT_UNEXPECTED: 'حدث خطأ غير متوقع أثناء التصدير. حاول مرة أخرى.',
  EXPORT_SHARE_UNSUPPORTED: 'المشاركة تحتاج Android 10 أو أحدث. الفيديو محفوظ داخل التطبيق.',
  EXPORT_NO_APP: 'لا يوجد تطبيق يمكنه فتح الفيديو.',
};

export function errorMessage(e) {
  const code = e && e.code;
  return (code && ERRORS[code]) || MSG.unexpected;
}
