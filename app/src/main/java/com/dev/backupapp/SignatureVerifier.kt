package com.dev.backupapp

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.util.Log
import java.security.MessageDigest

/**
 * يتحقق من أن التطبيق المُثبَّت موقّع فعلًا بمفتاحك الخاص (backup-release-key.keystore)
 * وليس نسخة أُعيد تجميعها وتوقيعها بمفتاح آخر بعد فك ضغطها والتعديل عليها.
 *
 * === خطوات التفعيل (مهم جدًا) ===
 * 1) ابنِ نسخة release واحدة ونصّبها على جهازك.
 * 2) افتح Logcat (أو راقب رسالة الـ Toast التحذيرية التي تظهر مؤقتًا) وابحث عن السطر:
 *      "بصمة التوقيع الحالية: XXXXXXXX..."
 * 3) انسخ تلك البصمة بالكامل، وضعها بدل قيمة EXPECTED_SIGNATURE_SHA256 أدناه.
 * 4) أعد بناء ورفع نسخة release جديدة. من هذه اللحظة سيرفض التطبيق العمل
 *    على أي نسخة موقّعة بمفتاح مختلف عن مفتاحك.
 *
 * ملاحظة: طالما القيمة أدناه لا تزال "REPLACE_ME"، الفحص يبقى معطّلًا تلقائيًا
 * (لن يمنع أحدًا من التشغيل) حتى لا يتعطل بناء المشروع قبل أن تضع البصمة الصحيحة.
 */
object SignatureVerifier {

    private const val TAG = "SignatureVerifier"

    // ضع هنا بصمة SHA-256 الحقيقية لمفتاح التوقيع الخاص بك بعد أول بناء (بالخطوات أعلاه)
    private const val EXPECTED_SIGNATURE_SHA256 = "REPLACE_ME"

    /** يُرجع true إذا كان التوقيع صحيحًا أو إذا كان الفحص غير مُفعَّل بعد (REPLACE_ME) */
    fun isSignatureValid(context: Context): Boolean {
        if (EXPECTED_SIGNATURE_SHA256 == "REPLACE_ME") {
            // الفحص غير مُفعَّل بعد — لا نمنع التشغيل، فقط نساعدك على استخراج البصمة الصحيحة
            return true
        }
        val current = getSigningSha256(context) ?: return false
        return current.equals(EXPECTED_SIGNATURE_SHA256, ignoreCase = true)
    }

    /** يُرجع بصمة SHA-256 (بصيغة سداسية عشرية) لشهادة التوقيع الحالية، أو null عند أي خطأ */
    fun getSigningSha256(context: Context): String? {
        return try {
            val signatures = getSignatures(context) ?: return null
            if (signatures.isEmpty()) return null
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(signatures[0].toByteArray())
            digest.digest().joinToString("") { "%02X".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "تعذر حساب بصمة التوقيع: ${e.message}")
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun getSignatures(context: Context): Array<Signature>? {
        val pm = context.packageManager
        val packageName = context.packageName
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signingInfo = info.signingInfo ?: return null
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            info.signatures
        }
    }
}
