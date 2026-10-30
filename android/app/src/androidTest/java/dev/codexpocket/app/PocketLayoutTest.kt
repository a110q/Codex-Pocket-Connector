package dev.codexpocket.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.ContentValues
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import java.io.File

class PocketLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var backup: JSONObject
    private lateinit var model: PocketModel
    @Before fun setup() {
        backup=SecureStore(context).load()
        val thread=JSONObject().put("id","layout-only").put("hostId","local").put("title","界面验证")
        val snapshot=JSONObject().put("thread",thread).put("messages",JSONArray().put(JSONObject().put("id","answer").put("turnId","t").put("key","t:answer").put("role","assistant").put("text","用于检查键盘布局的本地测试内容。"))).put("historyComplete",true)
        SecureStore(context).save(JSONObject().put("timelineFormat",2).put("snapshot",snapshot).put("threads",JSONArray().put(thread)))
        compose.runOnUiThread { compose.activity.enableEdgeToEdge(); model=PocketModel(context);model.openThread(thread) }
    }
    @After fun teardown() { compose.runOnUiThread { model.stop() };SecureStore(context).save(backup) }
    private fun showChat() { compose.setContent { PocketTheme("light") { PocketApp(model,{ },null,{ }) } };compose.waitForIdle() }
    private fun screenshot(name: String) {
        compose.waitForIdle();Thread.sleep(400)
        val values=ContentValues().apply{put(MediaStore.Images.Media.DISPLAY_NAME,"$name.png");put(MediaStore.Images.Media.MIME_TYPE,"image/png");put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/CodexPocket-QA");put(MediaStore.Images.Media.IS_PENDING,1)}
        val uri=context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)!!
        instrumentation.uiAutomation.takeScreenshot().let{bitmap->context.contentResolver.openOutputStream(uri)!!.use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}
        context.contentResolver.update(uri,ContentValues().apply{put(MediaStore.Images.Media.IS_PENDING,0)},null,null)
    }
    @Test fun addressKeepsHeightWithKeyboardAndRestoresChat() {
        showChat()
        // This fixture is intentionally offline. Preview navigation is tested
        // with a connected peer elsewhere; open the pane to isolate IME layout.
        compose.runOnUiThread { model.showPreviewTab("browser") }
        compose.onNodeWithContentDescription("网页地址").assertIsDisplayed()
        screenshot("address-before")
        compose.onNodeWithContentDescription("网页地址").performClick().performTextReplacement("http://127.0.0.1:5174/a/very/long/path?example=value")
        compose.waitUntil(5000){compose.onAllNodesWithContentDescription("聊天输入框").fetchSemanticsNodes().isEmpty()}
        val bounds=compose.onNodeWithContentDescription("网页地址").fetchSemanticsNode().boundsInRoot
        Assert.assertTrue("Address must remain at least 56dp",bounds.height>=56*context.resources.displayMetrics.density-1)
        compose.onNodeWithContentDescription("网页地址").assertIsDisplayed()
        compose.onNodeWithContentDescription("打开网页").assertIsDisplayed()
        screenshot("address-keyboard")
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
        compose.waitUntil(5000){compose.onAllNodesWithContentDescription("聊天输入框").fetchSemanticsNodes().isNotEmpty()}
        screenshot("address-restored")
        compose.onNodeWithContentDescription("网页地址").performClick().performTextReplacement("invalid-url")
        compose.onNodeWithContentDescription("网页地址").performImeAction()
        compose.onNodeWithText("请输入完整的 HTTP 或 HTTPS 地址").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭预览").performClick()
        compose.onNodeWithContentDescription("网页地址").assertDoesNotExist()
    }
    @Test fun realDesktopPhotoLoadsFromChunksCachesAndOpensPreview() {
        val bytes=instrumentation.context.assets.open("desktop-attachment.jpg").use{it.readBytes()}
        val secondBytes=instrumentation.context.assets.open("desktop-attachment-keyboard.jpg").use{it.readBytes()}
        val id=java.util.UUID.randomUUID().toString().replace("-","")
        val attachment=JSONObject().put("id",id).put("path","desktop-attachment.jpg").put("name","电脑发送的截图.jpg").put("size",bytes.size).put("mime","image/jpeg").put("available",true)
        val second=JSONObject(attachment.toString()).put("id",java.util.UUID.randomUUID().toString().replace("-","")).put("path","desktop-attachment-keyboard.jpg").put("name","键盘截图.jpg").put("size",secondBytes.size)
        val loader=MessageImageLoader(context)
        var reads=0
        val load:suspend(JSONObject)->Bitmap={image->loader.load("layout-only",image){_,_,offset->
            val payload=if(image.optString("id")==id)bytes else secondBytes
            reads++
            val end=(offset+192*1024).coerceAtMost(payload.size.toLong()).toInt()
            JSONObject().put("data",android.util.Base64.encodeToString(payload.copyOfRange(offset.toInt(),end),android.util.Base64.NO_WRAP)).put("total",payload.size).put("nextOffset",end).put("done",end==payload.size)
        }}
        val bitmap=runBlocking{load(attachment)}
        Assert.assertTrue("Image must actually pass through the chunk reader",reads>0)
        Assert.assertTrue(bitmap.width>0&&bitmap.height in 1..1024)
        runBlocking{loader.load("layout-only",attachment){_,_,_->error("Cache must prevent a new download")}}
        val original=File(context.cacheDir,"qa-original.jpg").apply{writeBytes(bytes)}
        compose.setContent { PocketTheme("light") {
            var preview by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Text("电脑图片 · 原生界面验证",Modifier.padding(20.dp))
                if(preview)RasterPreview(original,Modifier.weight(1f))
                else Box(Modifier.padding(horizontal=20.dp)) { MessageCard(JSONObject().put("id","photo").put("role","user").put("text","这块点击输入的时候，搜索框很小，"),{},images={MessageImages(listOf(attachment,second),true,load){preview=true}}) }
            }
        } }
        compose.waitUntil(5000){compose.onAllNodesWithContentDescription("图片附件：电脑发送的截图.jpg，点击预览").fetchSemanticsNodes().isNotEmpty()}
        compose.waitUntil(5000){compose.onAllNodesWithContentDescription("图片附件：键盘截图.jpg，点击预览").fetchSemanticsNodes().isNotEmpty()}
        screenshot("desktop-image-inline")
        compose.onNodeWithContentDescription("图片附件：电脑发送的截图.jpg，点击预览").performClick()
        compose.waitUntil(5000){compose.onAllNodesWithContentDescription("图片预览 100%").fetchSemanticsNodes().isNotEmpty()}
        screenshot("desktop-image-preview")
        compose.onNodeWithText("＋").performClick()
        compose.onNodeWithContentDescription("图片预览 150%").assertIsDisplayed()
        compose.onNodeWithText("重置").performClick()
        compose.onNodeWithContentDescription("图片预览 100%").assertIsDisplayed()
    }
    @Test fun missingImageShowsAnExplicitState() {
        compose.setContent { PocketTheme("light") { MessageImages(listOf(JSONObject().put("name","已清理的截图.jpg").put("path","missing.jpg").put("available",false)),true,{error("missing")},{}) } }
        compose.onNodeWithText("原图片已失效").assertIsDisplayed()
        screenshot("image-unavailable")
    }
}
