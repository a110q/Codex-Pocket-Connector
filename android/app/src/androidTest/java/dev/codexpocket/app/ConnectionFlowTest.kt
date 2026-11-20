package dev.codexpocket.app

import android.Manifest
import android.content.Intent
import android.view.View
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBackUnconditionally
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.*

class ConnectionFlowTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val backups=mutableMapOf<String,JSONObject>()
    private lateinit var scenario:ActivityScenario<MainActivity>
    private var previousLan=false
    @Before fun setup(){
        listOf("pocket-service.enc","pocket-account.enc","pocket-state.enc","pocket-lan-state.enc").forEach{
            backups[it]=SecureStore(context,it).load();SecureStore(context,it).save(JSONObject())
        }
        val preferences=context.getSharedPreferences("connection-mode",0)
        previousLan=preferences.getBoolean("lan",false);preferences.edit().putBoolean("lan",false).commit()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.CAMERA)
        scenario=ActivityScenario.launch(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    @After fun cleanup(){
        scenario.close();backups.forEach{(file,value)->SecureStore(context,file).save(value)}
        context.getSharedPreferences("connection-mode",0).edit().putBoolean("lan",previousLan).commit()
    }
    private fun choices(){
        listOf("公网使用","局域网使用","自建中继").forEach{compose.onNodeWithText(it).assertIsDisplayed().assertHasClickAction()}
        compose.onNodeWithText("登录账号").assertDoesNotExist()
    }
    private fun waitForScanButton(label:String){
        compose.waitUntil(10000){runCatching{compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()}.getOrDefault(false)}
        compose.onNodeWithText(label).assertIsDisplayed()
    }
    private fun closeScanner(){
        onView(withId(R.id.close_scanner)).perform(object:ViewAction{
            override fun getConstraints()=isDisplayed()
            override fun getDescription()="关闭扫码"
            override fun perform(controller:UiController,view:View){Assert.assertTrue(view.performClick());controller.loopMainThreadUntilIdle()}
        })
    }
    @Test fun firstLaunchHasThreeChoicesAndEachOpensItsOwnScreen(){
        choices()
        compose.onNodeWithText("公网使用").performClick()
        compose.onNodeWithText("默认公网服务").assertIsDisplayed()
        compose.onNodeWithText("登录账号").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回选择连接方式").performClick()
        choices()
        compose.onNodeWithText("局域网使用").performClick()
        compose.onNodeWithText("无需登录或账号审批，扫码后由电脑确认。").assertIsDisplayed()
        compose.onNodeWithText("扫码连接电脑").assertIsDisplayed()
        compose.onNodeWithText("登录账号").assertDoesNotExist()
        pressBackUnconditionally()
        choices()
        compose.onNodeWithText("自建中继").performClick()
        compose.onNodeWithText("扫码配置服务").assertIsDisplayed()
        compose.onNodeWithText("登录账号").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回选择连接方式").performClick()
        choices()
    }
    @Test fun cancellingScannerReturnsToSelfHostingGuideAndRecreationKeepsThatPage(){
        compose.onNodeWithText("自建中继").performClick()
        compose.onNodeWithText("扫码配置服务").performClick()
        closeScanner()
        waitForScanButton("扫码配置服务")
        scenario.recreate()
        waitForScanButton("扫码配置服务")
        compose.onNodeWithContentDescription("返回选择连接方式").performClick()
        choices()
    }
    @Test fun cancellingLanScannerKeepsModeSelectionWithoutStartingAnAccountLogin(){
        compose.onNodeWithText("局域网使用").performClick()
        compose.onNodeWithText("扫码连接电脑").performClick()
        closeScanner()
        waitForScanButton("扫码连接电脑")
        compose.onNodeWithText("登录账号").assertDoesNotExist()
        Assert.assertFalse(context.getSharedPreferences("connection-mode",0).getBoolean("lan",false))
    }
}
