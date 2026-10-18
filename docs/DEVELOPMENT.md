# تطوير مشواري والبناء على GitHub

المستودع مستقل وخاص: https://github.com/hoodmoshla/Mishwari-Android. لا تستخدم إعدادات أو مفاتيح توقيع تطبيق آخر معه. تبقى مرحلة التنفيذ والمراجعة في `codex/initial-implementation`، و`main` مستقر لا تُدمج إليه نسخة غير متحققة. قد يشير README الرئيسي إلى فرع العمل بدل وجود التطبيق غير المكتمل على main.

## بناء نسخة جديدة

1. استنسخ المستودع الخاص بحسابك، وانتقل إلى فرع التطوير.
2. استخدم JDK 17 وAndroid SDK 35، وحدد SDK في `local.properties` المحلي غير المتتبع عند الحاجة.
3. شغّل:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

APK في `app/build/outputs/apk/debug/app-debug.apk`. اختبارات الجهاز بعد توصيله وتشغيل USB debugging: `./gradlew :app:connectedDebugAndroidTest`. لا تشغّلها على بيانات مهمة قبل تصدير نسخة محلية.

كل Push على main أو `codex/**` وكل Pull Request يشغّل `Android CI`. يبني APK اختباريًا فقط بعد نجاح اختبارات JVM وRobolectric وLint. كما يبني APK اختبارات الجهاز، لكنه **لا يشغّلها على هاتف**؛ لا تخلط بين البناء والاختبار الفعلي. راجع `TESTING.md` للاختبار الميداني.

## تنزيل APK والنتائج

افتح Actions في المستودع، ثم Android CI، ثم التشغيل الأخضر. تحت Artifacts تجد `Mishwari-debug-<run_number>` وفيه APK واحد و`SHA256SUMS.txt` و`apk-signature.txt`. يلزم فك ضغط Artifact. التقرير الآخر `verification-reports-<run_number>` يضم سجل Gradle وتقارير HTML/XML وLint. مدة الحفظ 30 يومًا، ويمكنك تنزيلها قبل انتهائها. يحتفظ التشغيل الفاشل بالتقارير دون إعلان APK صالح.

## التوقيع والأسرار

لا يحتاج Workflow الحالي أي Secret. `GITHUB_TOKEN` تلقائي بصلاحية قراءة المحتوى فقط. لا توجد عمليات رفع إصدار إنتاجي أو مفاتيح مكان مخفية. لا تسجل Tokens أو كلمات مرور أو keystore أو `local.properties` في Git.

الـAPK الحالي **Debug**، ليس إصدارًا إنتاجيًا ولا شهادة تحديث دائمة. قد يختلف توقيع Debug بين بيئات البناء وتشغيلات CI، فلا تفترض أن APK جديدًا يحدّث نسخة سابقة دون تعارض. صدّر بياناتك أولًا؛ لا تمسحها لمعالجة تعارض الشهادة. إعداد مفتاح إصدار دائم خاص بمشواري مطلوب قبل توزيع إنتاجي؛ لم نُنشئ مفتاحًا بديلًا ولا نستخدم أي مفتاح تطبيق آخر.

عند اعتماد Release لاحقًا، أضف أسراره بنفسك من صفحة المستودع: **Settings → Secrets and variables → Actions → New repository secret**. أسماء مقترحة خاصة بهذا المشروع: `MISHWARI_KEYSTORE_BASE64` لمحتوى ملف التوقيع المشفر بنقل Base64، و`MISHWARI_KEYSTORE_PASSWORD`، و`MISHWARI_KEY_ALIAS`، و`MISHWARI_KEY_PASSWORD`. Base64 ليس تشفيرًا للحماية؛ احتفظ بالملف الأصلي ونسخة آمنة خارجه. لا ترسل القيم في محادثة أو Issue أو Commit. يلزم قبل ربط هذه الأسرار بـWorkflow مراجعة الهوية `ye.mishwari` والشهادة المرجعية وسلوك التحديث، ثم خطوة تحقق `apksigner` قبل أي نشر. لا يقرأ Workflow الحالي هذه الأسماء، ولا توجد خطوة Release مفعلة بمجرد إضافتها.

## تغيير قاعدة البيانات والبيانات الإدارية

مخطط Room 1 محفوظ في `app/schemas/`. أي تعديل لاحق في الجداول يحتاج زيادة إصدار ومهاجرة واختبار بيانات قائمة؛ لا تضف destructive migration. الدليل الإداري أسماء موثقة لا حدود جغرافية. المصدر والترخيص والتحفظات في `research/yemen-admin-data-notes.md`، والمعرّفات المستقرة مهمة لتحديثه دون فقد ربط الطلبات.

العربية وRTL مطبقان في واجهة Compose، وطبقة البيانات مستقلة عنها. حافظ على اختبارات التخزين والإشعارات والواجهة عند تطوير الإدخال بالصوت أو المشاركة؛ لا تجعل أي خدمة شبكة شرطًا لإضافة طلب.
