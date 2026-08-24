package com.dev.backupapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Reader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * InputStream يحسب عدد البايتات المقروءة فعليًا، تُستخدم لحساب نسبة التقدّم
 * أثناء قراءة ملفات ضخمة بدون تحميلها كاملة في الذاكرة.
 */
private class CountingInputStream(private val wrapped: InputStream) : InputStream() {
    var bytesRead: Long = 0
        private set
    override fun read(): Int {
        val b = wrapped.read()
        if (b >= 0) bytesRead++
        return b
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = wrapped.read(b, off, len)
        if (n > 0) bytesRead += n
        return n
    }
    override fun close() = wrapped.close()
}

private data class OpenStreamResult(val stream: OutputStream?, val uriString: String?, val error: String?)

/**
 * خدمة أمامية (Foreground Service) تنفذ كل عمليات النسخ الاحتياطي الثقيلة
 * (رسائل / جهات اتصال / تحويل HTML) في خيط خلفي منفصل عن الواجهة، وتستمر
 * بالعمل حتى لو أغلق المستخدم التطبيق أو انتقل لتطبيق آخر، مع إشعار دائم
 * يعرض نسبة التقدّم الفعلية بدل رسالة "جارِ الانتظار" الثابتة.
 */
class BackupService : Service() {

    companion object {
        const val ACTION_BACKUP_CONTACTS = "com.dev.backupapp.action.BACKUP_CONTACTS"
        const val ACTION_BACKUP_SMS = "com.dev.backupapp.action.BACKUP_SMS"
        const val ACTION_CONVERT_HTML = "com.dev.backupapp.action.CONVERT_HTML"

        private const val CHANNEL_ID = "backup_progress_channel"
        private const val NOTIF_ID = 501

        private const val PREFS_NAME = "backup_pro_prefs"
        private const val KEY_TREE_URI = "save_tree_uri"
        const val KEY_PENDING_CONTACTS = "pending_result_contacts"
        const val KEY_PENDING_SMS = "pending_result_sms"
        const val KEY_PENDING_CONVERT = "pending_result_convert"
    }

    /** واجهة بسيطة تربط الخدمة بالواجهة (Activity) عندما تكون مفتوحة، للتحديث الفوري بدون انتظار الإشعار فقط */
    interface ProgressListener {
        fun onProgress(op: String, percent: Int, message: String)
        fun onFinished(op: String, resultJson: String)
    }

    object Bus {
        @Volatile var listener: ProgressListener? = null
    }

    private lateinit var executor: ExecutorService
    private var activeJobs = AtomicInteger(0)

    override fun onCreate() {
        super.onCreate()
        executor = Executors.newSingleThreadExecutor()
        createChannelIfNeeded()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == null) {
            stopSelfIfIdle()
            return START_NOT_STICKY
        }

        // ألغِ فورًا أي إشعار "اكتمل ✅" متبقٍّ من عملية سابقة، حتى لا يظهر مع إشعار
        // التقدّم الجديد في نفس اللحظة ويبدو وكأنه إشعار مكرر أو متأخر
        notifGeneration.incrementAndGet()
        mainHandler.removeCallbacksAndMessages(null)
        if (hasNotificationPermission()) NotificationManagerCompat.from(this).cancel(NOTIF_ID)

        activeJobs.incrementAndGet()
        startForeground(NOTIF_ID, buildNotification("جارِ التحضير...", 0, true))

        executor.execute {
            try {
                when (action) {
                    ACTION_BACKUP_CONTACTS -> runContactsBackup()
                    ACTION_BACKUP_SMS -> runSmsBackup()
                    ACTION_CONVERT_HTML -> {
                        val uri = intent.data
                        if (uri != null) runConvertHtml(uri)
                        else finishOp("convert", JSONObject().put("result", "ERROR:لم يتم اختيار أي ملف").toString(), KEY_PENDING_CONVERT)
                    }
                }
            } finally {
                if (activeJobs.decrementAndGet() <= 0) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun stopSelfIfIdle() {
        if (activeJobs.get() <= 0) stopSelf()
    }

    // ================== إشعارات وتقدّم ==================

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val notifGeneration = AtomicInteger(0)

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "النسخ الاحتياطي", NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "إشعارات تقدّم النسخ الاحتياطي والتحويل"
                    setShowBadge(false)
                }
                mgr.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(text: String, percent: Int, indeterminate: Boolean): Notification {
        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setProgress(100, percent, indeterminate)
            .build()
    }

    /** نفس رقم الإشعار (NOTIF_ID) يُستخدم للتقدّم وللاكتمال معًا، فيستبدل أحدهما الآخر
     * بدل أن يظهر إشعاران منفصلان في وقت واحد */
    private fun updateNotification(text: String, percent: Int) {
        val notif = buildNotification(text, percent, false)
        if (hasNotificationPermission()) {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, notif)
        }
    }

