package dev.codexpocket.app

import android.graphics.Bitmap
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/** Run in two separate adb instrumentation processes: changing install-source
 * permission deliberately kills the first process. Never use Gradle's install /
 * uninstall test wrapper for this system test, as it deletes the pending data. */
class SystemUpdateRecoveryTest{
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val preferencesName="update-system-recovery"
    private fun ByteArray.hash()=MessageDigest.getInstance("SHA-256").digest(this).joinToString(""){"%02x".format(it)}
    private fun awaitState(condition:()->Boolean){
        val deadline=System.currentTimeMillis()+60000
        while(System.currentTimeMillis()<deadline){var ready=false;instrumentation.runOnMainSync{ready=condition()};if(ready)return;Thread.sleep(100)}
        fail("更新状态未在规定时间内就绪。")
    }
    @Test fun prepareDownloadedUpdateAndWaitForSystemPermissionChange(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("permissionRecovery")=="prepare")
        val cert=HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val server=MockWebServer();server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(),false);server.start()
        val signing=Ed25519PrivateKeyParameters(SecureRandom())
        val prefix=byteArrayOf(0x30,0x2a,0x30,0x05,0x06,0x03,0x2b,0x65,0x70,0x03,0x21,0x00);val spki=prefix+signing.generatePublicKey().encoded
        val configJson=JSONObject().put("manifestUrl",server.url("/updates/stable.json").toString()).put("pin",cert.certificate.encoded.hash()).put("keyId",spki.hash().take(16)).put("publicKey","-----BEGIN PUBLIC KEY-----\n"+Base64.encodeToString(spki,Base64.NO_WRAP)+"\n-----END PUBLIC KEY-----")
        val apk=File(context.applicationInfo.sourceDir).readBytes()
        val payload=JSONObject().put("schema",1).put("channel","stable").put("version",BuildConfig.VERSION_NAME).put("build",BuildConfig.VERSION_CODE).put("publishedAt","2026-11-01T00:00:00Z").put("notes","安装来源授权进程恢复测试").put("assets",JSONArray().put(JSONObject().put("platform","android").put("kind","apk").put("name","Codex-Pocket-${BuildConfig.VERSION_NAME}.apk").put("url",server.url("/packages/${BuildConfig.VERSION_NAME}/Codex-Pocket-${BuildConfig.VERSION_NAME}.apk").toString()).put("size",apk.size).put("sha256",apk.hash())))
        val bytes=payload.toString().toByteArray();val signer=Ed25519Signer();signer.init(true,signing);signer.update(bytes,0,bytes.size)
        val envelope=JSONObject().put("schema",1).put("algorithm","Ed25519").put("keyId",configJson.getString("keyId")).put("payload",Base64.encodeToString(bytes,Base64.NO_WRAP)).put("signature",Base64.encodeToString(signer.generateSignature(),Base64.NO_WRAP))
        context.getSharedPreferences(preferencesName,0).edit().clear().commit()
        File(context.filesDir,"updates/${BuildConfig.VERSION_NAME}").deleteRecursively()
        server.enqueue(MockResponse().setBody(envelope.toString()));server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        lateinit var model:AppUpdateModel
        instrumentation.runOnMainSync{model=AppUpdateModel(context,UpdateClient(UpdateConfig.parse(configJson)),"0.9.9.3",35,preferencesName=preferencesName);model.check()}
        awaitState{model.status=="available"};instrumentation.runOnMainSync{model.download()};awaitState{model.download=="ready"||model.download=="error"};assertEquals(model.error,"ready",model.download)
        assertEquals(2,server.requestCount)
        File(context.filesDir,"permission-recovery-config.json").writeText(configJson.toString())
        File(context.filesDir,"permission-recovery-before.json").writeText(JSONObject().put("pid",android.os.Process.myPid()).put("requests",server.requestCount).put("apkSha256",apk.hash()).toString())
        instrumentation.runOnMainSync{model.install()}
        Thread.sleep(60000)
        fail("外部驱动必须在系统设置授权并观察应用进程结束。")
    }
    @Test fun restoreOfflineInNewProcessAndOpenSystemInstaller(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("permissionRecovery")=="restore")
        val before=JSONObject(File(context.filesDir,"permission-recovery-before.json").readText())
        assertNotEquals(before.getInt("pid"),android.os.Process.myPid());assertTrue(context.packageManager.canRequestPackageInstalls())
        val config=UpdateConfig.parse(JSONObject(File(context.filesDir,"permission-recovery-config.json").readText()))
        lateinit var model:AppUpdateModel
        instrumentation.runOnMainSync{model=AppUpdateModel(context,UpdateClient(config),"0.9.9.3",35,preferencesName=preferencesName)}
        try{
            awaitState{!model.busy};assertEquals(model.error,"ready",model.download)
            val intent=model.installIntent();assertEquals(before.getString("apkSha256"),context.contentResolver.openInputStream(intent.data!!)!!.use{it.readBytes().hash()})
            instrumentation.runOnMainSync{model.checkAutomatically();model.install()}
            val ui=instrumentation.uiAutomation;val deadline=System.currentTimeMillis()+15000
            while(ui.rootInActiveWindow?.packageName?.toString()?.contains("packageinstaller")!=true&&System.currentTimeMillis()<deadline)Thread.sleep(100)
            assertTrue(ui.rootInActiveWindow?.packageName?.toString()?.contains("packageinstaller")==true)
            File(context.getExternalFilesDir(null),"update-restored-system-installer.png").outputStream().use{ui.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)}
            File(context.filesDir,"permission-recovery-result.json").writeText(JSONObject().put("beforePid",before.getInt("pid")).put("restoredPid",android.os.Process.myPid()).put("download",model.download).put("offlineRecovery",true).put("originalRequests",before.getInt("requests")).put("installerOpened",true).toString())
        }finally{instrumentation.runOnMainSync{model.close()}}
    }
}
