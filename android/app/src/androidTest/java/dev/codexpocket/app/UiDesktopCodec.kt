package dev.codexpocket.app
import org.json.JSONObject
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
internal class UiDesktopCodec(pairKey:String,private val host:String){
        private val sid=UUID.randomUUID().toString();private var seq=0L
        private val root=Encoding.decode(pairKey)
        private fun key(direction:String):ByteArray{
            val extract=Mac.getInstance("HmacSHA256").apply{init(SecretKeySpec(host.toByteArray(),"HmacSHA256"))}.doFinal(root)
            return Mac.getInstance("HmacSHA256").apply{init(SecretKeySpec(extract,"HmacSHA256"))}.doFinal("codex-pocket/v1/$direction".toByteArray()+byteArrayOf(1))
        }
        @Synchronized fun encode(payload:JSONObject):String{
            val direction="desktop-to-mobile";val n=++seq;val iv=ByteArray(12).also{java.security.SecureRandom().nextBytes(it)}
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.ENCRYPT_MODE,SecretKeySpec(key(direction),"AES"),GCMParameterSpec(128,iv));updateAAD("codex-pocket/v1|$host|$direction|$sid|$n".toByteArray())}
            return JSONObject().put("v",1).put("sid",sid).put("seq",n).put("iv",Encoding.encode(iv)).put("data",Encoding.encode(cipher.doFinal(payload.toString().toByteArray()))).toString()
        }
        fun decode(text:String):JSONObject{
            val frame=JSONObject(text);val direction="mobile-to-desktop"
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.DECRYPT_MODE,SecretKeySpec(key(direction),"AES"),GCMParameterSpec(128,Encoding.decode(frame.getString("iv"))));updateAAD("codex-pocket/v1|$host|$direction|${frame.getString("sid")}|${frame.getLong("seq")}".toByteArray())}
            return JSONObject(String(cipher.doFinal(Encoding.decode(frame.getString("data")))))
        }
    }
