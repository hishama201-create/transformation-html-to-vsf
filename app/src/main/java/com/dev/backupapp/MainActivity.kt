package com.dev.backupapp

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

class MainActivity : AppCompatActivity(), BackupService.ProgressListener {

    private lateinit var webView: WebView
    private lateinit var prefs: SharedPreferences

    private val PERMISSION_CODE = 100
    private val FILE_CHOOSER_CODE = 51426
    private val OPEN_TREE_CODE = 51427
    private val HTML_PICK_CODE = 51428

    private val PREFS_NAME = "backup_pro_prefs"
    private val KEY_TREE_URI = "save_tree_uri"

    // مطلوب لتمرير نتيجة اختيار الملف إلى صفحة الـ HTML
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // ===== التحقق من توقيع التطبيق (حماية من إعادة التغليف/التوقيع بمفتاح آخر) =====
        val sha256 = SignatureVerifier.getSigningSha256(this)
        if (!SignatureVerifier.isSignatureValid(this)) {
            // توقيع مزيّف/مختلف عن مفتاحك — أوقف التطبيق فورًا قبل تحميل أي واجهة أو صلاحيات
            android.widget.Toast.makeText(
                this, "تعذر التحقق من صحة التطبيق. الرجاء تثبيته من مصدر رسمي.", android.widget.Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }
        if (BuildConfig.DEBUG || sha256 == null) {
            // مساعدة أثناء التطوير فقط: اطبع البصمة الحالية حتى تنسخها إلى SignatureVerifier.kt
            Log.i("SignatureVerifier", "بصمة التوقيع الحالية: $sha256")
        }

        webView = WebView(this)
        setContentView(webView)

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true

        // بدون هذا الجزء لا يستجيب الضغط على <input type="file">
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                val intent = fileChooserParams.createIntent()
                try {
                    startActivityForResult(intent, FILE_CHOOSER_CODE)
                } catch (e: Exception) {
                    filePathCallback = null
                    return false
                }
                return true
            }
        }

        webView.addJavascriptInterface(Bridge(), "Android")
        webView.loadUrl("file:///android_asset/index.html")

        requestNeededPermissions()
    }

    override fun onResume() {
        super.onResume()
        // اربط الواجهة بمستمع تقدّم الخدمة حتى تصل التحديثات فورًا أثناء فتح التطبيق
        BackupService.Bus.listener = this
        // إن انتهت عملية بينما كان التطبيق مغلقًا، اعرض نتيجتها الآن بدل تجاهلها
        deliverPendingResultIfAny("contacts", BackupService.KEY_PENDING_CONTACTS, "onContactsResult")
        deliverPendingResultIfAny("sms", BackupService.KEY_PENDING_SMS, "onSmsResult")
        deliverPendingResultIfAny("convert", BackupService.KEY_PENDING_CONVERT, "onConvertResult")
    }

    override fun onPause() {
        super.onPause()
        if (BackupService.Bus.listener === this) BackupService.Bus.listener = null
    }

    private fun deliverPendingResultIfAny(op: String, prefKey: String, jsCallback: String) {
        val pending = prefs.getString(prefKey, null) ?: return
        prefs.edit().remove(prefKey).apply()
        notifyJs(jsCallback, pending)
    }

    // ================== BackupService.ProgressListener ==================

    override fun onProgress(op: String, percent: Int, message: String) {
        notifyJs("onBackupProgress", JSONObject().apply {
            put("op", op)
            put("percent", percent)
            put("message", message)
        }.toString())
    }

    override fun onFinished(op: String, resultJson: String) {
        val callback = when (op) {
            "contacts" -> "onContactsResult"
            "sms" -> "onSmsResult"
            "convert" -> "onConvertResult"
            else -> return
        }
        notifyJs(callback, resultJson)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            FILE_CHOOSER_CODE -> {
                val results: Array<Uri>? =
                    if (resultCode == Activity.RESULT_OK && data != null) {
                        val clipData = data.clipData
                        if (clipData != null) {
                            Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                        } else {
                            data.data?.let { arrayOf(it) }
                        }
                    } else null

                filePathCallback?.onReceiveValue(results)
                filePathCallback = null
                return
            }
            HTML_PICK_CODE -> {
                if (resultCode == Activity.RESULT_OK && data?.data != null) {
                    val uri = data.data!!
                    try {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (e: Exception) { /* بعض المزوّدين لا يدعمون الصلاحية الدائمة، نتابع دون ذلك */ }

                    val serviceIntent = Intent(this, BackupService::class.java).apply {
                        action = BackupService.ACTION_CONVERT_HTML
                        this.data = uri
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    ContextCompat.startForegroundService(this, serviceIntent)
                    notifyJs("onBackupProgress", JSONObject().apply {
                        put("op", "convert"); put("percent", 0); put("message", "جارِ التحضير...")
                    }.toString())
                } else {
                    notifyJs("onConvertResult", JSONObject().apply {
                        put("result", "ERROR:لم يتم اختيار أي ملف")
                    }.toString())
                }
                return
            }
            OPEN_TREE_CODE -> {
                if (resultCode == Activity.RESULT_OK && data?.data != null) {
                    val treeUri = data.data!!
                    try {
                        contentResolver.takePersistableUriPermission(
                            treeUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                        prefs.edit().putString(KEY_TREE_URI, treeUri.toString()).apply()
                        notifyJs("onFolderPicked", JSONObject().apply {
                            put("ok", true)
                            put("path", displayNameForTree(treeUri))
                        }.toString())
                    } catch (e: Exception) {
                        notifyJs("onFolderPicked", JSONObject().apply {
                            put("ok", false)
                            put("error", e.message ?: "خطأ غير معروف")
                        }.toString())
                    }
                }
                return
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val notGranted = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), PERMISSION_CODE)
        }
    }

    /** إرسال نتيجة من الكوتلن إلى جافاسكربت بأمان (Base64 لتفادي مشاكل الأحرف الخاصة) */
    private fun notifyJs(jsFunction: String, jsonPayload: String) {
        runOnUiThread {
            val encoded = Base64.encodeToString(jsonPayload.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            webView.evaluateJavascript(
                "window.$jsFunction && window.$jsFunction(decodeURIComponent(escape(window.atob('$encoded'))));",
                null
            )
        }
    }

    private fun getSavedTreeUri(): Uri? {
        val stored = prefs.getString(KEY_TREE_URI, null) ?: return null
        return try {
            val uri = Uri.parse(stored)
            // تأكد أن الصلاحية ما زالت موجودة
            val hasPermission = contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isWritePermission
            }
            if (hasPermission) uri else null
        } catch (e: Exception) {
            null
        }
    }

    private fun displayNameForTree(uri: Uri): String {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            docId.substringAfterLast(":").ifBlank { "المجلد المختار" }
        } catch (e: Exception) {
            "المجلد المختار"
        }
    }

    // ملاحظة: منطق حفظ الملفات وتحليل HTML واستخراج جهات الاتصال/الرسائل انتقل بالكامل
    // إلى BackupService.kt ليعمل داخل خدمة أمامية (Foreground Service) تستمر بالعمل
    // حتى بعد إغلاق التطبيق، بدل تنفيذه هنا داخل الـ Activity.

    inner class Bridge {

        // معلومات حول التطبيق: الإصدار والمطور
        @JavascriptInterface
        fun getAppInfo(): String {
            val json = JSONObject()
            json.put("appName", getString(R.string.app_name))
            json.put("version", BuildConfig.VERSION_NAME)
            json.put("developer", getString(R.string.developer_name))
            return json.toString()
        }

        @JavascriptInterface
        fun getSaveFolderName(): String {
            val uri = getSavedTreeUri()
            return if (uri != null) displayNameForTree(uri) else "Downloads (افتراضي)"
        }

        @JavascriptInterface
        fun pickSaveFolder() {
            runOnUiThread {
                try {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    intent.addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    )
                    startActivityForResult(intent, OPEN_TREE_CODE)
                } catch (e: Exception) {
                    notifyJs("onFolderPicked", JSONObject().apply {
                        put("ok", false)
                        put("error", e.message ?: "تعذر فتح منتقي المجلدات")
                    }.toString())
                }
            }
        }

        @JavascriptInterface
        fun resetSaveFolder() {
            prefs.edit().remove(KEY_TREE_URI).apply()
        }

        // نسخ احتياطي لجهات الاتصال — يبدأ خدمة أمامية (Foreground Service) تعمل حتى
        // لو خرج المستخدم من التطبيق، مع إشعار يعرض نسبة التقدّم الفعلية بالوقت الحقيقي
        @JavascriptInterface
        fun backupContacts() {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
                notifyJs("onContactsResult", JSONObject().put("result", "NO_PERMISSION").toString())
                return
            }
            val intent = Intent(this@MainActivity, BackupService::class.java).apply {
                action = BackupService.ACTION_BACKUP_CONTACTS
            }
            ContextCompat.startForegroundService(this@MainActivity, intent)
        }

        // نسخ احتياطي للرسائل — نفس مبدأ جهات الاتصال: خدمة أمامية + إشعار تقدّم حي
        @JavascriptInterface
        fun backupSms() {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED) {
                notifyJs("onSmsResult", JSONObject().put("result", "NO_PERMISSION").toString())
                return
            }
            val intent = Intent(this@MainActivity, BackupService::class.java).apply {
                action = BackupService.ACTION_BACKUP_SMS
            }
            ContextCompat.startForegroundService(this@MainActivity, intent)
        }

        // اختيار ملف HTML لتحويله: القراءة والتحليل واستخراج كل الأسماء (بدون حد للعدد)
        // تتم بالكامل في الكوتلن الأصلي بالخلفية، وليس عبر جافاسكربت/WebView — لضمان
        // عمل التحويل بشكل موثوق حتى مع آلاف الأسماء.
        @JavascriptInterface
        fun pickHtmlFile() {
            runOnUiThread {
                try {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                    intent.addCategory(Intent.CATEGORY_OPENABLE)
                    intent.type = "*/*"
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/html", "text/plain"))
                    startActivityForResult(intent, HTML_PICK_CODE)
                } catch (e: Exception) {
                    notifyJs("onConvertResult", JSONObject().apply {
                        put("result", "ERROR:${e.message ?: "تعذر فتح منتقي الملفات"}")
                    }.toString())
                }
            }
        }
    }
}
