package dev.codexpocket.app

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PreviewCodecTest {
    private fun chunk(bytes:ByteArray,zipped:Boolean):JSONObject {
        val wire=if(zipped)ByteArrayOutputStream().also{out->GZIPOutputStream(out).use{it.write(bytes)}}.toByteArray()else bytes
        return JSONObject().put("data",Base64.encodeToString(wire,Base64.NO_WRAP)).apply{if(zipped)put("encoding","gzip")}
    }
    @Test fun compressedAndLegacyChunksPreserveAllBytes(){
        val bytes=ByteArray(192*1024){(it%256).toByte()}
        for(zipped in listOf(true,false))assertArrayEquals(bytes,decodePreviewChunk(chunk(bytes,zipped)))
    }
    @Test fun decompressionRejectsOversizedAndUnsupportedChunks(){
        for(json in listOf(chunk(ByteArray(192*1024+1),true),chunk(byteArrayOf(1),false).put("encoding","unsupported"))){
            try{decodePreviewChunk(json);fail("Invalid preview chunk accepted")}catch(_:IllegalStateException){}
        }
    }
}
