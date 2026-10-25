package dev.codexpocket.app

import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// A local TLS peer exercises the real OkHttp socket, encryption, handshake,
// PocketModel retry timer, and reload. It never pairs to the user's relay.
class ConnectionRecoveryTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private lateinit var backup:JSONObject
    private lateinit var peer:Peer
    private var model:PocketModel?=null
    @Before fun setup(){backup=SecureStore(context).load();peer=Peer()}
    @After fun cleanup(){
        try{instrumentation.runOnMainSync{model?.stop()}}
        finally{try{peer.close()}finally{SecureStore(context).save(backup)}}
    }
    private fun start(){
        SecureStore(context).save(JSONObject().put("profile",peer.profile))
        instrumentation.runOnMainSync{model=PocketModel(context);model!!.start()}
    }
    private fun until(timeout:Long=5000,condition:()->Boolean){val end=System.currentTimeMillis()+timeout;while(true){var done=false;instrumentation.runOnMainSync{done=condition()};if(done)return;Assert.assertTrue("Timed out waiting for connection recovery",System.currentTimeMillis()<end);Thread.sleep(25)}}
    @Test fun lostHelloAutomaticallyReconnectsAndReloadsProjects(){
        peer.dropFirstHello=true;start()
        until(32000){model!!.connected&&model!!.projects.isNotEmpty()}
        Assert.assertEquals(2,peer.opens.get())
        Assert.assertEquals(2,peer.hellos.get())
        Assert.assertTrue(peer.auth.all{it=="Bearer ${peer.profile.getString("mobileToken")}"})
        Assert.assertTrue(peer.actions.contains("projects.list"))
    }
    @Test fun desktopReturnsOnTheSameRelayAndUncertainMessageIsNeverResent(){
        start();until{model!!.connected&&model!!.projects.isNotEmpty()}
        instrumentation.runOnMainSync{model!!.openThread(JSONObject().put("id","local-network-test").put("hostId","local").put("title","本地断网验证"))}
        until{!model!!.loadingThread}
        peer.ignoreMessages=true
        instrumentation.runOnMainSync{model!!.send("LOCAL TEST ONLY")}
        until{peer.actions.count{it=="message.send"}==1}
        peer.status(false)
        until{!model!!.connected&&!model!!.busy&&model!!.pendingOperation!=null}
        Thread.sleep(4500)
        Assert.assertEquals(1,peer.opens.get())
        peer.status(true)
        until{model!!.connected&&peer.hellos.get()==2&&!model!!.loadingThread}
        Assert.assertEquals(1,peer.opens.get())
        Assert.assertEquals(1,peer.actions.count{it=="message.send"})
        Assert.assertNotNull(model!!.pendingOperation)
    }
    @Test fun rejectedAuthorizationDoesNotStartARetryLoop(){
        peer.rejectAuthorization=true;start()
        until{model!!.status==ConnectionState.AUTH_FAILED.label}
        Thread.sleep(4500)
        Assert.assertEquals(1,peer.server.requestCount)
        Assert.assertFalse(model!!.connected)
    }
    @Test fun repeatedForegroundAndAccountStartsKeepTheExistingConnection(){
        start();until{model!!.connected&&model!!.projects.isNotEmpty()}
        instrumentation.runOnMainSync{
            repeat(3){
                model!!.authorizeAccount(AccountAuthorization("local-test-user","local-test-session",peer.profile.getString("hostId"),peer.profile.getString("relay"),peer.profile.getString("pin")))
                model!!.start()
            }
        }
        instrumentation.waitForIdleSync()
        Assert.assertTrue(model!!.connected)
        Assert.assertFalse(model!!.projects.isEmpty())
        Assert.assertEquals(1,peer.opens.get())
        Assert.assertEquals(1,peer.hellos.get())
        Assert.assertNull(model!!.error)
    }
    @Test fun pausingDuringAnApprovalQueryDoesNotDisplayCancellationAsAnError(){
        peer.holdPairStatus=true
        SecureStore(context).save(JSONObject().put("profile",JSONObject(peer.profile.toString()).put("approved",false)))
        instrumentation.runOnMainSync{model=PocketModel(context);model!!.start()}
        until{peer.pairStatusRequests.get()==1}
        instrumentation.runOnMainSync{model!!.stop()}
        peer.releasePairStatus.countDown()
        until{!peer.holdPairStatus}
        // The canceled IO continuation is dispatched back to the main thread.
        instrumentation.waitForIdleSync()
        Assert.assertNull(model!!.error)
        Assert.assertFalse(model!!.connected)
        instrumentation.runOnMainSync{model!!.start()}
        until{model!!.connected&&model!!.projects.isNotEmpty()}
        Assert.assertNull(model!!.error)
    }
    internal class Peer {
        val server=MockWebServer()
        val opens=AtomicInteger();val hellos=AtomicInteger()
        val actions=CopyOnWriteArrayList<String>();val auth=CopyOnWriteArrayList<String?>()
        @Volatile var simulatePairing=false
        @Volatile var postedPair=false
        @Volatile var approvePair=false
        @Volatile var denyPair=false
        @Volatile var transientPairStatus=0
        val messages=CopyOnWriteArrayList<JSONObject>()
        val commands=CopyOnWriteArrayList<JSONObject>()
        var extra:((String,JSONObject)->JSONObject?)?=null
        var capabilities=JSONObject()
        var accountResponse:JSONObject?=null
        val accountReads=AtomicInteger()
        private val channels=java.util.concurrent.ConcurrentHashMap<WebSocket,String>()
        val imageBytes=java.io.ByteArrayOutputStream()
        var dropFirstHello=false;var ignoreMessages=false;var rejectAuthorization=false
        @Volatile var holdPairStatus=false
        val pairStatusRequests=AtomicInteger()
        val releasePairStatus=CountDownLatch(1)
        private val sockets=CopyOnWriteArrayList<WebSocket>()
        private val host="network-test-host-abcdefghijklmnop"
        private val key=Encoding.secret()
        private val codec=DesktopCodec(key,host)
        val profile:JSONObject
        init{
            val cert=HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
            val tls=HandshakeCertificates.Builder().heldCertificate(cert).build()
            server.useHttps(tls.sslSocketFactory(),false)
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher(){override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):MockResponse{
                if(rejectAuthorization)return MockResponse().setResponseCode(401).setBody("{}")
                if(request.path=="/api/account/me"){accountReads.incrementAndGet();return MockResponse().setBody((accountResponse?:JSONObject()).toString())}
                if(request.path=="/api/pair") {postedPair=true;return MockResponse().setBody("{\"state\":\"pending\"}")}
                if(request.path=="/api/pair/status"){
                    if(simulatePairing){
                        if(!postedPair||denyPair)return MockResponse().setResponseCode(401).setBody("{\"error\":\"配对已失效或被拒绝\"}")
                        if(transientPairStatus>0){transientPairStatus--;return MockResponse().setResponseCode(503).setBody("{\"error\":\"temporary failure\"}")}
                        return MockResponse().setBody(if(approvePair)"{\"state\":\"approved\"}"else"{\"state\":\"pending\"}")
                    }
                    pairStatusRequests.incrementAndGet()
                    if(holdPairStatus){releasePairStatus.await(10,TimeUnit.SECONDS);holdPairStatus=false}
                    return MockResponse().setHeader("Content-Type","application/json").setBody("{\"state\":\"approved\"}")
                }
                return MockResponse().withWebSocketUpgrade(object:WebSocketListener(){
                    override fun onOpen(webSocket:WebSocket,response:Response){sockets.add(webSocket);opens.incrementAndGet();auth.add(request.getHeader("Authorization"));webSocket.send(JSONObject().put("type","status").put("peerOnline",true).toString())}
                    override fun onMessage(webSocket:WebSocket,text:String){
                        val payload=codec.decode(text);val channel=payload.getString("channel")
                        if(payload.optString("type")=="hello"){
                            channels[webSocket]=channel;val n=hellos.incrementAndGet();if(dropFirstHello&&n==1)return
                            webSocket.send(codec.encode(JSONObject().put("type","hello").put("channel",channel).put("desktopReady",true).put("capabilities",capabilities)));return
                        }
                        val action=payload.getString("action");actions.add(action)
                        if(action=="message.send"){messages.add(payload.getJSONObject("args"));if(ignoreMessages)return}
                        val args=payload.getJSONObject("args");commands.add(payload)
                        val result=extra?.invoke(action,args)?:when(action){
                            "image.upload.start"->JSONObject().put("nextOffset",imageBytes.size())
                            "image.upload.chunk"->{Assert.assertEquals(imageBytes.size(),args.getInt("offset"));imageBytes.write(android.util.Base64.decode(args.getString("data"),android.util.Base64.DEFAULT));JSONObject().put("nextOffset",imageBytes.size())}
                            "image.upload.finish"->JSONObject().put("done",true)
                            "projects.list"->JSONObject().put("projects",JSONArray().put(JSONObject().put("projectId","network-qa").put("title","已恢复的测试项目").put("hostId","local")))
                            "threads.list"->JSONObject().put("threads",JSONArray())
                            "models.list"->JSONObject().put("models",JSONArray())
                            "thread.read"->JSONObject().put("thread",JSONObject().put("id","local-network-test").put("hostId","local")).put("messages",JSONArray()).put("requests",JSONArray()).put("historyComplete",true)
                            else->JSONObject()
                        }
                        webSocket.send(codec.encode(JSONObject().put("type","response").put("channel",channel).put("id",payload.getString("id")).put("result",result)))
                    }
                    override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?){}
                })
            }}
            server.start()
            val pin=Encoding.hex(MessageDigest.getInstance("SHA-256").digest(cert.certificate.encoded))
            profile=JSONObject().put("relay",server.url("/").toString().trimEnd('/')).put("hostId",host).put("key",key).put("pin",pin).put("mobileToken",Encoding.secret()).put("approved",true)
        }
        fun snapshot(value:JSONObject){channels.forEach{(socket,channel)->socket.send(codec.encode(JSONObject().put("type","event").put("channel",channel).put("event",JSONObject().put("type","thread.updated").put("threadId",value.getJSONObject("thread").getString("id")).put("data",value))))}}
        fun status(online:Boolean){sockets.last().send(JSONObject().put("type","status").put("peerOnline",online).toString())}
        fun close(){releasePairStatus.countDown();try{sockets.forEach{it.close(1000,"Local test finished")}}finally{server.shutdown()}}
    }
    // The fixture's server role follows the wire specification independently
    // of PocketConnection's handshake implementation.
    private class DesktopCodec(pairKey:String,private val host:String){
        private val sid=UUID.randomUUID().toString();private var seq=0L
        private val root=Encoding.decode(pairKey)
        private fun key(direction:String):ByteArray{
            val extract=Mac.getInstance("HmacSHA256").apply{init(SecretKeySpec(host.toByteArray(),"HmacSHA256"))}.doFinal(root)
            return Mac.getInstance("HmacSHA256").apply{init(SecretKeySpec(extract,"HmacSHA256"))}.doFinal("codex-pocket/v1/$direction".toByteArray()+byteArrayOf(1))
        }
        @Synchronized fun encode(payload:JSONObject):String{
            val direction="desktop-to-mobile";val n=++seq;val iv=ByteArray(12).also{java.security.SecureRandom().nextBytes(it)}
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.ENCRYPT_MODE,SecretKeySpec(key(direction),"AES"),GCMParameterSpec(128,iv));updateAAD("codex-pocket/v1|$host|$direction|$sid|$n".toByteArray())}
            return JSONObject().put("v",1).put("sid",sid).put("seq",n).put("iv",Encoding.encode(iv)).put("data",Encoding.encode(cipher.doFinal(payload.toString().toByteArray()))).toString()
        }
        fun decode(text:String):JSONObject{
            val frame=JSONObject(text);val direction="mobile-to-desktop"
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.DECRYPT_MODE,SecretKeySpec(key(direction),"AES"),GCMParameterSpec(128,Encoding.decode(frame.getString("iv"))));updateAAD("codex-pocket/v1|$host|$direction|${frame.getString("sid")}|${frame.getLong("seq")}".toByteArray())}
            return JSONObject(String(cipher.doFinal(Encoding.decode(frame.getString("data")))))
        }
    }
}
