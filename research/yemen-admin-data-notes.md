# بيانات أسماء محافظات ومديريات اليمن

## الملف

`yemen_admin_names.json` قائمة أسماء عربية وإنجليزية، ورموز OCHA الإدارية (P-codes)، مرتبة في المحافظات ثم المديريات التابعة. العدد المستخرج من مورد OCHA هو **22 محافظة و335 مديرية**. أُسقطت الهندسة والإحداثيات بالكامل؛ الملف لا يتضمن إحداثيات أو حدودًا جغرافية.

## المصدر والتوثيق

- [OCHA/HDX — Yemen: Subnational Administrative Boundaries (COD-AB)](https://data.humdata.org/dataset/cod-ab-yem)
- [مورد GeoJSON المنشور في HDX](https://data.humdata.org/dataset/6b2656e2-b915-4671-bfed-468d5edcd80a/resource/c79a3728-44cd-4d20-8ea4-ecfab88c1450/download/yem_admin_boundaries.geojson.zip) — طبقتا Admin 1 وAdmin 2.
- [بيانات HDX الوصفية عبر API](https://data.humdata.org/api/3/action/package_show?id=cod-ab-yem)
- [شرح OCHA لمجموعة COD-AB](https://knowledge.base.unocha.org/wiki/spaces/imtoolbox/pages/2557378679/Administrative+Boundaries+COD-AB)
- الترخيص الذي تعيّنه بيانات HDX للمجموعة: [Creative Commons Attribution for Intergovernmental Organisations (CC BY-IGO 3.0)](https://creativecommons.org/licenses/by/3.0/igo/legalcode). يلزم الإسناد وفق نص الترخيص؛ أوصي بإظهار «المصدر: OCHA Yemen / HDX (COD-AB), CC BY-IGO 3.0» في صفحة المساعدة/حول التطبيق. لا تفترض أن مجرد كون الملف متاحًا للتنزيل يعني غياب شروط الترخيص.

المصدر الأصلي للبيانات بحسب HDX هو **Central Statistical Organization (CSO)**؛ أسهمت OCHA Yemen، وجهّزت/نشرت المجموعة OCHA FIS وHDX. الأسماء العربية مأخوذة حرفيًا من الحقول `adm1_name1` و`adm2_name1`، والإنجليزية من `adm1_name` و`adm2_name`، والرموز من حقول P-code المناظرة. لم تُجرَ إعادة تهجئة أو إضافة أسماء بديلة.

## حداثة البيانات والتحفظات

تم الاطلاع على بيانات HDX في **2026-10-18**. تسجل صفحة المجموعة مراجعة للدقة والاكتمال بتاريخ **2024-12-23**، لكن تاريخ صلاحية الحدود المسجل يبدأ **2019-11-22**، وآخر تحرير قبل النشر مذكور **2023-10-09**. كما أن موعد المراجعة التالي في البيانات الوصفية كان **2025-12-23**؛ لذلك لا يصح وصف المصدر بأنه تحقق حديثًا من التغييرات الإدارية حتى 2026، رغم أن صفحة/موارد HDX كانت متاحة ومحدّثة تقنيًا. يذكر OCHA أن COD-AB ينبغي مراجعتها كل 12 شهرًا. استخدم القائمة كمرجع موثق للأسماء والرموز، وتحقق من الجهة اليمنية المختصة أو OCHA قبل اعتمادها كمرجع قانوني أو تحديث إداري راهن.

## مخطط الاستيراد المقترح

شكل السجل المستخدم داخل `governorates`:

```json
{
  "pcode": "YE…",
  "name_en": "…",
  "name_ar": "…",
  "districts": [
    { "pcode": "YE……", "name_en": "…", "name_ar": "…" }
  ]
}
```

اعتبر P-code المفتاح المستقر للربط، ولا تربط المديريات بالاسم وحده. أضف عند الاستيراد تحققًا من تفرد الرموز، وأن رمز كل مديرية تابع للمحافظة الصحيحة، ومن وجود الاسمين؛ لا تُولّد إحداثيات مشتقة من أسماء المناطق.
