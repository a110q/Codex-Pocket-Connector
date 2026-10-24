package dev.codexpocket.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

// Share the existing encrypted, per-thread file reader. No arbitrary URL or path
// is fetched by the phone. Decode sampled bitmaps and bound the disk cache.
class MessageImageLoader(context: Context,computer:String="") {
    private val cache = File(context.cacheDir, "message-images/"+computer.replace(Regex("[^A-Za-z0-9_-]"),"_").ifBlank{"legacy"})
    private val slots = Semaphore(2)
    suspend fun load(threadId: String, image: JSONObject, chunk: suspend (String, String, Long) -> JSONObject): Bitmap = slots.withPermit {
        withContext(Dispatchers.IO) {
            val id = image.optString("id")
            require(id.matches(Regex("[a-f0-9]{32}"))) { "图片不可用，电脑上的原文件可能已被清理" }
            require(image.optLong("size") in 1..50L * 1024 * 1024) { "图片超过 50 MB，请保存后查看" }
            cache.mkdirs()
            val target = File(cache, "$id.jpg")
            if (!target.exists()) {
                val temp = File.createTempFile("loading-", ".part", cache)
                try {
                    temp.outputStream().use { output ->
                        var offset = 0L
                        while (true) {
                            val part = chunk(threadId, id, offset)
                            val bytes = Base64.decode(part.getString("data"), Base64.DEFAULT)
                            val next = offset + bytes.size
                            require(part.getLong("total") == image.getLong("size") && part.getLong("nextOffset") == next && next <= image.getLong("size") && bytes.isNotEmpty()) { "图片传输不完整，请重试" }
                            output.write(bytes); offset = next
                            if (part.getBoolean("done")) { require(offset == image.getLong("size")); break }
                        }
                    }
                    val bitmap = decode(temp)
                    val saved = File.createTempFile("thumbnail-", ".part", cache)
                    try {
                        saved.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it)) }
                        check(saved.renameTo(target)) { "缩略图保存失败，请重试" }
                    } finally { saved.delete() }
                    trim(target)
                    bitmap
                } finally { temp.delete() }
            } else {
                target.setLastModified(System.currentTimeMillis())
                decode(target)
            }
        }
    }
    private fun decode(file: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("图片格式暂不支持，可保存原图查看")
    }
    private fun trim(keep: File) {
        val files = cache.listFiles()?.filter { it.extension == "jpg" }?.sortedBy { it.lastModified() }.orEmpty()
        var total = files.sumOf { it.length() }
        for (file in files) if (total > 32L * 1024 * 1024 && file != keep) { total -= file.length(); file.delete() }
    }
}

@Composable fun MessageImages(images: List<JSONObject>, connected: Boolean, load: suspend (JSONObject) -> Bitmap, preview: (JSONObject) -> Unit) {
    if (images.isEmpty()) return
    Column(Modifier.widthIn(max=330.dp).fillMaxWidth().padding(bottom=8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        images.chunked(2).forEach { row ->
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                row.forEach { image ->
                    key(image.optString("path")) {
                        var bitmap by remember(image.optString("id")) { mutableStateOf<Bitmap?>(null) }
                        var failure by remember(image.optString("id")) { mutableStateOf<String?>(null) }
                        var retry by remember { mutableIntStateOf(0) }
                        LaunchedEffect(image.optString("id"),connected,retry) {
                            failure=null
                            try { bitmap=load(image) }
                            catch(e: CancellationException) { throw e }
                            catch(e: Exception) { failure=if(!image.optBoolean("available",true)) "原图片已失效" else if(!connected) "连接电脑后加载" else e.message ?: "图片加载失败" }
                        }
                        Surface(onClick={if(image.optString("id").isNotBlank()) { if(bitmap!=null)preview(image) else retry++ }},modifier=Modifier.weight(1f).height(168.dp),shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surfaceVariant,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
                            Box(contentAlignment=Alignment.Center) {
                                val ready=bitmap
                                if(ready!=null)Image(ready.asImageBitmap(),"图片附件：${image.optString("name")}，点击预览",Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
                                else if(failure!=null)Column(Modifier.padding(12.dp),horizontalAlignment=Alignment.CenterHorizontally) { PocketIcon("图片");Spacer(Modifier.height(8.dp));Text(failure!!,fontSize=12.sp);Text(image.optString("name"),fontSize=10.sp,maxLines=2,overflow=TextOverflow.Ellipsis);if(connected&&image.has("id"))Text("点击重试",fontSize=11.sp,color=MaterialTheme.colorScheme.secondary) }
                                else CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}
