package dev.codexpocket.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import java.util.concurrent.atomic.AtomicReference

// Isolated TLS/encrypted transport and store: no real relay or user projects.
class ProjectSyncTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var peer:ConnectionRecoveryTest.Peer
    private lateinit var model:PocketModel
    private val filename="qa-project-host-sync.enc"
    private lateinit var saved:JSONObject
    private val frames=AtomicReference<JSONArray>()
    private val chats=AtomicReference<JSONArray>()
    private var revision=0
    private fun frame(host:Any?,kind:String="local",includeHost:Boolean=true):JSONArray {
        val yunqi=JSONObject().put("projectId","yunqi").put("label","YunqiPay").put("projectKind",kind).put("revision",++revision)
        if(includeHost)yunqi.put("hostId",host)
        return JSONArray().put(yunqi)
            .put(JSONObject().put("projectId","box-old").put("canonicalProjectId","box-new").put("label","全能文件保管箱").put("projectKind","local").put("hostId",host))
            .put(JSONObject().put("projectId","clean").put("label","C盘清理大师"))
            .put(JSONObject().put("projectId","excel").put("label","excel逆向修复"))
    }
    @Before fun setup(){
        saved=SecureStore(context,filename).load()
        peer=ConnectionRecoveryTest.Peer()
        frames.set(frame(JSONObject.NULL))
        chats.set(JSONArray().put(JSONObject().put("id","project-chat").put("projectId","box-new").put("hostId",JSONObject.NULL).put("title","项目内的对话").put("status","idle")))
        peer.extra={action,args->when(action){
            "projects.list"->JSONObject().put("projects",frames.get())
            "threads.list"->JSONObject().put("threads",chats.get())
            "thread.read"->JSONObject().put("thread",JSONObject().put("id",args.getString("threadId")).put("hostId",JSONObject.NULL)).put("messages",JSONArray()).put("requests",JSONArray()).put("historyComplete",true)
            "thread.create"->JSONObject().put("threadId","phone-created").put("hostId","local")
            else->null
        }}
        SecureStore(context,filename).save(JSONObject().put("profile",peer.profile).put("appearance","light"))
        compose.runOnUiThread{model=PocketModel(context,filename);model.start()}
        compose.setContent{PocketTheme("light"){PocketApp(model,{},null,{})}}
        until{model.connected&&model.projects.size==4&&model.threads.size==1}
    }
    @After fun cleanup(){try{compose.runOnUiThread{model.stop()}}finally{try{peer.close()}finally{SecureStore(context,filename).save(saved)}}}
    private fun until(condition:()->Boolean){compose.waitUntil(10000){var result=false;compose.runOnUiThread{result=condition()};result}}
    @Test fun nullAndBlankLocalHostsKeepAllDesktopProjectsAndMembership(){
        for(host in listOf(JSONObject.NULL,"","  ","local",null)){
            frames.set(frame(host,includeHost=host!=null))
            chats.get().getJSONObject(0).put("hostId",host)
            val expectedRevision=revision
            compose.runOnUiThread{model.refresh()}
            until{model.projects.size==4&&model.projects[0].optInt("revision")==expectedRevision&&model.threads[0].optString("hostId")=="local"}
            Assert.assertEquals("local",model.projects[0].optString("hostId"))
            Assert.assertTrue(threadBelongsToProject(model.threads[0],model.projects[1]))
            compose.onNodeWithText("YunqiPay").assertIsDisplayed()
            compose.onNodeWithText("项目内的对话").assertIsDisplayed()
            compose.onNodeWithText("独立对话").assertDoesNotExist()
        }
        compose.onNodeWithText("项目内的对话").performClick()
        until{!model.loadingThread&&model.snapshot!=null}
        Assert.assertNull(model.error)
        Assert.assertEquals("local",peer.commands.last{it.optString("action")=="thread.read"}.getJSONObject("args").getString("hostId"))
        compose.runOnUiThread{model.create("项目内新建验证","box-old",null)}
        until{peer.actions.contains("thread.create")&&!model.busy}
        Assert.assertEquals("box-old",peer.commands.last{it.optString("action")=="thread.create"}.getJSONObject("args").getString("projectId"))
        until{SecureStore(context,filename).load().optJSONArray("projects")?.length()==4}
        compose.runOnUiThread{
            model.stop()
            val restored=PocketModel(context,filename)
            Assert.assertEquals(4,restored.projects.size)
            Assert.assertEquals("local",restored.projects[0].getString("hostId"))
            Assert.assertTrue(threadBelongsToProject(restored.threads[0],restored.projects[1]))
            restored.stop()
        }
    }
    @Test fun remoteProjectsStayOnTheirHostAndDeletedEmptyProjectsDisappear(){
        frames.set(frame("remote-host"))
        compose.runOnUiThread{model.refresh()};until{model.projects.size==2}
        compose.onNodeWithText("YunqiPay").assertDoesNotExist()
        frames.set(frame(JSONObject.NULL,kind="remote"))
        compose.runOnUiThread{model.refresh()};until{model.projects.size==3}
        compose.onNodeWithText("YunqiPay").assertDoesNotExist()
        frames.set(frame(JSONObject.NULL))
        compose.runOnUiThread{model.refresh()};until{model.projects.size==4}
        compose.onNodeWithText("YunqiPay").assertIsDisplayed()
        frames.set(JSONArray().put(frames.get().getJSONObject(1)).put(frames.get().getJSONObject(2)).put(frames.get().getJSONObject(3)))
        compose.runOnUiThread{model.refresh()};until{model.projects.size==3}
        compose.onNodeWithText("YunqiPay").assertDoesNotExist()
        until{SecureStore(context,filename).load().optJSONArray("projects")?.length()==3}
        compose.runOnUiThread{
            model.stop()
            val restored=PocketModel(context,filename)
            Assert.assertFalse(restored.projects.any{it.optString("projectId")=="yunqi"})
            restored.stop()
        }
        Assert.assertFalse(peer.actions.contains("thread.delete"))
    }
    @Test fun oldNullAndBlankHostCachesNormalizeOnUpgradeAndComputerSwitch(){
        val cacheFile="qa-old-project-host-cache.enc"
        val store=SecureStore(context,cacheFile)
        val previous=store.load()
        val second=JSONObject(peer.profile.toString()).put("hostId","second-fixture-computer")
        fun cache(host:Any)=JSONObject().put("projects",frame(host)).put("threads",JSONArray().put(JSONObject()
            .put("id","cached-chat").put("projectId","box-new").put("hostId",host)))
        val firstCache=cache(JSONObject.NULL)
        val nextCache=cache("  ")
        val initial=JSONObject(firstCache.toString()).put("profile",peer.profile)
            .put("computers",JSONArray().put(peer.profile).put(second))
            .put("computerStates",JSONObject().put(second.getString("hostId"),nextCache))
        store.save(initial)
        var restored:PocketModel?=null
        try{
            compose.runOnUiThread{
                restored=PocketModel(context,cacheFile)
                val current=restored!!
                Assert.assertEquals(4,current.projects.size)
                Assert.assertEquals("local",current.projects[0].getString("hostId"))
                Assert.assertEquals("local",current.threads[0].getString("hostId"))
                Assert.assertTrue(threadBelongsToProject(current.threads[0],current.projects[1]))
                Assert.assertTrue(current.switchComputer(second.getString("hostId")))
                Assert.assertEquals(4,current.projects.size)
                Assert.assertEquals("local",current.projects[0].getString("hostId"))
                Assert.assertEquals("local",current.threads[0].getString("hostId"))
                Assert.assertTrue(threadBelongsToProject(current.threads[0],current.projects[1]))
                current.stop()
                val reloaded=PocketModel(context,cacheFile)
                Assert.assertEquals(second.getString("hostId"),reloaded.profile!!.getString("hostId"))
                Assert.assertEquals("local",reloaded.projects[0].getString("hostId"))
                Assert.assertTrue(threadBelongsToProject(reloaded.threads[0],reloaded.projects[1]))
                reloaded.stop()
            }
        }finally{
            compose.runOnUiThread{restored?.stop()}
            store.save(previous)
        }
    }
}
