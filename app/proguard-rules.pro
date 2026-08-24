# ==================================================================
# قواعد ProGuard / R8 الخاصة بتطبيق Backup Pro
# ==================================================================

# لازم! أي دالة تستدعى من JavaScript عبر addJavascriptInterface يجب أن تبقى
# بنفس الاسم دون تعمية، وإلا سيتوقف التواصل بين الواجهة (index.html) والكوتلن تمامًا.
-keepclassmembers class com.dev.backupapp.MainActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.dev.backupapp.MainActivity$Bridge { *; }

# احتفظ بالخدمة الأمامية بشكل صريح (تُستدعى أيضًا من AndroidManifest.xml بالاسم)
-keep class com.dev.backupapp.BackupService { *; }

# أداة التحقق من توقيع التطبيق (الحماية من إعادة التوقيع/التعديل)
-keep class com.dev.backupapp.SignatureVerifier { *; }

# نقاط دخول WebView القياسية التي قد يستدعيها النظام عبر انعكاس داخلي
-keepclassmembers class * extends android.webkit.WebViewClient { public *; }
-keepclassmembers class * extends android.webkit.WebChromeClient { public *; }

# org.json مضمّنة في نظام أندرويد نفسه وليست جزءًا من كود التطبيق، لا داعي لتعميتها
-dontwarn org.json.**
