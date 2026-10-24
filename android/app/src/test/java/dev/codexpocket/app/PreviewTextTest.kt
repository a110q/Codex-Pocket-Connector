package dev.codexpocket.app

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class PreviewTextTest {
    @Test fun chineseAndEmptyFilesRemainReadable() {
        val text = "# 预览\n安卓 10 也能读取 🐈"
        val data = text.toByteArray()
        assertEquals(text, readPreviewText(ByteArrayInputStream(data), data.size.toLong()))
        assertEquals("", readPreviewText(ByteArrayInputStream(byteArrayOf()), 0))
    }
    @Test fun largeFileStopsReadingAtPreviewLimit() {
        val input = ByteArrayInputStream(ByteArray(TEXT_PREVIEW_LIMIT + 200) { 'a'.code.toByte() })
        val result = readPreviewText(input, (TEXT_PREVIEW_LIMIT + 200).toLong())
        assertEquals(200, input.available())
        assertTrue(result.endsWith("预览到此为止，保存后可查看完整文件。"))
    }
    @Test fun binaryFilesOfferDownload() {
        assertTrue(readPreviewText(ByteArrayInputStream(byteArrayOf(1, 0, 2)), 3).contains("保存到手机"))
    }
    @Test fun ioFailureIsNotMistakenForAnEmptyFile() {
        val stream = object : InputStream() { override fun read(): Int = throw IOException("fixture failure") }
        assertThrows(IOException::class.java) { readPreviewText(stream, 20) }
    }
}
