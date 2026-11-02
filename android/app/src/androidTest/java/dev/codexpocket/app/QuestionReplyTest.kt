package dev.codexpocket.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import java.io.File
import android.graphics.Bitmap

class QuestionReplyTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun machineRepliesAndCachedWrappersShowReadableQuestionsAndAnswers(){
        val values=JSONArray().put(JSONObject().put("questionItemId","internal-call-should-not-display").put("question","请完成服务器登录").put("answer","已登录"))
            .put(JSONObject().put("question","发现新版本，是否现在更新？").put("answer","稍后更新"))
        val raw="<send_user_message_question_reply>\n$values\n</send_user_message_question_reply>"
        val cached=JSONObject().put("role","user").put("text",raw)
        compose.setContent{PocketTheme("light"){Column(Modifier.safeDrawingPadding().padding(16.dp)){MessageCard(cached,{})}}}
        compose.onNodeWithText("请完成服务器登录").assertIsDisplayed()
        compose.onNodeWithText("已登录").assertIsDisplayed()
        compose.onNodeWithText("发现新版本，是否现在更新？").assertIsDisplayed()
        compose.onNodeWithText("稍后更新").assertIsDisplayed()
        compose.onAllNodesWithText("send_user_message_question_reply",substring=true).assertCountEquals(0)
        compose.onAllNodesWithText("internal-call-should-not-display",substring=true).assertCountEquals(0)
        Assert.assertEquals(2,questionReplies(cached).size)
        Assert.assertEquals(2,questionReplies(JSONObject().put("questionReplies",values)).size)
        Assert.assertTrue(questionReplies(JSONObject().put("text","示例：$raw")).isEmpty())
        Assert.assertTrue(questionReplies(JSONObject().put("text","<send_user_message_question_reply>invalid</send_user_message_question_reply>")).isEmpty())
        compose.waitForIdle()
        val path=File(compose.activity.getExternalFilesDir(null),"question-replies-0.9.15.2.png")
        path.outputStream().use{InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)}
    }
}
