package dev.codexpocket.app

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class PairingHttpException(val status:Int,message:String):Exception(message)

class PocketConnection(private val profile: JSONObject, private val onStatus: (ConnectionState) -> Unit, private val onEvent: (JSONObject) -> Unit, private val handshakeTimeoutMillis: Long = 20000) {
    private val pending = ConcurrentHashMap<String, (JSONObject?, String?) -> Unit>()
    private var socket: WebSocket? = null
    private val crypto = CryptoChannel(profile.getString("key"), profile.getString("hostId"))
    private val channel = UUID.randomUUID().toString()
    private val client = httpClient(profile.getString("pin"))
    private val timeouts = Executors.newSingleThreadScheduledExecutor()
    private val ended = AtomicBoolean(false)
    private var handshake: ScheduledFuture<*>? = null
    @Volatile var ready = false; private set

    fun connect() {
        onStatus(ConnectionState.CONNECTING)
        val url = profile.getString("relay").replaceFirst("https://", "wss://").trimEnd('/') + "/relay"
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${profile.getString("mobileToken")}")
            .header("X-Pocket-Host", profile.getString("hostId")).header("X-Pocket-Role", "mobile")
            .apply { profile.optString("accountToken").takeIf { it.isNotBlank() }?.let { header("X-Pocket-Session",it) } }.build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { if(ended.get()){webSocket.cancel();return};onStatus(ConnectionState.RELAY_CONNECTED);armHandshake() }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (ended.get()) return
                try {
                    val frame = JSONObject(text)
                    if (frame.optString("type") == "status") {
                        if (frame.optBoolean("peerOnline")) {
                            ready = false
                            onStatus(ConnectionState.CONNECTING_DESKTOP);armHandshake()
                            if(!webSocket.send(crypto.encrypt(JSONObject().put("type", "hello").put("channel", channel).put("sentAt", System.currentTimeMillis()))))finish(ConnectionState.RECONNECTING)
                        } else { cancelHandshake();ready = false; onStatus(ConnectionState.COMPUTER_OFFLINE);failPending("电脑已离线；操作结果待确认，请恢复连接后检查") }
                        return
                    }
                    val payload = crypto.decrypt(frame)
                    if (payload.optString("channel") != channel) return
                    when (payload.optString("type")) {
                        "hello" -> { cancelHandshake();ready = payload.optBoolean("desktopReady"); onStatus(if (ready) ConnectionState.READY else ConnectionState.DESKTOP_UNAVAILABLE); onEvent(payload) }
                        "response" -> pending.remove(payload.optString("id"))?.invoke((payload.optJSONObject("result") ?: JSONObject()).put("_confirmed", true).put("_uncertain", payload.optBoolean("uncertain")), payload.optString("error").takeIf { it.isNotBlank() })
                        "event" -> {
                            val eventType = payload.optJSONObject("event")?.optString("type")
                            if (eventType == "desktop.ready") { cancelHandshake();ready = true; onStatus(ConnectionState.READY); onEvent(JSONObject().put("type", "hello")) }
                            if (eventType == "desktop.disconnected") { ready = false; onStatus(ConnectionState.DESKTOP_DISCONNECTED);failPending("电脑上的 Codex 已断开；操作结果待确认") }
                            if (eventType == "desktop.incompatible") finish(ConnectionState.INCOMPATIBLE)
                            onEvent(payload)
                        }
                    }
                } catch (_: Exception) { finish(ConnectionState.INVALID_DATA) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { finish(when(response?.code){401->ConnectionState.AUTH_FAILED;403->ConnectionState.ACCESS_DENIED;else->ConnectionState.RECONNECTING}) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { finish(when(code){4000->ConnectionState.REPLACED;4001->ConnectionState.REVOKED;4003->ConnectionState.ACCESS_DENIED;else->ConnectionState.CLOSED}) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        })
    }
    @Synchronized private fun armHandshake() {
        if(ended.get())return
        handshake?.cancel(false)
        handshake=timeouts.schedule({finish(ConnectionState.HANDSHAKE_TIMEOUT)},handshakeTimeoutMillis,TimeUnit.MILLISECONDS)
    }
    @Synchronized private fun cancelHandshake(){handshake?.cancel(false);handshake=null}
    private fun failPending(message:String) {
        pending.keys.toList().forEach { id -> pending.remove(id)?.invoke(null,message) }
    }
    @Synchronized private fun finish(state:ConnectionState) {
        if(!ended.compareAndSet(false,true))return
        ready=false;cancelHandshake();socket?.cancel();timeouts.shutdownNow()
        client.dispatcher.cancelAll();client.connectionPool.evictAll()
        onStatus(state)
        failPending("连接中断；操作结果待确认，请刷新会话后检查")
    }
    @Synchronized fun request(action: String, args: JSONObject = JSONObject(), id: String = UUID.randomUUID().toString(), callback: (JSONObject?, String?) -> Unit) {
        if (!ready || ended.get()) { callback(null, "电脑尚未连接"); return }
        pending[id] = callback
        val body = JSONObject().put("type", "request").put("channel", channel).put("id", id).put("action", action).put("args", args).put("sentAt", System.currentTimeMillis())
        if (socket?.send(crypto.encrypt(body)) != true) pending.remove(id)?.invoke(null, "发送失败")
        timeouts.schedule({
            pending.remove(id)?.invoke(null, "请求超时；请刷新查看操作结果，避免重复发送")
        }, 45, TimeUnit.SECONDS)
    }
    @Synchronized fun close() {
        if(!ended.compareAndSet(false,true))return
        cancelHandshake();socket?.cancel(); socket = null; ready = false
        failPending("连接已暂停；操作结果待确认，返回 App 后请刷新检查")
        client.dispatcher.cancelAll();client.connectionPool.evictAll();timeouts.shutdownNow()
    }

    companion object {
        fun isLanPairing(profile:JSONObject):Boolean {
            if(profile.optString("mode")!="lan")return false
            val uri=try{URI(profile.getString("relay"))}catch(_:Exception){return false}
            val parts=uri.host?.split('.')?:return false
            if(parts.size!=4||parts.any{!it.matches(Regex("0|[1-9][0-9]{0,2}"))||(it.toIntOrNull()?:256)>255})return false
            val a=parts[0].toInt();val b=parts[1].toInt()
            return uri.scheme=="https"&&uri.userInfo==null&&uri.query==null&&uri.fragment==null&&(uri.path.isNullOrBlank()||uri.path=="/")&&(a==10||(a==172&&b in 16..31)||(a==192&&b==168))
        }
        fun validatePairing(text: String): JSONObject {
            val raw = text.trim()
            val data = if (raw.startsWith("codexpocket://pair?data=")) String(Encoding.decode(raw.substringAfter("data=")), Charsets.UTF_8) else raw
            val json = JSONObject(data)
            require(json.getInt("v") == 1)
            val uri = URI(json.getString("relay"))
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query==null && uri.fragment==null && (uri.path.isNullOrBlank() || uri.path == "/")) { "中继地址必须为 HTTPS" }
            require(json.getString("hostId").matches(Regex("[A-Za-z0-9_-]{16,64}")))
            require(json.getString("key").matches(Regex("[A-Za-z0-9_-]{43}")))
            require(json.getString("pairCode").matches(Regex("[A-Za-z0-9_-]{43}")))
            require(json.getString("pin").matches(Regex("[a-f0-9]{64}")))
            require(json.getLong("expiresAt") > System.currentTimeMillis()) { "配对码已过期，请在电脑上重新生成" }
            return json
        }
        fun httpClient(pin: String): OkHttpClient {
            require(pin.matches(Regex("[a-f0-9]{64}")))
            val trust = object : X509TrustManager {
                override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
                override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) { throw java.security.cert.CertificateException("Not a server") }
                override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                    if (chain.isEmpty()) throw java.security.cert.CertificateException("Missing certificate")
                    chain[0].checkValidity()
                    val actual = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
                    if (!MessageDigest.isEqual(actual, pin.chunked(2).map { it.toInt(16).toByte() }.toByteArray())) throw java.security.cert.CertificateException("服务器证书与配对信息不一致")
                }
            }
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
            // Keep OkHttp's normal hostname verification; only this exact pinned certificate is trusted.
            return OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, trust).connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).build()
        }
        fun post(profile: JSONObject, path: String, body: JSONObject): JSONObject {
            val request = Request.Builder().url(profile.getString("relay").trimEnd('/') + path)
                .apply { profile.optString("accountToken").takeIf { it.isNotBlank() }?.let { header("Authorization","Bearer $it") } }
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            httpClient(profile.getString("pin")).newCall(request).execute().use { response ->
                val result = JSONObject(response.body?.string() ?: "{}")
                if (!response.isSuccessful) throw PairingHttpException(response.code,result.optString("error", "请求失败 ${response.code}"))
                return result
            }
        }
    }
}
