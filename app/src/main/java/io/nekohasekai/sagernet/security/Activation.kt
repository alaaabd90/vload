package io.nekohasekai.sagernet.security

import android.content.Context
import java.io.File
import java.security.*
import java.security.spec.X509EncodedKeySpec
import android.util.AtomicFile

object Activation {
    const val OWNER_PUBLIC = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEJkjHhI+jwPXY3kjcMZ42JW8OTg5bpP12cxjN0V31b1n2lfASgyFjH2NPyt+nXSncApoFBtgnrMnRFKEEYmBYCw=="
    private fun record(context: Context) = File(context.noBackupFilesDir,"activation.v1")
    fun request(context: Context): String = "VLR1." + SecurityIdentity.fingerprint(context)
    private fun verified(context: Context, token: String, fresh: Boolean): Boolean = runCatching {
        require(token.length < 1024)
        val parts = token.trim().split('.')
        require(parts.size == 4 && parts[0] == "VLA1" && parts[1] == SecurityIdentity.fingerprint(context))
        val step = parts[2].toLong()
        require(step >= 0)
        if (fresh) require(kotlin.math.abs(System.currentTimeMillis()/30000 - step) <= 1)
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(android.util.Base64.decode(OWNER_PUBLIC,android.util.Base64.DEFAULT)))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key); update(parts.take(3).joinToString(".").toByteArray(Charsets.US_ASCII)); verify(SecurityIdentity.decode(parts[3]))
        }
    }.getOrDefault(false)
    fun isActive(context: Context): Boolean = SecurityIdentity.trusted(context) && runCatching {
        val file = record(context)
        file.length() in 1..1024 && verified(context, AtomicFile(file).openRead().bufferedReader().use { it.readText() }, false)
    }.getOrDefault(false)
    fun accept(context: Context, token: String): Boolean {
        if (!SecurityIdentity.trusted(context) || !verified(context,token,true)) return false
        val file = AtomicFile(record(context))
        val stream = file.startWrite()
        try { stream.write(token.trim().toByteArray()); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
        return true
    }
}
