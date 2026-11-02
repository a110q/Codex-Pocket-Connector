package dev.codexpocket.app

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.*
import java.io.File

class LiveChatRenderingTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val revision=mutableIntStateOf(0)
    private val streamed=androidx.compose.runtime.mutableStateOf("我")
    private val prose="我先做真实 API 对照：固定模型、推理强度、提示词和上下文，测试现有通道与可用出口的首字速度、慢请求和失败率。只有确认稳定改善后才调整新会话路由；本轮不启用付费加速档。"
    private fun texts(v:View):List<TextView> = if(v is TextView && v.getGlobalVisibleRect(Rect()))listOf(v) else if(v is ViewGroup)(0 until v.childCount).flatMap{texts(v.getChildAt(it))}else emptyList()
    private fun checkWidths(){
        compose.waitForIdle()
        compose.runOnUiThread {
            val views=texts(compose.activity.window.decorView).filter{it.text.contains("我先做真实")}
            Assert.assertTrue("未显示测试正文",views.isNotEmpty())
            for(view in views){
                val layout=view.layout!!
                Assert.assertTrue("正文被排成单字竖排: view=${view.width}, layout=${layout.width}, firstEnd=${layout.getLineEnd(0)}",layout.getLineEnd(0)>8)
                Assert.assertTrue("正文没有使用可用宽度",layout.width>=view.width-view.totalPaddingLeft-view.totalPaddingRight-4)
                Assert.assertTrue("正文末尾被裁掉",layout.getLineBottom(layout.lineCount-1)<=view.height-view.totalPaddingTop-view.totalPaddingBottom+4)
            }
        }
    }
    @Test fun streamedTextGrowsFromOneChineseCharacterAndSurvivesLazyPrefetch(){
        compose.setContent{PocketTheme("light"){
            val text=streamed.value
            LazyColumn(Modifier.fillMaxSize().testTag("rendering-history"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
                items((0..40).toList(),key={it}){i->MessageCard(JSONObject().put("id","m$i").put("role","assistant").put("text",text+" · 第 $i 条"),onLink={})}
            }
        }}
        for(i in 1..prose.length step 4){
            compose.runOnUiThread{streamed.value=prose.take(i)}
            compose.waitForIdle()
        }
        compose.runOnUiThread{streamed.value=prose}
        checkWidths()
        repeat(12){
            compose.onNodeWithTag("rendering-history").performTouchInput{swipeUp(durationMillis=180)}
            checkWidths()
        }
        repeat(12){
            compose.onNodeWithTag("rendering-history").performTouchInput{swipeDown(durationMillis=180)}
            checkWidths()
        }
    }
    @Test fun textReflowsWhenNativePrefetchLayoutWidthDiffersFromTheComposeBounds(){
        compose.setContent{PocketTheme("light"){Column(Modifier.fillMaxSize().padding(16.dp)){MarkdownText(prose,Modifier.fillMaxWidth())}}}
        checkWidths()
        compose.runOnUiThread{
            val view=texts(compose.activity.window.decorView).first{it.text.contains("我先做真实")}
            val left=view.left;val top=view.top;val right=view.right;val bottom=view.bottom
            view.measure(View.MeasureSpec.makeMeasureSpec(1,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
            view.layout(left,top,right,bottom)
        }
        checkWidths()
    }
    @Test fun longChineseRepliesKeepTheirWidthAfterScrollingAndStreamUpdates(){
        compose.setContent{PocketTheme("light"){
            val frame=revision.intValue
            LazyColumn(Modifier.fillMaxSize().testTag("rendering-history"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
                items((0..100).toList(),key={it}){i->MessageCard(JSONObject().put("id","m$i").put("role","assistant").put("text",if(i%3==0)"| 项目 | 结果 |\n|---|---|\n| 状态 | 已完成 |\n\n$prose\n\n第 $i 条 · 更新 $frame" else "$prose\n\n第 $i 条 · 更新 $frame"),onLink={})}
            }
        }}
        for(i in listOf(0,45,90,5,66,0,99,30,0)){
            compose.onNodeWithTag("rendering-history").performScrollToIndex(i)
            checkWidths()
            compose.runOnUiThread{revision.intValue++}
            checkWidths()
        }
        val output=File(compose.activity.getExternalFilesDir(null),"chinese-reply-layout.png")
        output.outputStream().use{InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)}
    }
}
