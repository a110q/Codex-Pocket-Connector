package dev.codexpocket.app

import android.graphics.Bitmap
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.style.ImageSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class MarkdownImagesTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val loads=ConcurrentHashMap<String,Int>()
    private var clicked=""
    private fun bitmap()=Bitmap.createBitmap(120,180,Bitmap.Config.ARGB_8888).apply{eraseColor(android.graphics.Color.rgb(143,182,159))}
    private fun views(view:View):List<TextView> = if(view is TextView)listOf(view)else if(view is ViewGroup)(0 until view.childCount).flatMap{views(view.getChildAt(it))}else emptyList()
    private fun spans()=views(compose.activity.window.decorView).flatMap{view->
        val text=view.text as? Spanned
        text?.getSpans(0,text.length,ImageSpan::class.java)?.mapNotNull{it.drawable as? InlinePicture}.orEmpty()
    }
    @Test fun imagesPreserveNestedListsReferenceLinksTableCellsAndTextOrder(){
        val support=MarkdownImageSupport("fixture",{source->loads.merge(source,1,Int::plus);bitmap()},{clicked=it})
        compose.setContent{PocketTheme("light"){Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())){
            MarkdownText("第一套：\n\n1. **开衫**\n\n   ![参考图][outfit]\n\n2. 衬衫\n\n   ![第二张](https://example.invalid/outfit.png)\n\n| 推荐 | 图片 |\n| --- | --- |\n| 法式 | ![表格图](./table.jpg) |\n\n最后的结论。\n\n`![代码不加载](./code.jpg)`\n\n[outfit]: <./outfit one.jpg>",imageSupport=support)
        }}}
        compose.waitUntil(15000){var ready=false;compose.runOnUiThread{ready=spans().count{it.bitmap!=null}==3};ready}
        Assert.assertEquals(setOf("./outfit one.jpg","https://example.invalid/outfit.png","./table.jpg"),loads.keys)
        compose.runOnUiThread{
            val prose=views(compose.activity.window.decorView).first{it.text.contains("第一套")}.text as Spanned
            val images=prose.getSpans(0,prose.length,MarkdownImageSource::class.java).sortedBy{prose.getSpanStart(it)}
            Assert.assertEquals(2,images.size)
            Assert.assertTrue(prose.getSpanStart(images[0])>prose.indexOf("开衫"))
            Assert.assertTrue("图片后的衬衫文字缺失或顺序改变：$prose",prose.getSpanEnd(images[0])<prose.indexOf("衬衫"))
            Assert.assertTrue(spans().all{it.bounds.width()>1&&it.bounds.height()>1})
        }
        val target=File(compose.activity.getExternalFilesDir(null),"markdown-images.png")
        target.outputStream().use{InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun failedImageCanRetryAndThenOpenPreview(){
        val support=MarkdownImageSupport("fixture",{source->if(loads.merge(source,1,Int::plus)==1)error("模拟断网");bitmap()},{clicked=it})
        compose.setContent{PocketTheme("dark"){MarkdownText("图片前\n\n![重试图片](./retry.jpg)\n\n图片后",imageSupport=support)}}
        compose.waitUntil(10000){loads["./retry.jpg"]==1}
        fun click(){compose.runOnUiThread{
            val view=views(compose.activity.window.decorView).first{it.text.contains("重试图片")}
            val text=view.text as Spanned
            text.getSpans(0,text.length,ClickableSpan::class.java).single().onClick(view)
        }}
        compose.waitForIdle();click()
        compose.waitUntil(10000){var ready=false;compose.runOnUiThread{ready=spans().singleOrNull()?.bitmap!=null};ready}
        click();Assert.assertEquals("./retry.jpg",clicked)
    }
    @Test fun remoteImageCacheWorksOfflineAndDoesNotSendAccountCredentials(){
        val server=MockWebServer();server.start()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val cacheName="qa-remote-${System.nanoTime()}";val loader=MessageImageLoader(context,cacheName)
        try{
            val bytes=ByteArrayOutputStream().apply{bitmap().compress(Bitmap.CompressFormat.PNG,100,this)}.toByteArray()
            server.enqueue(MockResponse().setHeader("Content-Type","image/png").setBody(Buffer().write(bytes)))
            val url=server.url("/outfit.png").toString()
            val image=runBlocking{loader.remote(url)};Assert.assertEquals(120,image.width)
            val request=server.takeRequest();Assert.assertNull(request.getHeader("Authorization"));Assert.assertNull(request.getHeader("Cookie"))
            server.shutdown()
            Assert.assertEquals(120,runBlocking{MessageImageLoader(context,cacheName).remote(url)}.width)
        }finally{server.shutdown();File(context.cacheDir,"message-images/$cacheName").deleteRecursively()}
    }
}
