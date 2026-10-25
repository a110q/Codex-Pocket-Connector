package dev.codexpocket.app

import android.app.NotificationManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*

class MultiComputerNotificationsTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val manager get()=context.getSystemService(NotificationManager::class.java)
    private lateinit var a:ConnectionRecoveryTest.Peer
    private lateinit var b:ConnectionRecoveryTest.Peer
    private val backups=mutableMapOf<String,JSONObject>()
    private var scenario:ActivityScenario<MainActivity>?=null
    private var oldLan=false
    private var oldEnabled=false
    private fun save(name:String,value:JSONObject){backups.putIfAbsent(name,SecureStore(context,name).load());SecureStore(context,name).save(value)}
    private fun until(timeout:Long=30000,condition:()->Boolean){val end=System.currentTimeMillis()+timeout;while(!condition()){Assert.assertTrue("Timed out waiting for multiple computers: A opens=${if(::a.isInitialized)a.opens.get()else -1} hellos=${if(::a.isInitialized)a.hellos.get()else -1} actions=${if(::a.isInitialized)a.actions.toList()else emptyList<String>()}; B opens=${if(::b.isInitialized)b.opens.get()else -1} hellos=${if(::b.isInitialized)b.hellos.get()else -1}",System.currentTimeMillis()<end);Thread.sleep(50)}}
    private fun snapshot(title:String,approval:Boolean=false)=JSONObject().put("thread",JSONObject().put("id","local-network-test").put("hostId","local").put("title",title).put("lastTurnId","multi-turn").put("lastTurnStatus","inProgress").put("activeTurnId","multi-turn")).put("messages",JSONArray()).put("requests",JSONArray().also{if(approval)it.put(JSONObject().put("id","same-approval-id").put("method","item/commandExecution/requestApproval").put("params",JSONObject().put("threadId","local-network-test").put("command","QA_ONLY_COMMAND").put("availableDecisions",JSONArray().put("accept").put("decline"))))}).put("historyComplete",true)
    private fun configure(peer:ConnectionRecoveryTest.Peer,title:String){peer.extra={action,_->when(action){"thread.read"->snapshot(title);"threads.list"->JSONObject().put("threads",JSONArray().put(JSONObject().put("id","local-network-test").put("hostId","local").put("title",title).put("status","active").put("updatedAt","1")));else->null}}}
    @Before fun setup(){
        oldLan=context.getSharedPreferences("connection-mode",0).getBoolean("lan",false);oldEnabled=TaskNotifications.enabled(context)
        instrumentation.runOnMainSync{TaskNotifications.setEnabled(context,false)}
        a=ConnectionRecoveryTest.Peer("multi-computer-a-abcdefghijklmnop",true);b=ConnectionRecoveryTest.Peer("multi-computer-b-abcdefghijklmnop",true)
        a.profile.put("name","开发 Mac");b.profile.put("name","办公室 Windows");configure(a,"构建任务 A");configure(b,"修复任务 B")
        save("pocket-service.enc",JSONObject().put("selected",JSONObject().put("relay",b.profile.getString("relay")).put("pin",b.profile.getString("pin"))))
        save("pocket-lan-state.enc",JSONObject().put("profile",b.profile).put("computers",JSONArray().put(a.profile).put(b.profile)))
        context.getSharedPreferences("connection-mode",0).edit().putBoolean("lan",true).commit()
        instrumentation.uiAutomation.executeShellCommand("pm grant dev.codexpocket.app android.permission.POST_NOTIFICATIONS").close()
        scenario=ActivityScenario.launch(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));until{b.hellos.get()==1}
        instrumentation.runOnMainSync{TaskNotifications.setEnabled(context,true)}
        until{a.hellos.get()==1};until{a.actions.contains("thread.read")}
        Assert.assertEquals(1,b.opens.get())
    }
    @After fun cleanup(){
        instrumentation.runOnMainSync{TaskNotifications.setEnabled(context,false)};scenario?.close()
        if(::a.isInitialized)a.close();if(::b.isInitialized)b.close()
        backups.forEach{(file,value)->SecureStore(context,file).save(value)}
        context.getSharedPreferences("connection-mode",0).edit().putBoolean("lan",oldLan).commit()
        context.getSharedPreferences("task-alerts",0).edit().putBoolean("enabled",oldEnabled).commit();manager.cancelAll()
    }
    private fun alerts()=manager.activeNotifications.filter{it.notification.channelId==TaskNotifications.CHANNEL}
    @Test fun foregroundOtherComputerAlertsAndTapSwitchesToTheCorrectApprovalWithoutSocketFights(){
        a.snapshot(snapshot("构建任务 A",true));until{alerts().any{it.notification.extras.getString("android.text")?.contains("开发 Mac")==true}}
        val alert=alerts().first{it.notification.extras.getString("android.text")?.contains("开发 Mac")==true}
        Assert.assertEquals("Codex 等待你的操作",alert.notification.extras.getString("android.title"))
        a.snapshot(snapshot("构建任务 A",true));Thread.sleep(250);Assert.assertEquals(1,alerts().size);Assert.assertEquals(1,b.opens.get());Assert.assertEquals(1,a.opens.get())
        // Capture the real Android notification shade with fictional peer names.
        instrumentation.uiAutomation.executeShellCommand("cmd statusbar expand-notifications").close();Thread.sleep(500)
        val values=ContentValues().apply{put(MediaStore.Images.Media.DISPLAY_NAME,"multi-computer-alert.png");put(MediaStore.Images.Media.MIME_TYPE,"image/png");put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/CodexPocket-review")}
        val uri=context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)!!
        context.contentResolver.openOutputStream(uri)!!.use{instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)}
        instrumentation.uiAutomation.executeShellCommand("cmd statusbar collapse").close()
        // The read response is now the pending approval on A.
        a.extra={action,_->when(action){"thread.read"->snapshot("构建任务 A",true);"threads.list"->JSONObject().put("threads",JSONArray());else->null}}
        alert.notification.contentIntent.send()
        compose.waitUntil(20000){compose.onAllNodesWithText("允许本次").fetchSemanticsNodes().isNotEmpty()}
        until{a.hellos.get()==2&&b.hellos.get()==2}
        compose.onNodeWithText("允许本次").performClick();until{a.commands.any{it.optString("action")=="approval.respond"}}
        Assert.assertFalse(b.commands.any{it.optString("action")=="approval.respond"})
        Assert.assertFalse(a.commands.any{it.optString("action")=="message.send"})
        b.snapshot(snapshot("修复任务 B",true));until{alerts().any{it.notification.extras.getString("android.text")?.contains("办公室 Windows")==true}}
        Thread.sleep(500);Assert.assertEquals(2,a.opens.get());Assert.assertEquals(2,b.opens.get())
    }
    @Test fun backgroundKeepsBothComputersAndIdenticalApprovalIdsRemainSeparate(){
        val beforeA=a.opens.get();scenario!!.moveToState(Lifecycle.State.CREATED)
        until{b.hellos.get()==2};Assert.assertEquals(beforeA,a.opens.get())
        a.snapshot(snapshot("构建任务 A",true));b.snapshot(snapshot("修复任务 B",true))
        until{alerts().size==2}
        Assert.assertEquals(2,alerts().map{it.id}.toSet().size)
        Assert.assertTrue(alerts().any{it.notification.extras.getString("android.text")?.contains("开发 Mac")==true})
        Assert.assertTrue(alerts().any{it.notification.extras.getString("android.text")?.contains("办公室 Windows")==true})
    }
    @Test fun renamedComputerAppearsInNotificationsWithoutRestartingItsSocket(){
        compose.onNodeWithContentDescription("快速切换电脑").performClick()
        compose.onNodeWithText("管理电脑与名称").performClick()
        compose.onNodeWithContentDescription("电脑选项 开发 Mac").performClick()
        compose.onNodeWithText("重命名").performClick()
        compose.onNodeWithContentDescription("电脑名称").performTextReplacement("工作站 A")
        val previousReads=a.actions.count{it=="thread.read"}
        compose.onNodeWithText("保存").performClick()
        until{a.actions.count{it=="thread.read"}>previousReads}
        a.snapshot(snapshot("构建任务 A",true))
        until{alerts().any{it.notification.extras.getString("android.text")?.contains("工作站 A · 构建任务 A")==true}}
        Assert.assertEquals(1,a.opens.get());Assert.assertEquals(1,b.opens.get())
    }
    @Test fun foregroundPollsApprovalsOnTheVisibleComputerWithoutASecondSocket(){
        b.extra={action,_->when(action){
            "thread.read"->snapshot("同一电脑的其他对话",true)
            "threads.list"->JSONObject().put("threads",JSONArray().put(JSONObject().put("id","local-network-test").put("hostId","local").put("title","同一电脑的其他对话").put("status","waitingForApproval").put("updatedAt","2")))
            else->null
        }}
        until{alerts().any{it.notification.extras.getString("android.text")?.contains("办公室 Windows · 同一电脑的其他对话")==true}}
        Assert.assertEquals(1,b.opens.get());Assert.assertEquals(1,a.opens.get())
        alerts().first{it.notification.extras.getString("android.text")?.contains("办公室 Windows")==true}.notification.contentIntent.send()
        compose.waitUntil(20000){compose.onAllNodesWithText("允许本次").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("允许本次").performClick();until{b.commands.any{it.optString("action")=="approval.respond"}}
        Assert.assertFalse(a.commands.any{it.optString("action")=="approval.respond"});Assert.assertEquals(1,b.opens.get())
    }
    @Test fun newlyApprovedPairingTakesOverItsMonitorSocketBeforeOpeningTheWorkspace(){
        val pairing=JSONObject(a.profile.toString()).put("v",1).put("pairCode",Encoding.secret()).put("expiresAt",System.currentTimeMillis()+600000)
        val link="codexpocket://pair?data="+Encoding.encode(pairing.toString().toByteArray())
        scenario!!.onActivity{instrumentation.callActivityOnNewIntent(it,Intent(context,MainActivity::class.java).setData(android.net.Uri.parse(link)))}
        compose.waitForIdle()
        until{a.hellos.get()==2&&b.hellos.get()==2}
        // Let the service reconcile while the newly paired workspace is visible.
        instrumentation.runOnMainSync{TaskMonitorService.foreground(context)}
        Thread.sleep(16000);Assert.assertEquals(2,a.opens.get());Assert.assertEquals(2,b.opens.get())
        a.snapshot(snapshot("新配对的电脑",true))
        until{alerts().any{it.notification.extras.getString("android.text")?.contains("新配对的电脑")==true}}
    }
}
