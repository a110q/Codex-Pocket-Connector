package dev.codexpocket.app

import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object Encoding {
    fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    fun decode(value: String): ByteArray = Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    fun secret(): String = encode(ByteArray(32).also { SecureRandom().nextBytes(it) })
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}

class CryptoChannel(private val pairKey: String, private val hostId: String) {
    private val sid = UUID.randomUUID().toString()
    private var sequence = 0L
    private val received = linkedMapOf<String, Long>()
    private fun derive(direction: String): ByteArray {
        val extract = Mac.getInstance("HmacSHA256")
        extract.init(SecretKeySpec(hostId.toByteArray(UTF_8), "HmacSHA256"))
        val prk = extract.doFinal(Encoding.decode(pairKey))
        val expand = Mac.getInstance("HmacSHA256")
        expand.init(SecretKeySpec(prk, "HmacSHA256"))
        return expand.doFinal("codex-pocket/v1/$direction".toByteArray(UTF_8) + byteArrayOf(1))
    }
    private fun aad(direction: String, session: String, seq: Long) = "codex-pocket/v1|$hostId|$direction|$session|$seq".toByteArray(UTF_8)
    @Synchronized fun encrypt(payload: JSONObject): String {
        val seq = ++sequence
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derive("mobile-to-desktop"), "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad("mobile-to-desktop", sid, seq))
        return JSONObject().put("v", 1).put("sid", sid).put("seq", seq).put("iv", Encoding.encode(iv))
            .put("data", Encoding.encode(cipher.doFinal(payload.toString().toByteArray(UTF_8)))).toString()
    }
    @Synchronized fun decrypt(frame: JSONObject): JSONObject {
        require(frame.getInt("v") == 1)
        val session = frame.getString("sid"); val seq = frame.getLong("seq")
        require(session.length <= 64 && seq > (received[session] ?: 0)) { "重复或无效的消息" }
        val iv = Encoding.decode(frame.getString("iv")); val data = Encoding.decode(frame.getString("data"))
        require(iv.size == 12 && data.size in 16..(2 * 1024 * 1024))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(derive("desktop-to-mobile"), "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad("desktop-to-mobile", session, seq))
        val payload = JSONObject(String(cipher.doFinal(data), UTF_8))
        received[session] = seq
        if (received.size > 64) received.remove(received.keys.first())
        return payload
    }
}
