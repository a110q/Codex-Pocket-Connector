package dev.codexpocket.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*

class ComputerControlsTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val filename="qa-computer-controls.enc"
    private lateinit var backup:JSONObject
    private lateinit var a:ConnectionRecoveryTest.Peer
    private lateinit var b:ConnectionRecoveryTest.Peer
    private lateinit var model:PocketModel
    private var oldNotifications=false
    @Before fun setup(){
        backup=SecureStore(context,filename).load();oldNotifications=TaskNotifications.enabled(context)
        instrumentation.runOnMainSync{TaskNotifications.setEnabled(context,false)}
        a=ConnectionRecoveryTest.Peer("rename-computer-a-abcdefghijklmnop",true)
        b=ConnectionRecoveryTest.Peer("rename-computer-b-abcdefghijklmnop",true)
        a.profile.put("name","DESKTOP-QA");b.profile.put("name","家里的 Mac")
        a.capabilities.put("command.status",true);a.ignoreMessages=true
        a.extra={action,_->if(action=="command.status")JSONObject().put("state","running")else null}
        SecureStore(context,filename).save(JSONObject().put("profile",a.profile).put("computers",JSONArray().put(a.profile).put(b.profile)))
        compose.runOnUiThread{model=PocketModel(context,filename,true);model.start()}
        compose.setContent{PocketTheme("light"){PocketApp(model,{},null,{})}}
        until{model.connected}
    }
    @After fun cleanup(){
        compose.runOnUiThread{model.stop()};a.close();b.close();SecureStore(context,filename).save(backup)
        context.getSharedPreferences("task-alerts",0).edit().putBoolean("enabled",oldNotifications).commit()
    }
    private fun until(condition:()->Boolean){compose.waitUntil(12000){var result=false;instrumentation.runOnMainSync{result=condition()};result}}
    private fun thread()=JSONObject().put("id","local-network-test").put("hostId","local").put("title","设备切换验证")
    private fun choose(name:String){compose.onNodeWithContentDescription("快速切换电脑").performClick();compose.onNodeWithText(name).performClick()}
    private fun screenshot(name:String){
        compose.waitForIdle()
        instrumentation.uiAutomation.waitForIdle(400,3000)
        val values=ContentValues().apply{put(MediaStore.Images.Media.DISPLAY_NAME,"$name.png");put(MediaStore.Images.Media.MIME_TYPE,"image/png");put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/CodexPocket-092")}
        val uri=context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)!!
        context.contentResolver.openOutputStream(uri)!!.use{instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun renamePersistsAndTheHeaderDropdownSwitchesWithoutOpeningSettings(){
        compose.onNodeWithContentDescription("快速切换电脑").performClick()
        compose.onNodeWithText("管理电脑与名称").performClick()
        compose.onNodeWithContentDescription("重命名当前电脑").performClick()
        compose.onNodeWithContentDescription("电脑名称").performTextReplacement("")
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithContentDescription("电脑名称").performTextReplacement("办公室 Windows")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        screenshot("computer-rename-dialog")
        compose.onNodeWithText("保存").performClick()
        until{model.profile?.computerName()=="办公室 Windows"}
        screenshot("computers-renamed")
        compose.onNodeWithContentDescription("返回工作空间").performClick()
        compose.onNodeWithContentDescription("快速切换电脑").performClick()
        compose.onAllNodesWithText("办公室 Windows").onLast().assertIsDisplayed()
        compose.onNodeWithText("家里的 Mac").assertIsDisplayed()
        screenshot("computer-dropdown")
        compose.onNodeWithText("家里的 Mac").performClick()
        until{model.profile?.optString("hostId")==b.profile.optString("hostId")&&model.connected}
        Assert.assertEquals(1,b.opens.get())
        compose.runOnUiThread{
            Assert.assertFalse(model.renameComputer(a.profile.getString("hostId"),"x".repeat(81)))
            model.stop()
            val restored=PocketModel(context,filename,true)
            val renamed=restored.computers.first{it.optString("hostId")==a.profile.optString("hostId")}
            Assert.assertEquals("办公室 Windows",renamed.computerName());Assert.assertEquals("DESKTOP-QA",renamed.optString("name"))
            Assert.assertEquals(a.profile.getString("key"),renamed.getString("key"));restored.stop()
        }
    }
    @Test fun switchingDuringPendingDeliveryKeepsEachComputersDraftAndReceiptSeparate(){
        compose.runOnUiThread{model.openThread(thread())};until{!model.loadingThread}
        compose.runOnUiThread{model.send("QA send to A once")};until{a.messages.size==1&&model.pendingOperation!=null}
        compose.runOnUiThread{model.draft="A 下一条草稿";model.back()}
        choose("家里的 Mac");until{model.connected&&model.profile?.optString("hostId")==b.profile.optString("hostId")}
        Assert.assertNull(model.pendingOperation)
        compose.runOnUiThread{model.openThread(thread())};until{!model.loadingThread}
        compose.runOnUiThread{model.send("QA send to B once")};until{b.messages.size==1&&model.pendingOperation==null}
        compose.runOnUiThread{model.draft="B 下一条草稿";model.back()}
        a.extra={action,_->if(action=="command.status")JSONObject().put("state","done").put("result",JSONObject().put("submitted",true))else null}
        choose("DESKTOP-QA");until{model.connected&&model.pendingOperation==null&&a.actions.contains("command.status")}
        compose.runOnUiThread{model.openThread(thread())};until{!model.loadingThread}
        Assert.assertEquals("A 下一条草稿",model.draft);Assert.assertEquals(1,a.messages.size);Assert.assertEquals(1,b.messages.size)
        compose.runOnUiThread{model.back()};choose("家里的 Mac");until{model.connected&&model.profile?.optString("hostId")==b.profile.optString("hostId")}
        compose.runOnUiThread{model.openThread(thread())};until{!model.loadingThread}
        Assert.assertEquals("B 下一条草稿",model.draft)
    }
    @Test fun rescanningRenewedPairingPreservesTheChosenComputerName(){
        compose.runOnUiThread{Assert.assertTrue(model.renameComputer(a.profile.getString("hostId"),"实验室主机"))}
        val renewed=JSONObject(a.profile.toString()).put("v",1).put("pairCode",Encoding.secret()).put("expiresAt",System.currentTimeMillis()+600000)
        compose.runOnUiThread{model.pair(renewed.toString())}
        until{model.connected&&a.hellos.get()==2}
        Assert.assertEquals("实验室主机",model.profile?.computerName())
        Assert.assertEquals(2,model.computers.size)
    }
}
