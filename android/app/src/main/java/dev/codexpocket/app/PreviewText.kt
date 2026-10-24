package dev.codexpocket.app

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal const val TEXT_PREVIEW_LIMIT = 512 * 1024

// InputStream.readNBytes is only available starting with Android 13.
internal fun readPreviewText(input: InputStream, totalBytes: Long): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (output.size() < TEXT_PREVIEW_LIMIT) {
        val count = input.read(buffer, 0, minOf(buffer.size, TEXT_PREVIEW_LIMIT - output.size()))
        if (count < 0) break
        if (count == 0) continue
        output.write(buffer, 0, count)
    }
    val data = output.toByteArray()
    if (data.any { it == 0.toByte() }) return "此文件格式暂不支持内嵌预览，请保存到手机后打开。"
    return String(data, Charsets.UTF_8) + if (totalBytes > data.size) "\n\n预览到此为止，保存后可查看完整文件。" else ""
}
