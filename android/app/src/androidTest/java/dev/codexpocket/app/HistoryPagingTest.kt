package dev.codexpocket.app

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.graphics.Bitmap
import java.io.File

class HistoryPagingTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val filename="qa-history-paging.enc"
    private lateinit var peer:ConnectionRecoveryTest.Peer
    private lateinit var model:PocketModel
    private var release=CountDownLatch(1)
    private var entered=CountDownLatch(1)
    private var clickedLink=""
    private var tableMode=androidx.compose.runtime.mutableStateOf(false)
    @Volatile private var total=120
    private fun thread(id:String="qa")=JSONObject().put("id",id).put("hostId","local").put("title","历史阅读测试").put("status","idle")
    private fun data(start:Int,end:Int,id:String="qa",revision:Int=1)=JSONObject().put("thread",thread(id)).put("source","desktop-live").put("streamId","qa-stream").put("revision",revision).put("historyComplete",true)
        .put("messages",JSONArray((start until end).map{index->JSONObject().put("id","m$index").put("key","t:m$index").put("turnId","t").put("order",index)
            .put("role",if(index in 60..89)"tool"else"assistant").put("text","历史消息 $index：记录需要完整保留，阅读时不要跳动。").put("title","工具历史 $index").put("type",if(index in 60..89)"commandExecution"else"agentMessage")}))
        .put("requests",JSONArray()).put("history",JSONObject().put("hasEarlier",start>0).put("before","t:m$start").put("total",total))
    @Before fun setup(){
        peer=ConnectionRecoveryTest.Peer("history-paging-qa-abcdefghijklmnop")
        peer.extra={action,args->when(action){
            "thread.read"->data(80,120,args.getString("threadId"))
            "thread.history"->{entered.countDown();release.await(60,TimeUnit.SECONDS);data(if(args.getString("before")=="t:m80")40 else 0,if(args.getString("before")=="t:m80")80 else 40,args.getString("threadId"))}
            else->null
        }}
        SecureStore(context,filename).save(JSONObject().put("profile",peer.profile))
        compose.runOnUiThread{model=PocketModel(context,filename);model.start()}
        until{model.connected}
        compose.runOnUiThread{model.openThread(thread())};until{!model.loadingThread&&model.snapshot!=null}
        compose.setContent{PocketTheme("light"){if(tableMode.value)MarkdownText("正文保持手机宽度\n\n| 功能 | 状态 | 说明 |\n|:--|:--:|--:|\n| 历史 | **完成** | [审核页](./review.html) |\n| 网页 | 可预览 | 样式和图片 |\n\n表格后的正常正文。",Modifier.padding(32.dp)){clickedLink=it}else ChatScreen(model)}}
        compose.waitForIdle()
    }
    @After fun cleanup(){release.countDown();compose.runOnUiThread{model.stop()};peer.close();SecureStore(context,filename).save(JSONObject())}
    private fun until(condition:()->Boolean){compose.waitUntil(10000){var done=false;compose.runOnUiThread{done=condition()};done}}
    private fun screenshot(name:String){val dir=compose.activity.getExternalFilesDir(null)!!;File(dir,"$name.png").outputStream().use{InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)}}
    private fun visibleTexts():List<android.widget.TextView>{
        compose.waitForIdle();var values=emptyList<android.widget.TextView>()
        compose.runOnUiThread{
            fun walk(view:android.view.View):List<android.widget.TextView> = if(view is android.widget.TextView&&view.getGlobalVisibleRect(android.graphics.Rect()))listOf(view)else if(view is android.view.ViewGroup)(0 until view.childCount).flatMap{walk(view.getChildAt(it))}else emptyList()
            values=walk(compose.activity.window.decorView)
        };return values
    }
    private fun screenY(view:android.view.View):Int{var y=0;compose.runOnUiThread{val point=IntArray(2);view.getLocationOnScreen(point);y=point[1]};return y}
    @Test fun scrollingToTopLoadsAutomaticallyRetainsFoldAnchorAndDoesNotFollowLiveReplies(){
        Assert.assertEquals(0,peer.actions.count{it=="thread.history"})
        compose.onNodeWithTag("chat-history").performScrollToIndex(1)
        compose.onNodeWithTag("chat-history").performTouchInput{swipe(center,center.copy(y=center.y+45f),400)}
        Assert.assertTrue(entered.await(3,TimeUnit.SECONDS));until{model.loadingHistory}
        val y=compose.onNodeWithContentDescription("展开执行过程").fetchSemanticsNode().boundsInRoot.top
        release.countDown();until{!model.loadingHistory&&model.snapshot!!.getJSONArray("messages").length()==80}
        compose.waitForIdle()
        val after=compose.onNodeWithContentDescription("展开执行过程").fetchSemanticsNode().boundsInRoot.top
        Assert.assertEquals("Folded group must remain at its reading position",y,after,3f)
        compose.onNodeWithContentDescription("展开执行过程").performClick()
        compose.onNodeWithText("工具历史 60").assertIsDisplayed()
        screenshot("history-auto-paging")
        // Scroll beyond the expanded native Compose tool rows to an assistant
        // TextView, so the anchor represents a message that survives the tail.
        compose.onNodeWithTag("chat-history").performScrollToIndex(55)
        val visible=visibleTexts().first{it.text.startsWith("历史消息")}
        val label=visible.text.toString();val previousY=screenY(visible)
        total=121;peer.snapshot(data(81,121,revision=2));until{model.snapshot!!.optLong("revision")==2L};compose.waitForIdle()
        Assert.assertEquals(previousY,screenY(visibleTexts().first{it.text.toString()==label}))
        compose.onNodeWithContentDescription("跳到最新").performClick()
        Assert.assertTrue(visibleTexts().any{it.text.startsWith("历史消息 120")})
        Assert.assertNull(model.error)
    }
    @Test fun aLatePageCannotReplaceAnotherChatOrClearItsRequestState(){
        compose.runOnUiThread{model.loadEarlier()};Assert.assertTrue(entered.await(3,TimeUnit.SECONDS))
        compose.runOnUiThread{model.openThread(thread("other"))};release.countDown()
        until{!model.loadingThread&&model.snapshot?.getJSONObject("thread")?.getString("id")=="other"}
        compose.waitForIdle();Assert.assertEquals(40,model.snapshot!!.getJSONArray("messages").length());Assert.assertFalse(model.loadingHistory);Assert.assertNull(model.historyError)
    }
    @Test fun failedAutoPageHasInlineRetryAndDoesNotRequestForever(){
        peer.requestError={action,_->if(action=="thread.history")"测试网络暂时中断"else null}
        compose.onNodeWithTag("chat-history").performScrollToIndex(1)
        compose.onNodeWithTag("chat-history").performTouchInput{swipe(center,center.copy(y=center.y+45f),400)}
        until{model.historyError!=null&&!model.loadingHistory}
        Assert.assertEquals(1,peer.actions.count{it=="thread.history"})
        compose.onNodeWithTag("chat-history").performScrollToIndex(0)
        compose.onNodeWithText("加载失败，点击重试").assertIsDisplayed()
        peer.requestError=null;release.countDown()
        compose.onNodeWithText("加载失败，点击重试").performClick()
        until{model.snapshot!!.getJSONArray("messages").length()==80}
        Assert.assertNull(model.historyError);Assert.assertNull(model.error)
    }
    @Test fun markdownTableKeepsReadableCellsLinksAndNormalBodyWidth(){
        compose.runOnUiThread{tableMode.value=true}
        compose.onNodeWithContentDescription("表格，可横向滚动").assertIsDisplayed()
        screenshot("markdown-table")
        Assert.assertTrue(visibleTexts().any{it.text.toString().trim()=="正文保持手机宽度"})
        Assert.assertTrue(visibleTexts().any{it.text.toString().trim()=="历史"})
        val cell=visibleTexts().first{it.text.contains("审核页")}
        compose.runOnUiThread{
            val span=cell.text as android.text.Spanned
            span.getSpans(0,span.length,android.text.style.ClickableSpan::class.java).single().onClick(cell)
        }
        Assert.assertEquals("./review.html",clickedLink)
        screenshot("markdown-table")
    }
}
