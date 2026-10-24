package dev.codexpocket.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.json.JSONObject
import org.junit.*
import java.io.File

class PairingImagesTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val context get()=compose.activity.applicationContext
    private lateinit var peer:ConnectionRecoveryTest.Peer
    private lateinit var model:PocketModel
    private val storeName="qa-pair-images.enc"
    @Before fun setup(){
        SecureStore(context,storeName).save(JSONObject());peer=ConnectionRecoveryTest.Peer()
        compose.runOnUiThread{model=PocketModel(context,storeName);model.start()}
        compose.setContent{PocketTheme("light"){PocketApp(model,{},null,{})}}
    }
    @After fun cleanup(){compose.runOnUiThread{model.stop()};peer.close();SecureStore(context,storeName).save(JSONObject())}
    private fun until(condition:()->Boolean){compose.waitUntil(15000){var ready=false;compose.runOnUiThread{ready=condition()};ready}}
    private fun pair(){
        val p=JSONObject(peer.profile.toString()).put("v",1).put("mode","cloud").put("pairCode",Encoding.secret()).put("expiresAt",System.currentTimeMillis()+600000)
        compose.runOnUiThread{model.authorizeAccount(AccountAuthorization("qa-user",Encoding.secret(),p.getString("hostId"),p.getString("relay"),p.getString("pin")));model.pair(p.toString())}
    }
    @Test fun transientErrorsKeepTheWaitingScreenAndApprovalAutomaticallyOpensWorkspace(){
        peer.simulatePairing=true;peer.transientPairStatus=1;pair()
        until{peer.postedPair&&model.pairing}
        compose.onNodeWithText("配对中，等待电脑确认").assertIsDisplayed()
        until{model.status.contains("网络恢复")}
        Assert.assertNull(model.error)
        compose.onNodeWithText("配对中，等待电脑确认").assertIsDisplayed()
        peer.approvePair=true;until{model.connected&&!model.pairing}
        compose.onNodeWithText("配对中，等待电脑确认").assertDoesNotExist()
    }
    @Test fun rejectedPairingShowsAnActionableErrorAndCanBeCancelled(){
        peer.simulatePairing=true;pair();until{peer.postedPair};peer.denyPair=true
        until{model.error!=null};compose.onNodeWithText("配对尚未完成").assertIsDisplayed()
        compose.onNodeWithText("取消配对").performClick();until{model.profile==null}
    }
    @Test fun imageAndTextShareOneEncryptedMessageAndSelectedImageCanBeRemoved(){
        pair();until{model.connected}
        compose.runOnUiThread{model.openThread(JSONObject().put("id","local-network-test").put("hostId","local").put("title","图片传输验收"))};until{!model.loadingThread}
        val file=File(context.cacheDir,"qa-outgoing.png");val bitmap=Bitmap.createBitmap(80,60,Bitmap.Config.ARGB_8888);bitmap.eraseColor(android.graphics.Color.BLUE);file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        compose.runOnUiThread{model.addImages(listOf(Uri.fromFile(file)))};until{!model.busy&&model.imagesFor(model.imageDraftKey).size==1}
        compose.onNodeWithContentDescription("待发送图片 1").assertIsDisplayed()
        compose.onNodeWithContentDescription("移除图片 1").performClick();until{model.imagesFor(model.imageDraftKey).isEmpty()}
        compose.runOnUiThread{model.addImages(listOf(Uri.fromFile(file)))};until{!model.busy&&model.imagesFor(model.imageDraftKey).size==1}
        compose.onNodeWithContentDescription("聊天输入框").performTextInput("请描述蓝色图片")
        compose.onNodeWithContentDescription("发送").performClick();until{!model.busy&&peer.messages.size==1}
        Assert.assertEquals("请描述蓝色图片",peer.messages[0].getString("text"));Assert.assertEquals(1,peer.messages[0].getJSONArray("imageUploadIds").length());Assert.assertTrue(peer.imageBytes.size()>100)
        Assert.assertEquals(1,peer.actions.count{it=="message.send"});Assert.assertNull(model.pendingOperation);Assert.assertTrue(model.imagesFor(model.imageDraftKey).isEmpty())
    }
}
