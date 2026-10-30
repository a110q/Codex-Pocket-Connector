package dev.codexpocket.app

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.*
import java.io.File

/** Real encrypted relay, FileAccess authorization, loopback origin and WebView proxy. */
class HtmlArtifactIntegrationTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val args get()=InstrumentationRegistry.getArguments()
    private lateinit var model:PocketModel
    private fun find(view:View):WebView?=if(view is WebView)view else if(view is ViewGroup)(0 until view.childCount).firstNotNullOfOrNull{find(view.getChildAt(it))}else null
    @Before fun setup(){
        Assume.assumeNotNull(args.getString("htmlFixture"))
        val fixture=JSONObject(args.getString("htmlFixture")!!)
        SecureStore(compose.activity,"qa-html-artifact.enc").save(JSONObject().put("profile",fixture.getJSONObject("profile")))
        compose.runOnUiThread{WebView(compose.activity).apply{clearCache(true);destroy()};model=PocketModel(compose.activity,"qa-html-artifact.enc");model.start()}
        until{model.connected}
        compose.runOnUiThread{model.openThread(JSONObject().put("id",fixture.getString("threadId")).put("hostId","local").put("title","审核页验收"))}
        until{!model.loadingThread&&model.snapshot!=null}
        compose.setContent{PocketTheme("light"){ChatScreen(model)}}
    }
    @After fun cleanup(){if(::model.isInitialized){compose.runOnUiThread{model.stop()};SecureStore(compose.activity,"qa-html-artifact.enc").save(JSONObject())}}
    private fun until(timeout:Long=15000,condition:()->Boolean){compose.waitUntil(timeout){var done=false;compose.runOnUiThread{done=condition()};done}}
    private fun js(script:String):String{var result="";var received=false;compose.runOnUiThread{find(compose.activity.window.decorView)?.evaluateJavascript(script){result=it;received=true}};until{received};return result}
    private fun ready(){until(30000){model.previewKind=="browser"};var state="";compose.waitUntil(40000){compose.runOnUiThread{find(compose.activity.window.decorView)?.evaluateJavascript("JSON.stringify({ready:document.body.dataset.ready,css:getComputedStyle(document.body).getPropertyValue('--fixture-css'),image:document.querySelector('#cover')?.naturalWidth,text:document.body.innerText})"){state=it}};state.contains("yes")&&state.contains("ready")&&state.contains("600")};Assert.assertNull(model.previewError)}
    private fun screenshot(name:String){compose.waitForIdle();Thread.sleep(250);File(compose.activity.getExternalFilesDir(null),"$name.png").outputStream().use{InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)}}
    @Test fun chatHtmlLinkLoadsInteractivePageWithRelativeAssetsOverRelay(){
        // Click the actual Markdown span rather than calling preview directly.
        compose.waitForIdle()
        compose.runOnUiThread{
            fun locate(view:View):android.widget.TextView?=if(view is android.widget.TextView&&view.text.contains("审核完成"))view else if(view is ViewGroup)(0 until view.childCount).firstNotNullOfOrNull{locate(view.getChildAt(it))}else null
            val view=locate(compose.activity.window.decorView)!!
            val start=view.text.indexOf("审核页面")+1;val line=view.layout.getLineForOffset(start)
            val x=view.totalPaddingLeft+view.layout.getPrimaryHorizontal(start)
            val y=view.totalPaddingTop+(view.layout.getLineTop(line)+view.layout.getLineBottom(line))/2f
            val now=android.os.SystemClock.uptimeMillis()
            // Exercise TextView's actual touch/link handler without relying on
            // the previous instrumentation activity relinquishing OS focus.
            listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP).forEachIndexed{index,action->
                val event=android.view.MotionEvent.obtain(now,now+index*80L,action,x,y,0)
                try{view.dispatchTouchEvent(event)}finally{event.recycle()}
            }
        }
        // Fallback assertion identifies incorrect span coordinates rather than hiding a broken link.
        until{model.previewKind!=null}
        ready();screenshot("html-page-loaded")
        Assert.assertTrue(js("document.querySelector('#approve').click();document.body.dataset.approved").contains("yes"))
        screenshot("html-page-approved")
        compose.onNodeWithContentDescription("刷新网页").performClick();ready()
        Assert.assertTrue(js("document.body.dataset.approved||'fresh'").contains("fresh"))
        compose.onNodeWithContentDescription("关闭预览").performClick();until{model.previewKind==null}
        compose.onNodeWithTag("chat-history").assertIsDisplayed()
    }
    @Test fun projectFileHtmlUsesTheSameInteractivePreview(){
        compose.runOnUiThread{model.previewProjectFile(JSONObject().put("projectId","qa-project"),"pages/review page.html")}
        ready();screenshot("html-project-file")
        Assert.assertTrue(js("document.querySelector('#approve').click();document.querySelector('#result').innerText").contains("审核操作成功"))
    }
}
