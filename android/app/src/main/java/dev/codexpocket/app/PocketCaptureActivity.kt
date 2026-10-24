package dev.codexpocket.app

import android.content.Intent
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Button
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/** Portrait camera with a compact square target and an always-visible exit. */
class PocketCaptureActivity : CaptureActivity() {
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    override fun initializeContent(): DecoratedBarcodeView {
        val root=FrameLayout(this).apply { setBackgroundColor(Color.rgb(14,20,30)) }
        val scanner=DecoratedBarcodeView(this)
        root.addView(scanner,FrameLayout.LayoutParams(-1,-1))
        val box=dp(256).coerceAtMost(resources.displayMetrics.widthPixels-dp(64))
        scanner.barcodeView.framingRectSize=com.journeyapps.barcodescanner.Size(box,box)
        scanner.statusView.visibility=View.GONE
        val header=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(24),dp(16),dp(16),dp(16));setBackgroundColor(0xB30E141E.toInt()) }
        val title=TextView(this).apply { text="扫码连接电脑";setTextColor(Color.WHITE);textSize=20f }
        header.addView(title,LinearLayout.LayoutParams(0,dp(52),1f))
        val close=Button(this).apply { text="×";textSize=30f;setTextColor(Color.WHITE);setBackgroundColor(Color.TRANSPARENT);contentDescription="关闭扫码";id=R.id.close_scanner;setOnClickListener { setResult(RESULT_CANCELED,Intent());finish() } }
        header.addView(close,LinearLayout.LayoutParams(dp(52),dp(52)))
        root.addView(header,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
        val tip=TextView(this).apply { text="将电脑上的二维码放入方框内\n识别成功后会自动返回";setTextColor(Color.WHITE);textSize=15f;gravity=Gravity.CENTER;setPadding(dp(24),dp(20),dp(24),dp(32));setBackgroundColor(0xB30E141E.toInt()) }
        root.addView(tip,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        root.setOnApplyWindowInsetsListener { _,insets -> root.setPadding(0,insets.systemWindowInsetTop,0,insets.systemWindowInsetBottom);insets }
        setContentView(root);return scanner
    }
}
