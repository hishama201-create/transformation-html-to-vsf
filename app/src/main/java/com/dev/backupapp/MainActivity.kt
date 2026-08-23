package com.dev.backupapp

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.ContactsContract
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val PERMISSION_CODE = 100
    private val FILE_CHOOSER_CODE = 51426

    // مطلوب لتمرير نتيجة اختيار الملف إلى صفحة الـ HTML
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true

        // هذا هو الجزء الناقص: بدونه لا يستجيب الضغط على <input type="file">
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
        if (requestCode == FILE_CHOOSER_CODE) {
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

    inner class Bridge {

        // معلومات حول التطبيق: الإصدار والمطور
        @JavascriptInterface
        fun getAppInfo(): String {
            val json = JSONObject()
            json.put("version", BuildConfig.VERSION_NAME)
            json.put("developer", getString(R.string.developer_name))
            return json.toString()
        }

        // نسخ احتياطي لجهات الاتصال إلى ملف VCF داخل مجلد التنزيلات
        @JavascriptInterface
        fun backupContacts(): String {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) return "NO_PERMISSION"

            val sb = StringBuilder()
            val cursor: Cursor? = contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI, null, null, null, null
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val id = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                    val name = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)) ?: continue

                    val phoneCursor = contentResolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null,
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                        arrayOf(id), null
                    )
                    phoneCursor?.use { pc ->
                        while (pc.moveToNext()) {
                            val number = pc.getString(
                                pc.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                            )
                            sb.append("BEGIN:VCARD\n")
                            sb.append("VERSION:3.0\n")
                            sb.append("FN:$name\n")
                            sb.append("TEL:$number\n")
                            sb.append("END:VCARD\n")
                        }
                    }
                }
            }
            return saveToDownloads("contacts_backup.vcf", sb.toString())
        }

        // نسخ احتياطي للرسائل إلى ملف JSON
        @JavascriptInterface
        fun backupSms(): String {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED) return "NO_PERMISSION"

            val arr = JSONArray()
            val cursor: Cursor? = contentResolver.query(
                Uri.parse("content://sms/"), null, null, null, null
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val obj = JSONObject()
                    obj.put("address", it.getString(it.getColumnIndexOrThrow("address")) ?: "")
                    obj.put("body", it.getString(it.getColumnIndexOrThrow("body")) ?: "")
                    obj.put("date", it.getString(it.getColumnIndexOrThrow("date")) ?: "")
                    obj.put("type", it.getString(it.getColumnIndexOrThrow("type")) ?: "")
                    arr.put(obj)
                }
            }
            return saveToDownloads("sms_backup.json", arr.toString(2))
        }

        private fun saveToDownloads(filename: String, content: String): String {
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
    }
}
