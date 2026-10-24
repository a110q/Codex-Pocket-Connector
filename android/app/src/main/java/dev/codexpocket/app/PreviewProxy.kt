package dev.codexpocket.app

import android.util.Base64
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** Loopback HTTP proxy used only by the preview WebView. Website TCP streams
 * pass through the existing authenticated, encrypted phone/desktop channel. */
class PreviewProxy(private val rpc: suspend (String, JSONObject) -> JSONObject) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private var server: ServerSocket? = null
    private var previewId: String? = null
    suspend fun start(url: String): Int {
        val session = rpc("preview.open", JSONObject().put("url", url))
        previewId = session.getString("previewId")
        val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")); server = listener
        scope.launch {
            while (isActive && !listener.isClosed) {
                val socket = try { listener.accept() } catch (_: Exception) { break }
                if (clients.size >= 16) { socket.close(); continue }
                clients.add(socket)
                launch { handle(socket) }
            }
        }
        return listener.localPort
    }
    private fun args() = JSONObject().put("previewId", previewId)
    private suspend fun handle(client: Socket) = coroutineScope {
        var socketId: String? = null
        var established = false
        try {
            client.soTimeout = 120_000
            val input = client.getInputStream(); val output = client.getOutputStream()
            val bytes = ByteArrayOutputStream()
            while (bytes.size() < 65536) {
                val b = input.read(); check(b >= 0) { "浏览器连接已结束" }; bytes.write(b)
                val data = bytes.toByteArray(); val n = data.size
                if (n >= 4 && data[n-4] == 13.toByte() && data[n-3] == 10.toByte() && data[n-2] == 13.toByte() && data[n-1] == 10.toByte()) break
            }
            check(bytes.size() < 65536) { "网页请求头过大" }
            val header = bytes.toString(Charsets.ISO_8859_1.name()); val lines = header.split("\r\n")
            val first = lines.first().split(' '); check(first.size == 3)
            val connect = first[0] == "CONNECT"
            val uri = URI(if (connect) "https://${first[1]}" else first[1])
            check(uri.scheme in listOf("http", "https") && uri.host != null && uri.userInfo == null)
            val target = rpc("preview.connect", args().put("host", uri.host.removePrefix("[").removeSuffix("]")).put("port", if (uri.port > 0) uri.port else if (uri.scheme == "https") 443 else 80))
            socketId = target.getString("socketId")
            val id = socketId
            suspend fun write(data: ByteArray) { rpc("preview.write", args().put("socketId", id).put("data", Base64.encodeToString(data, Base64.NO_WRAP))) }
            if (connect) { output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray()); output.flush() }
            else {
                val path = uri.rawPath.orEmpty().ifBlank { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
                val upgrade = lines.any { it.startsWith("Upgrade:", true) }
                val headers = lines.drop(1).filter { it.isNotEmpty() && !it.startsWith("Proxy-", true) && (upgrade || !it.startsWith("Connection:", true)) }
                val rewritten = "${first[0]} $path ${first[2]}\r\n" + headers.joinToString("\r\n") + (if (!upgrade) "\r\nConnection: close" else "") + "\r\n\r\n"
                write(rewritten.toByteArray(Charsets.ISO_8859_1))
            }
            established = true
            val writer = launch(Dispatchers.IO) {
                try {
                    val buffer = ByteArray(96 * 1024)
                    while (isActive) { val n = input.read(buffer); if (n < 0) break; write(buffer.copyOf(n)) }
                } catch (_: Exception) { }
            }
            try {
                while (isActive) {
                    val result = rpc("preview.read", args().put("socketId", id))
                    val data = Base64.decode(result.getString("data"), Base64.DEFAULT)
                    if (data.isNotEmpty()) { output.write(data); output.flush() }
                    if (result.optBoolean("done")) break
                }
            } finally { client.close(); writer.cancel() }
        } catch (_: Exception) {
            if (!established) runCatching { client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()) }
        } finally {
            socketId?.let { runCatching { rpc("preview.disconnect", args().put("socketId", it)) } }
            clients.remove(client); runCatching { client.close() }
        }
    }
    fun close() {
        runCatching { server?.close() }; clients.forEach { runCatching { it.close() } }; clients.clear()
        previewId?.let { id -> scope.launch(NonCancellable) { runCatching { rpc("preview.close", JSONObject().put("previewId", id)) } } }
        scope.cancel()
    }
}
