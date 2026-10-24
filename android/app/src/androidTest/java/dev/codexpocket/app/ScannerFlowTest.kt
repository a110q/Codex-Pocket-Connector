package dev.codexpocket.app

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.pressBackUnconditionally
import com.journeyapps.barcodescanner.ScanOptions
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ScannerFlowTest {
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrument.targetContext
    @Before fun cameraPermission(){instrument.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.CAMERA)}
    private fun launch()=ActivityScenario.launchActivityForResult<PocketCaptureActivity>(ScanOptions().setCaptureActivity(PocketCaptureActivity::class.java).setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false).createScanIntent(context))
    @Test fun closeBeforeScanReturnsCancelledAndKeepsPortrait(){
        val scenario=launch()
        scenario.onActivity { activity ->
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,activity.requestedOrientation)
            val close=activity.findViewById<android.view.View>(R.id.close_scanner)
            assertEquals("关闭扫码",close.contentDescription)
            assertTrue(close.isShown)
            val root=activity.window.decorView as android.view.ViewGroup
            fun scanner(view:android.view.View):com.journeyapps.barcodescanner.DecoratedBarcodeView? {
                if(view is com.journeyapps.barcodescanner.DecoratedBarcodeView)return view
                if(view is android.view.ViewGroup)for(i in 0 until view.childCount){val found=scanner(view.getChildAt(i));if(found!=null)return found}
                return null
            }
            val box=scanner(root)!!.barcodeView.framingRectSize!!
            assertEquals(box.width,box.height)
            assertTrue(box.width < activity.resources.displayMetrics.widthPixels)
            java.io.File(context.getExternalFilesDir(null),"scanner-061.png").outputStream().use { instrument.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            close.performClick()
        }
        assertEquals(Activity.RESULT_CANCELED,scenario.result.resultCode)
        scenario.close()
    }
    @Test fun systemBackBeforeScanReturnsCancelled(){val scenario=launch();pressBackUnconditionally();assertEquals(Activity.RESULT_CANCELED,scenario.result.resultCode);scenario.close()}
}
