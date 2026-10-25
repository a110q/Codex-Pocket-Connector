package dev.codexpocket.app
import android.app.NotificationManager
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
class TaskNotificationsTest {
 @get:Rule val compose=createEmptyComposeRule()
 private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
 private val context get()=instrumentation.targetContext
 private lateinit var peer:ConnectionRecoveryTest.Peer
 private val backups=mutableMapOf<String,JSONObject>()
 private var scenario:ActivityScenario<MainActivity>?=null
 private var oldLan=false
 private var oldEnabled=false
 private fun save(name:String,value:JSONObject){backups.putIfAbsent(name,SecureStore(context,name).load());SecureStore(context,name).save(value)}
 private fun until(timeout:Long=20000,condition:()->Boolean){val end=System.currentTimeMillis()+timeout;while(!condition()){Assert.assertTrue("Timed out waiting for task notification",System.currentTimeMillis()<end);Thread.sleep(50)}}
 private fun snapshot(active:Boolean)=JSONObject().put("thread",JSONObject().put("id","local-network-test").put("hostId","local").put("title","通知验收").put("lastTurnId","notification-turn").put("lastTurnStatus",if(active)"inProgress"else"completed").put("activeTurnId",if(active)"notification-turn"else JSONObject.NULL)).put("messages",JSONArray()).put("requests",JSONArray()).put("historyComplete",true)
 @Before fun setup(){
  peer=ConnectionRecoveryTest.Peer();oldLan=context.getSharedPreferences("connection-mode",0).getBoolean("lan",false);oldEnabled=TaskNotifications.enabled(context);context.getSharedPreferences("connection-mode",0).edit().putBoolean("lan",false).commit()
  save("pocket-service.enc",JSONObject().put("selected",JSONObject().put("relay",peer.profile.getString("relay")).put("pin",peer.profile.getString("pin"))))
  val suffix=AccountApi.suffix(context);val user=JSONObject().put("id","qa-notify").put("role","user").put("accessState","active").put("status","approved").put("displayName","通知验收")
  peer.accountResponse=JSONObject().put("user",user).put("hosts",JSONArray().put(JSONObject().put("id",peer.profile.getString("hostId"))))
  save("pocket-account$suffix.enc",JSONObject().put("token","qa-notification-token").put("user",user));save("pocket-state$suffix.enc",JSONObject().put("profile",peer.profile).put("computers",JSONArray().put(peer.profile)))
  val current=snapshot(true);peer.extra={action,_->when(action){"thread.read"->current;"threads.list"->JSONObject().put("threads",JSONArray().put(JSONObject().put("id","local-network-test").put("hostId","local").put("status","active").put("updatedAt","1")));else->null}}
  instrumentation.uiAutomation.executeShellCommand("pm grant dev.codexpocket.app android.permission.POST_NOTIFICATIONS").close()
  scenario=ActivityScenario.launch(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));until{peer.hellos.get()>0}
 }
 @After fun cleanup(){
  instrumentation.runOnMainSync{TaskNotifications.setEnabled(context,false);TaskMonitorService.foreground(context)};scenario?.close();peer.close();backups.forEach{(file,value)->SecureStore(context,file).save(value)};context.getSharedPreferences("connection-mode",0).edit().putBoolean("lan",oldLan).commit();context.getSharedPreferences("task-alerts",0).edit().putBoolean("enabled",oldEnabled).commit();context.getSystemService(NotificationManager::class.java).cancelAll()
 }
 @Test fun backgroundServiceDeliversDeduplicatedEncryptedResultsAndTapRestoresTheChat(){
  instrumentation.runOnMainSync{TaskNotifications.setEnabled(context,true);TaskNotifications.observe(context,peer.profile,snapshot(true),false)}
  val before=peer.hellos.get();scenario!!.moveToState(Lifecycle.State.CREATED);until(35000){peer.hellos.get()>before}
  peer.snapshot(snapshot(false));val manager=context.getSystemService(NotificationManager::class.java)
  until{manager.activeNotifications.any{it.notification.channelId==TaskNotifications.CHANNEL}}
  val alert=manager.activeNotifications.first{it.notification.channelId==TaskNotifications.CHANNEL};Assert.assertEquals("Codex 任务已完成",alert.notification.extras.getString("android.title"));peer.snapshot(snapshot(false));Thread.sleep(250);Assert.assertEquals(1,manager.activeNotifications.count{it.notification.channelId==TaskNotifications.CHANNEL})
  AccountNotifications.cancel(context);Assert.assertTrue(manager.activeNotifications.any{it.id==7101});Assert.assertTrue(manager.activeNotifications.any{it.id==alert.id})
  alert.notification.contentIntent.send();compose.waitUntil(15000){compose.onAllNodesWithContentDescription("聊天输入框").fetchSemanticsNodes().isNotEmpty()};Assert.assertTrue(peer.commands.any{it.optString("action")=="thread.read"});Assert.assertFalse(peer.actions.any{it=="message.send"||it=="approval.respond"});val connected=peer.opens.get();Thread.sleep(750);Assert.assertEquals(connected,peer.opens.get())
  instrumentation.runOnMainSync{TaskNotifications.setEnabled(context,false)};until{manager.activeNotifications.none{it.id==7101}}
 }
 @Test fun backgroundMonitoringRefusesAnInactiveAccountOnItsCustomService(){
  peer.accountResponse!!.getJSONObject("user").put("accessState","suspended")
  val reads=peer.accountReads.get();val opens=peer.opens.get()
  instrumentation.runOnMainSync{TaskNotifications.setEnabled(context,true)};scenario!!.moveToState(Lifecycle.State.CREATED)
  until{peer.accountReads.get()>reads};Thread.sleep(400)
  Assert.assertEquals(opens,peer.opens.get());Assert.assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications.any{it.notification.channelId==TaskNotifications.CHANNEL})
 }

}
