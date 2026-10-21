# تطوير مشواري والتحقق قبل التوزيع

المستودع الخاص هو [hoodmoshla/Mishwari-Android](https://github.com/hoodmoshla/Mishwari-Android)، والعمل الحالي على `codex/initial-implementation`. لا تُدمج هذه المرحلة إلى `main` ولا تُنشئ GitHub Release قبل اعتمادها. هوية التطبيق `ye.mishwari`، و`versionName=0.1.2` و`versionCode=3` ثابتة لهذه الإصلاحات.

## الفحص المحلي

استخدم JDK 17 وAndroid SDK Platform/Build Tools 35. حدّد `sdk.dir` داخل `local.properties` المحلي غير المتتبع، ثم شغّل:

```bash
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

اختبارات JVM وRobolectric تستخدم نسخة Debug المهيأة بـ`ui-test-manifest`. بناء APK اختبارات Android لا يعني تنفيذها؛ شغّل `:app:connectedDebugAndroidTest` على محاكي/جهاز اختبار منفصل. لا تستخدم هاتفًا فيه بيانات مهمة قبل نسخة احتياطية، ولا تحذف بياناته لمعالجة اختلاف التوقيع. ملف Debug ليس تحديثًا للنسخة الدائمة.

## ما ينفّذه CI

كل Push على `main` أو `codex/**` وكل Pull Request يشغّل Workflow واحدًا: `.github/workflows/android-ci.yml`.

1. يتحقق من رفض Release عند غياب أسرار التوقيع، ويشغّل اختبارات Debug وLint ويبني التطبيق وAPK اختبارات Android.
2. بعد نجاحها، ينفّذ اختبارات Android على محاكي Google APIs بمعمارية x86_64 لكل من API 26 و35. هذه اختبارات محاكي وليست اختبار هاتف أو Geofence ميدانيًا.
3. على فرع العمل المحدد فقط، وبعد نجاح البوابتين، يشغّل `lintRelease` ويبني Release بالمفتاح الدائم ثم يتحقق من الشهادة قبل رفع Artifact. لا توجد خطوة تنشئ GitHub Release أو تدمج فرعًا.

تُحفظ تقارير الوحدة وLint في `verification-reports-رقم_التشغيل`، وتقارير المحاكي في `android-tests-api-رقم_API-رقم_التشغيل`، وتقارير Release في `release-verification-reports-رقم_التشغيل`. المخرج الموقّع باسم `Mishwari-signed-رقم_التشغيل` يتضمن APK و`SHA256SUMS.txt` و`apk-signature.txt` و`build-provenance.json`. مدة حفظ Artifacts ثلاثون يومًا. افتح التشغيل نفسه لمعرفة commit والنتيجة؛ لا تعتبر وجود ملف اعتمادًا ميدانيًا.

## التوقيع والأسرار

Release يستخدم مفتاح مشواري الدائم فقط. يرفض البناء غياب `MISHWARI_KEYSTORE_PATH` أو `MISHWARI_STORE_PASSWORD` أو `MISHWARI_KEY_ALIAS` أو `MISHWARI_KEY_PASSWORD` بدل استخدام بديل. في CI يُستعاد الملف من `MISHWARI_KEYSTORE_BASE64` إلى مجلد مؤقت وتُقرأ بقية القيم من GitHub Secrets ثم يُحذف الملف المؤقت. لا تطبع الأسرار أو تضع keystore أو كلمة مرور أو `local.properties` في Git، ولا تخلط إعدادات أي تطبيق آخر مع هذا المستودع.

قارن `apksigner verify --verbose --print-certs` بالمرجع `signing/certificate-sha256.txt`: `da2adb23a6d2c5c12af57037bab627fd33133f0528a9fbf4c1fcb976e50d6894`. اختلاف البصمة يمنع التسليم كتحديث؛ لا تعالجه بإنشاء مفتاح أو إزالة التطبيق وبياناته.

## قاعدة البيانات والدليل والمساعد

مخطط Room v1 محفوظ في `app/schemas/` ولم يتغير في إصلاحات المراجعة. لا توجد destructive migration؛ أي تغيير جداول لاحق يحتاج زيادة إصدار وmigration صريحة واختبارات بيانات قائمة. اختبارات السلامة الحالية تفتح fixture مطابقًا لـv1 وتتحقق من البيانات، وتختبر رفض downgrade غير المدعوم دون إسقاط الصفوف.

دليل اليمن يحتوي 358 سجلًا إداريًا بلا إحداثيات. لا تُضف إحداثيات تخمينية، ولا تجعل التقاط الموقع يشغّل المراقبة أو يحفظ النقطة دون مراجعة المستخدم. اختبارات المساعد تستخدم نقلًا وهميًا وبيانات اصطناعية؛ نجاحها لا يعني اختبار Gemini أو OpenAI حقيقيًا.

## تجربة التصغير

R8 وتصغير الموارد غير مفعّلين في بناء التطبيق الأساسي. جرت المقارنة في worktree منفصل مع الإبقاء على المعماريات الأربع والهوية والتوقيع. انخفاض الحجم وحده ليس اختبار توافق: تحذيرات Kotlin metadata وبقاء اختبارات الهاتف/نسخة Release المقلّصة دون تحقق تمنع اعتماد التصغير. لا تُضف ABI filters لاستبعاد أجهزة المستخدمين.

راجع [قائمة القبول الميداني](FIELD_ACCEPTANCE.ar.md) قبل التفكير في الدمج أو النشر.
