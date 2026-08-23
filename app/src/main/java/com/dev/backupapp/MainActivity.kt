package com.dev.backupapp

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.ContactsContract
import android.provider.DocumentsContract
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var prefs: SharedPreferences

    // تنفيذ عمليات النسخ الثقيلة في الخلفية حتى لا تتجمد الواجهة (ANR)
    private val backgroundExecutor = Executors.newSingleThreadExecutor()

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
                    notifyJs("onConvertProgress", "جارِ قراءة الملف وتحويل الأسماء (قد يستغرق بضع ثوانٍ للملفات الكبيرة)...")
                    backgroundExecutor.execute {
                        processHtmlToVcf(uri)
                    }
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
        val perms = arrayOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS)
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

    /**
     * يحفظ الملف في المجلد الذي اختاره المستخدم (إن وُجد)، وإلا في مجلد Downloads كخيار افتراضي.
     * يُنفَّذ دومًا من خيط خلفي.
     */
    private fun writeBackupFile(filename: String, mimeType: String, content: String): String {
        val treeUri = getSavedTreeUri()
        if (treeUri != null) {
            try {
                val dir = DocumentFile.fromTreeUri(this, treeUri)
                    ?: return "ERROR:تعذر فتح المجلد المختار"
                // احذف أي ملف سابق بنفس الاسم حتى لا تتكرر النسخ
                dir.findFile(filename)?.delete()
                val newFile = dir.createFile(mimeType, filename)
                    ?: return "ERROR:تعذر إنشاء الملف في المجلد المختار"
                contentResolver.openOutputStream(newFile.uri)?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                } ?: return "ERROR:تعذر الكتابة في الملف"
                return "OK:${newFile.uri}"
            } catch (e: Exception) {
                return "ERROR:${e.message}"
            }
        }
        // fallback: مجلد التنزيلات العام
        return try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, filename)
            FileWriter(file).use { it.write(content) }
            "OK:${file.absolutePath}"
        } catch (e: Exception) {
            "ERROR:${e.message}"
        }
    }

    // ================== استخراج جهات الاتصال من HTML (Kotlin أصلي، بدون WebView) ==================

    private fun unescapeHtml(s: String): String {
        return s
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()
    }

    private fun stripTags(s: String): String = s.replace(Regex("<[^>]*>"), "").trim()

    /** الصيغة الأساسية: تصدير "دفاتر العناوين" من فيسبوك (كل جهة اتصال داخل div class="_a6-g") */
    private fun parseFacebookAddressBook(html: String): List<Pair<String, List<String>>>? {
        val marker = "<div class=\"_a6-g\">"
        if (!html.contains(marker)) return null

        val parts = html.split(marker).drop(1)
        if (parts.isEmpty()) return null

        val nameRegex = Regex("_a6-h[^\"]*\">(.*?)</div>", RegexOption.DOT_MATCHES_ALL)
        val numRegex = Regex("_a6_p\">(.*?)</div>", RegexOption.DOT_MATCHES_ALL)

        val results = mutableListOf<Pair<String, List<String>>>()
        for (part in parts) {
            // نكتفي ببداية الكتلة لتفادي التقاط بيانات من كتل تالية بالخطأ
            val window = if (part.length > 4000) part.substring(0, 4000) else part
            val nameMatch = nameRegex.find(window)
            val name = nameMatch?.groupValues?.get(1)?.let { unescapeHtml(stripTags(it)) } ?: ""
            val nums = numRegex.findAll(window)
                .map { unescapeHtml(stripTags(it.groupValues[1])) }
                .filter { it.isNotBlank() }
                .toList()
            if (name.isNotBlank() || nums.isNotEmpty()) {
                results.add(Pair(name.ifBlank { "بدون اسم" }, nums))
            }
        }
        return results
    }

    /** صيغة بديلة: جدول HTML عادي (tr > td) */
    private fun parseHtmlTable(html: String): List<Pair<String, List<String>>>? {
        val rowRegex = Regex("<tr[^>]*>(.*?)</tr>", RegexOption.DOT_MATCHES_ALL)
        val cellRegex = Regex("<t[dh][^>]*>(.*?)</t[dh]>", RegexOption.DOT_MATCHES_ALL)
        val rows = rowRegex.findAll(html).toList()
        if (rows.isEmpty()) return null

        val results = mutableListOf<Pair<String, List<String>>>()
        for (row in rows) {
            val cells = cellRegex.findAll(row.groupValues[1])
                .map { unescapeHtml(stripTags(it.groupValues[1])) }
                .toList()
            if (cells.size >= 2) {
                val name = cells[0].ifBlank { "بدون اسم" }
                val number = cells[1]
                if (name.isNotBlank() || number.isNotBlank()) {
                    results.add(Pair(name, if (number.isNotBlank()) listOf(number) else emptyList()))
                }
            }
        }
        return if (results.isNotEmpty()) results else null
    }

    /** الملاذ الأخير: نص عادي بعد إزالة الوسوم، أسطر متبادلة (اسم ثم رقم) */
    private fun parseFallbackLines(html: String): List<Pair<String, List<String>>> {
        val bodyOnly = Regex("<body.*?>(.*)</body>", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: html
        val text = stripTags(bodyOnly.replace(Regex("<script.*?</script>", RegexOption.DOT_MATCHES_ALL), ""))
        val lines = unescapeHtml(text).lines().map { it.trim() }.filter { it.isNotEmpty() }

        val results = mutableListOf<Pair<String, List<String>>>()
        var i = 0
        while (i < lines.size) {
            val name = lines[i].ifBlank { "بدون اسم" }
            val number = lines.getOrNull(i + 1) ?: ""
            results.add(Pair(name, if (number.isNotBlank()) listOf(number) else emptyList()))
            i += 2
        }
        return results
    }

    private fun parseContacts(html: String): List<Pair<String, List<String>>> {
        return parseFacebookAddressBook(html)
            ?: parseHtmlTable(html)
            ?: parseFallbackLines(html)
    }

    private fun buildVcf(contacts: List<Pair<String, List<String>>>): String {
        val sb = StringBuilder()
        for ((name, numbers) in contacts) {
            sb.append("BEGIN:VCARD\nVERSION:3.0\nFN:").append(name).append("\n")
            for (num in numbers) {
                if (num.contains("@")) {
                    sb.append("EMAIL:").append(num).append("\n")
                } else {
                    sb.append("TEL:").append(num).append("\n")
                }
            }
            sb.append("END:VCARD\n")
        }
        return sb.toString()
    }

    /** يُنفَّذ دومًا من خيط خلفي: يقرأ ملف HTML كاملًا، يستخرج كل الأسماء (بدون أي حد للعدد)، ويحفظ VCF */
    private fun processHtmlToVcf(uri: Uri) {
        val result = try {
            val html = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: return notifyJs("onConvertResult", JSONObject().put("result", "ERROR:تعذرت قراءة الملف").toString())

            val contacts = parseContacts(html)
            if (contacts.isEmpty()) {
                JSONObject().put("result", "ERROR:لم يتم العثور على بيانات قابلة للتحويل في هذا الملف").toString()
            } else {
                val vcf = buildVcf(contacts)
                val saveResult = writeBackupFile("contacts_converted.vcf", "text/vcard", vcf)
                JSONObject().apply {
                    put("result", saveResult)
                    put("count", contacts.size)
                }.toString()
            }
        } catch (e: Exception) {
            JSONObject().put("result", "ERROR:${e.message}").toString()
        }
        notifyJs("onConvertResult", result)
    }

    inner class Bridge {

        // معلومات حول التطبيق: الإصدار والمطور
        @JavascriptInterface
        fun getAppInfo(): String {
            val json = JSONObject()
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

        // نسخ احتياطي لجهات الاتصال إلى ملف VCF — يعمل في الخلفية ولا يجمّد الواجهة
        @JavascriptInterface
        fun backupContacts() {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
                notifyJs("onContactsResult", JSONObject().put("result", "NO_PERMISSION").toString())
                return
            }

            backgroundExecutor.execute {
                val result = try {
                    val sb = StringBuilder()
                    var count = 0
                    val cursor: Cursor? = contentResolver.query(
                        ContactsContract.Contacts.CONTENT_URI, null, null, null, null
                    )
                    cursor?.use {
                        val idIdx = it.getColumnIndex(ContactsContract.Contacts._ID)
                        val nameIdx = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                        if (idIdx < 0 || nameIdx < 0) return@use

                        while (it.moveToNext()) {
                            val id = it.getString(idIdx) ?: continue
                            val name = it.getString(nameIdx) ?: continue

                            val phoneCursor = contentResolver.query(
                                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null,
                                ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                                arrayOf(id), null
                            )
                            phoneCursor?.use { pc ->
                                val numIdx = pc.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                                if (numIdx < 0) return@use
                                while (pc.moveToNext()) {
                                    val number = pc.getString(numIdx) ?: continue
                                    sb.append("BEGIN:VCARD\n")
                                    sb.append("VERSION:3.0\n")
                                    sb.append("FN:$name\n")
                                    sb.append("TEL:$number\n")
                                    sb.append("END:VCARD\n")
                                    count++
                                }
                            }
                        }
                    }
                    val saveResult = writeBackupFile("contacts_backup.vcf", "text/vcard", sb.toString())
                    JSONObject().apply {
                        put("result", saveResult)
                        put("count", count)
                    }.toString()
                } catch (e: Exception) {
                    JSONObject().put("result", "ERROR:${e.message}").toString()
                }
                notifyJs("onContactsResult", result)
            }
        }

        // نسخ احتياطي للرسائل إلى ملف JSON — يعمل في الخلفية ولا يجمّد الواجهة
        @JavascriptInterface
        fun backupSms() {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED) {
                notifyJs("onSmsResult", JSONObject().put("result", "NO_PERMISSION").toString())
                return
            }

            backgroundExecutor.execute {
                val result = try {
                    val arr = JSONArray()
                    val cursor: Cursor? = contentResolver.query(
                        Uri.parse("content://sms/"), null, null, null, null
                    )
                    cursor?.use {
                        val addressIdx = it.getColumnIndex("address")
                        val bodyIdx = it.getColumnIndex("body")
                        val dateIdx = it.getColumnIndex("date")
                        val typeIdx = it.getColumnIndex("type")

                        while (it.moveToNext()) {
                            val obj = JSONObject()
                            obj.put("address", if (addressIdx >= 0) it.getString(addressIdx) ?: "" else "")
                            obj.put("body", if (bodyIdx >= 0) it.getString(bodyIdx) ?: "" else "")
                            obj.put("date", if (dateIdx >= 0) it.getString(dateIdx) ?: "" else "")
                            obj.put("type", if (typeIdx >= 0) it.getString(typeIdx) ?: "" else "")
                            arr.put(obj)
                        }
                    }
                    val saveResult = writeBackupFile("sms_backup.json", "application/json", arr.toString(2))
                    JSONObject().apply {
                        put("result", saveResult)
                        put("count", arr.length())
                    }.toString()
                } catch (e: Exception) {
                    JSONObject().put("result", "ERROR:${e.message}").toString()
                }
                notifyJs("onSmsResult", result)
            }
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