    private fun showCompletedNotification(text: String) {
        if (!hasNotificationPermission()) return
        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 1, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        NotificationManagerCompat.from(this).notify(NOTIF_ID, notif)

        // اختفِ تلقائيًا بعد ثوانٍ قليلة، إلا إذا بدأت عملية جديدة بالفعل قبل ذلك
        val myGeneration = notifGeneration.get()
        mainHandler.postDelayed({
            if (notifGeneration.get() == myGeneration && hasNotificationPermission()) {
                NotificationManagerCompat.from(this).cancel(NOTIF_ID)
            }
        }, 6000)
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** يبلّغ الواجهة (إن كانت مفتوحة) فورًا، ويخزّن النتيجة في التفضيلات لعرضها لاحقًا إن كان التطبيق مغلقًا */
    private fun finishOp(op: String, resultJson: String, prefKey: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(prefKey, resultJson).apply()
        Bus.listener?.onFinished(op, resultJson)
    }

    private fun reportProgress(op: String, percent: Int, message: String) {
        updateNotification(message, percent)
        Bus.listener?.onProgress(op, percent, message)
    }

    // ================== حفظ الملفات (نفس منطق المسار المختار / Downloads) ==================

    private fun getSavedTreeUri(): Uri? {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_TREE_URI, null) ?: return null
        return try {
            val uri = Uri.parse(stored)
            val hasPermission = contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isWritePermission
            }
            if (hasPermission) uri else null
        } catch (e: Exception) {
            null
        }
    }

    private fun writeBackupFile(filename: String, mimeType: String, content: String): String {
        val treeUri = getSavedTreeUri()
        if (treeUri != null) {
            try {
                val dir = DocumentFile.fromTreeUri(this, treeUri)
                    ?: return "ERROR:تعذر فتح المجلد المختار"
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
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = contentResolver
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                resolver.query(
                    collection, arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(filename), null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                        resolver.delete(ContentUris.withAppendedId(collection, id), null, null)
                    }
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val itemUri = resolver.insert(collection, values)
                    ?: return "ERROR:تعذر إنشاء الملف في مجلد التنزيلات"
                resolver.openOutputStream(itemUri)?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                } ?: return "ERROR:تعذر الكتابة في الملف"
                "OK:${itemUri}"
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, filename)
                FileWriter(file).use { it.write(content) }
                "OK:${file.absolutePath}"
            }
        } catch (e: Exception) {
            "ERROR:${e.message}"
        }
    }

    // ================== جهات الاتصال: نسخة سريعة (استعلامان فقط بدل استعلام لكل جهة اتصال) ==================

    private fun runContactsBackup() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            finishOp("contacts", JSONObject().put("result", "NO_PERMISSION").toString(), KEY_PENDING_CONTACTS)
            return
        }

        val result = try {
            reportProgress("contacts", 0, "جارِ قراءة جهات الاتصال...")

            // خريطة: معرف جهة الاتصال -> (الاسم، قائمة الأرقام) — تُبنى من استعلام واحد فقط
            // على جدول أرقام الهواتف بدل عمل استعلام منفصل لكل جهة اتصال (وهذا كان سبب البطء الشديد سابقًا)
            val numbersMap = LinkedHashMap<String, Pair<String, MutableList<String>>>()

            val phoneCursor: Cursor? = contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null,
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " ASC"
            )
            val totalPhoneRows = phoneCursor?.count ?: 0
            phoneCursor?.use {
                val idIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                var row = 0
                while (it.moveToNext()) {
                    row++
                    if (idIdx < 0 || numIdx < 0) continue
                    val id = it.getString(idIdx) ?: continue
                    val number = it.getString(numIdx)?.trim() ?: ""
                    val name = (if (nameIdx >= 0) it.getString(nameIdx) else null) ?: "بدون اسم"
                    val entry = numbersMap.getOrPut(id) { Pair(name, mutableListOf()) }
                    if (number.isNotBlank() && !entry.second.contains(number)) entry.second.add(number)

                    if (totalPhoneRows > 0 && row % 40 == 0) {
                        val percent = (row * 80 / totalPhoneRows).coerceIn(0, 80)
                        reportProgress("contacts", percent, "جارِ المعالجة... ($row/$totalPhoneRows)")
                    }
                }
            }

            // استعلام ثانٍ وحيد لإضافة جهات الاتصال التي لا تملك أي رقم هاتف (حتى لا تُفقد من النسخة الاحتياطية)
            val allCursor: Cursor? = contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
                null, null, null
            )
            allCursor?.use {
                val idIdx = it.getColumnIndex(ContactsContract.Contacts._ID)
                val nameIdx = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                while (it.moveToNext()) {
                    if (idIdx < 0) continue
                    val id = it.getString(idIdx) ?: continue
                    if (!numbersMap.containsKey(id)) {
                        val name = (if (nameIdx >= 0) it.getString(nameIdx) else null) ?: "بدون اسم"
                        numbersMap[id] = Pair(name, mutableListOf())
                    }
                }
            }

            reportProgress("contacts", 90, "جارِ حفظ الملف...")

            val sb = StringBuilder()
            for ((_, pair) in numbersMap) {
                val (name, numbers) = pair
                sb.append("BEGIN:VCARD\nVERSION:3.0\nFN:").append(name).append("\n")
                for (num in numbers) sb.append("TEL:").append(num).append("\n")
                sb.append("END:VCARD\n")
            }

            val saveResult = writeBackupFile("contacts_backup.vcf", "text/vcard", sb.toString())
            JSONObject().apply {
                put("result", saveResult)
                put("count", numbersMap.size)
            }.toString()
        } catch (e: Exception) {
            JSONObject().put("result", "ERROR:${e.message}").toString()
        }

        val ok = result.contains("\"OK:")
        showCompletedNotification(if (ok) "اكتمل النسخ الاحتياطي لجهات الاتصال ✅" else "فشل النسخ الاحتياطي لجهات الاتصال")
        finishOp("contacts", result, KEY_PENDING_CONTACTS)
    }

    // ================== الرسائل ==================

    private fun runSmsBackup() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            finishOp("sms", JSONObject().put("result", "NO_PERMISSION").toString(), KEY_PENDING_SMS)
            return
        }

        val result = try {
            reportProgress("sms", 0, "جارِ قراءة الرسائل...")
            val arr = JSONArray()
            val cursor: Cursor? = contentResolver.query(Uri.parse("content://sms/"), null, null, null, null)
            val total = cursor?.count ?: 0
            cursor?.use {
                val addressIdx = it.getColumnIndex("address")
                val bodyIdx = it.getColumnIndex("body")
                val dateIdx = it.getColumnIndex("date")
                val typeIdx = it.getColumnIndex("type")
                var row = 0
                while (it.moveToNext()) {
                    row++
                    val obj = JSONObject()
                    obj.put("address", if (addressIdx >= 0) it.getString(addressIdx) ?: "" else "")
                    obj.put("body", if (bodyIdx >= 0) it.getString(bodyIdx) ?: "" else "")
                    obj.put("date", if (dateIdx >= 0) it.getString(dateIdx) ?: "" else "")
                    obj.put("type", if (typeIdx >= 0) it.getString(typeIdx) ?: "" else "")
                    arr.put(obj)

                    if (total > 0 && row % 100 == 0) {
                        val percent = (row * 90 / total).coerceIn(0, 90)
                        reportProgress("sms", percent, "جارِ المعالجة... ($row/$total)")
                    }
                }
            }
            reportProgress("sms", 95, "جارِ حفظ الملف...")
            val saveResult = writeBackupFile("sms_backup.json", "application/json", arr.toString(2))
            JSONObject().apply {
                put("result", saveResult)
                put("count", arr.length())
            }.toString()
        } catch (e: Exception) {
            JSONObject().put("result", "ERROR:${e.message}").toString()
        }

        val ok = result.contains("\"OK:")
        showCompletedNotification(if (ok) "اكتمل النسخ الاحتياطي للرسائل ✅" else "فشل النسخ الاحتياطي للرسائل")
        finishOp("sms", result, KEY_PENDING_SMS)
    }

    // ================== تحويل HTML إلى VCF (نسخة تدفّقية Streaming) ==================
    // بدل تحميل الملف كاملًا في الذاكرة (وهو ما كان يسبب استهلاك عدة غيغابايت من الرام
    // مع ملفات كبيرة ويجبر النظام على قتل تطبيقات أخرى)، نقرأ الملف على شكل أجزاء صغيرة
    // (حتى ٢٠٠ ألف حرف بالمرة)، نحلل كل جزء فور اكتماله، نكتب نتيجته مباشرة إلى ملف VCF
    // على القرص، ثم نتخلص من ذلك الجزء من الذاكرة قبل قراءة الجزء التالي. بهذا تبقى
    // الذاكرة المستخدمة محدودة بحجم الجزء تقريبًا، بغض النظر عن حجم ملف HTML الأصلي.

    private fun unescapeHtml(s: String): String = s
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'")
        .replace("&apos;", "'").replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").trim()

    private fun stripTags(s: String): String = s.replace(Regex("<[^>]*>"), "").trim()

    private fun parseFacebookBlock(block: String): Pair<String, List<String>>? {
        val nameRegex = Regex("_a6-h[^\"]*\">(.*?)</div>", RegexOption.DOT_MATCHES_ALL)
        val numRegex = Regex("_a6_p\">(.*?)</div>", RegexOption.DOT_MATCHES_ALL)
        val window = if (block.length > 4000) block.substring(0, 4000) else block
        val name = nameRegex.find(window)?.groupValues?.get(1)?.let { unescapeHtml(stripTags(it)) } ?: ""
        val nums = numRegex.findAll(window)
            .map { unescapeHtml(stripTags(it.groupValues[1])) }
            .filter { it.isNotBlank() }.toList()
        return if (name.isNotBlank() || nums.isNotEmpty()) Pair(name.ifBlank { "بدون اسم" }, nums) else null
    }

    private fun parseTableRow(row: String): Pair<String, List<String>>? {
        val cellRegex = Regex("<t[dh][^>]*>(.*?)</t[dh]>", RegexOption.DOT_MATCHES_ALL)
        val inner = Regex("<tr[^>]*>(.*)</tr>", RegexOption.DOT_MATCHES_ALL).find(row)?.groupValues?.get(1) ?: row
        val cells = cellRegex.findAll(inner).map { unescapeHtml(stripTags(it.groupValues[1])) }.toList()
        if (cells.size < 2) return null
        val name = cells[0].ifBlank { "بدون اسم" }
        val number = cells[1]
        return if (name.isNotBlank() || number.isNotBlank())
            Pair(name, if (number.isNotBlank()) listOf(number) else emptyList()) else null
    }

    private fun readFully(reader: Reader, buf: CharArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = reader.read(buf, total, buf.size - total)
            if (n == -1) break
            total += n
        }
        return total
    }

    /** تحليل تدفّقي لملفات تصدير جهات اتصال فيسبوك، حسب مواضع الفواصل (marker) فقط،
     * بدون الاحتفاظ بأكثر من جزء صغير من الملف في الذاكرة بأي لحظة. */
    private fun streamFacebookFormat(
        reader: Reader, initial: String, eofAlready: Boolean,
        onContact: (String, List<String>) -> Unit
    ) {
        val marker = "<div class=\"_a6-g\">"
        val sb = StringBuilder(initial)
        var eof = eofAlready
        val chunkBuf = CharArray(200_000)
        while (true) {
            var searchFrom = 0
            while (true) {
                val idx1 = sb.indexOf(marker, searchFrom)
                if (idx1 == -1) { searchFrom = maxOf(0, sb.length - marker.length + 1); break }
                val idx2 = sb.indexOf(marker, idx1 + marker.length)
                if (idx2 == -1) {
                    if (eof) { parseFacebookBlock(sb.substring(idx1))?.let { onContact(it.first, it.second) }; searchFrom = sb.length }
                    break
                } else {
                    parseFacebookBlock(sb.substring(idx1, idx2))?.let { onContact(it.first, it.second) }
                    searchFrom = idx2
                }
            }
            if (searchFrom > 0) sb.delete(0, searchFrom)
            if (eof) break
            val n = reader.read(chunkBuf)
            if (n == -1) eof = true else sb.append(chunkBuf, 0, n)
        }
    }

    /** تحليل تدفّقي لجدول HTML، صفًا بصف، بنفس مبدأ التقطيع المحدود الذاكرة. */
    private fun streamTableFormat(
        reader: Reader, initial: String, eofAlready: Boolean,
        onContact: (String, List<String>) -> Unit
    ) {
        val sb = StringBuilder(initial)
        var eof = eofAlready
        val chunkBuf = CharArray(200_000)
        while (true) {
            var searchFrom = 0
            while (true) {
                val idx1 = sb.indexOf("<tr", searchFrom)
                if (idx1 == -1) { searchFrom = maxOf(0, sb.length - 4); break }
                val idx2 = sb.indexOf("</tr>", idx1)
                if (idx2 == -1) {
                    if (eof) searchFrom = sb.length
                    break
                } else {
                    val end = idx2 + 5
                    parseTableRow(sb.substring(idx1, end))?.let { onContact(it.first, it.second) }
                    searchFrom = end
                }
            }
            if (searchFrom > 0) sb.delete(0, searchFrom)
            if (eof) break
            val n = reader.read(chunkBuf)
            if (n == -1) eof = true else sb.append(chunkBuf, 0, n)
        }
    }

    /** احتياطي: قراءة سطرًا بسطر عندما لا يُعرف تنسيق الملف — أخف الأنماط ذاكرة أصلًا. */
    private fun streamFallbackFormat(
        reader: BufferedReader, initial: String, eofAlready: Boolean,
        onContact: (String, List<String>) -> Unit
    ) {
        var pendingName: String? = null
        var inScript = false
        fun handleLine(rawLine: String) {
            if (rawLine.contains("<script", ignoreCase = true)) inScript = true
            if (!inScript) {
                val line = unescapeHtml(stripTags(rawLine)).trim()
                if (line.isNotEmpty()) {
                    val name = pendingName
                    if (name == null) pendingName = line
                    else { onContact(name.ifBlank { "بدون اسم" }, listOf(line)); pendingName = null }
                }
            }
            if (rawLine.contains("</script", ignoreCase = true)) inScript = false
        }
        initial.lineSequence().forEach { handleLine(it) }
        if (!eofAlready) {
            var line = reader.readLine()
            while (line != null) { handleLine(line); line = reader.readLine() }
        }
        pendingName?.let { onContact(it.ifBlank { "بدون اسم" }, emptyList()) }
    }

    private fun writeVcard(writer: BufferedWriter, name: String, numbers: List<String>) {
        writer.write("BEGIN:VCARD\nVERSION:3.0\nFN:"); writer.write(name); writer.write("\n")
        for (num in numbers) {
            if (num.contains("@")) { writer.write("EMAIL:"); writer.write(num); writer.write("\n") }
            else { writer.write("TEL:"); writer.write(num); writer.write("\n") }
        }
        writer.write("END:VCARD\n")
    }

    private fun getFileSize(uri: Uri): Long = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && !c.isNull(idx)) c.getLong(idx) else -1L
            } else -1L
        } ?: -1L
    } catch (e: Exception) { -1L }

    /** نفس منطق writeBackupFile، لكن يُرجع مجرى كتابة (Stream) بدل استقبال المحتوى كاملًا،
     * لنتمكن من كتابة كل جهة اتصال فور استخراجها بدل تجميعها كلها في نص واحد ضخم بالذاكرة. */
    private fun openBackupOutputStream(filename: String, mimeType: String): OpenStreamResult {
        val treeUri = getSavedTreeUri()
        if (treeUri != null) {
            return try {
                val dir = DocumentFile.fromTreeUri(this, treeUri)
                    ?: return OpenStreamResult(null, null, "تعذر فتح المجلد المختار")
                dir.findFile(filename)?.delete()
                val newFile = dir.createFile(mimeType, filename)
                    ?: return OpenStreamResult(null, null, "تعذر إنشاء الملف في المجلد المختار")
                val out = contentResolver.openOutputStream(newFile.uri)
                    ?: return OpenStreamResult(null, null, "تعذر الكتابة في الملف")
                OpenStreamResult(out, newFile.uri.toString(), null)
            } catch (e: Exception) {
                OpenStreamResult(null, null, e.message)
            }
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = contentResolver
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                resolver.query(
                    collection, arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(filename), null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                        resolver.delete(ContentUris.withAppendedId(collection, id), null, null)
                    }
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val itemUri = resolver.insert(collection, values)
                    ?: return OpenStreamResult(null, null, "تعذر إنشاء الملف في مجلد التنزيلات")
                val out = resolver.openOutputStream(itemUri)
                    ?: return OpenStreamResult(null, null, "تعذر الكتابة في الملف")
                OpenStreamResult(out, itemUri.toString(), null)
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, filename)
                OpenStreamResult(FileOutputStream(file), file.absolutePath, null)
            }
        } catch (e: Exception) {
            OpenStreamResult(null, null, e.message)
        }
    }

    private fun runConvertHtml(uri: Uri) {
        var writer: BufferedWriter? = null
        var savedUri: String? = null
        var count = 0

        val result = try {
            reportProgress("convert", 2, "جارِ التحضير...")
            val totalSize = getFileSize(uri)

            val rawInput = contentResolver.openInputStream(uri)
                ?: return finishOp("convert", JSONObject().put("result", "ERROR:تعذرت قراءة الملف").toString(), KEY_PENDING_CONVERT)
            val counting = CountingInputStream(rawInput)
            val reader = BufferedReader(InputStreamReader(counting, Charsets.UTF_8), 1 shl 16)

            val outOpen = openBackupOutputStream("contacts_converted.vcf", "text/vcard")
            if (outOpen.stream == null) {
                reader.close()
                return finishOp("convert", JSONObject().put("result", "ERROR:${outOpen.error ?: "تعذر إنشاء ملف الحفظ"}").toString(), KEY_PENDING_CONVERT)
            }
            savedUri = outOpen.uriString
            val activeWriter = BufferedWriter(OutputStreamWriter(outOpen.stream, Charsets.UTF_8), 1 shl 16)
            writer = activeWriter

            var lastPercent = -1
            val onContact: (String, List<String>) -> Unit = { name, nums ->
                writeVcard(activeWriter, name, nums)
                count++
                val percent = if (totalSize > 0)
                    (10 + (counting.bytesRead * 80 / totalSize)).toInt().coerceIn(10, 90)
                else (10 + (count / 200)).coerceIn(10, 90)
                if (percent != lastPercent) {
                    lastPercent = percent
                    reportProgress("convert", percent, "جارِ المعالجة... ($count جهة اتصال حتى الآن)")
                }
            }

            // عيّنة أولى (محدودة الحجم) لتحديد نوع الملف دون تحميله كاملًا
            val sampleBuf = CharArray(500_000)
            val sampleLen = readFully(reader, sampleBuf)
            val sample = String(sampleBuf, 0, sampleLen)
            val eofAfterSample = sampleLen < sampleBuf.size

            when {
                sample.contains("<div class=\"_a6-g\">") ->
                    streamFacebookFormat(reader, sample, eofAfterSample, onContact)
                sample.contains("<tr") ->
                    streamTableFormat(reader, sample, eofAfterSample, onContact)
                else ->
                    streamFallbackFormat(reader, sample, eofAfterSample, onContact)
            }

            activeWriter.flush()
            activeWriter.close()
            reader.close()
            writer = null

            if (count == 0) {
                JSONObject().put("result", "ERROR:لم يتم العثور على بيانات قابلة للتحويل في هذا الملف").toString()
            } else {
                reportProgress("convert", 95, "جارِ إنهاء الحفظ...")
                JSONObject().apply {
                    put("result", "OK:$savedUri")
                    put("count", count)
                }.toString()
            }
        } catch (e: Exception) {
            try { writer?.close() } catch (e2: Exception) { /* تجاهل */ }
            JSONObject().put("result", "ERROR:${e.message}").toString()
        }

        val ok = result.contains("\"OK:")
        showCompletedNotification(if (ok) "اكتمل تحويل الملف ✅" else "فشل تحويل الملف")
        finishOp("convert", result, KEY_PENDING_CONVERT)
    }
}
