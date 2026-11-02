package dev.codexpocket.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.style.ImageSpan
import android.view.View
import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class MarkdownImageSupport(
    val revision:String,
    val load:suspend (String)->Bitmap,
    val preview:(String)->Unit
)

// Keep images in the Markdown tree. Splitting on image syntax loses list
// numbering, quotes, reference links and the order of text inside table cells.
internal data class MarkdownImageSource(val source:String,val label:String)

internal class InlinePicture(val bitmap:Bitmap?,private val label:String,width:Int,density:Float):Drawable(){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    init {
        val maxWidth=width.coerceAtLeast(1)
        val scale=bitmap?.let{minOf(maxWidth.toFloat()/it.width,480*density/it.height)}?:1f
        setBounds(0,0,bitmap?.let{(it.width*scale).toInt().coerceAtLeast(1)}?:maxWidth,
            bitmap?.let{(it.height*scale).toInt().coerceAtLeast(1)}?:(72*density).toInt())
        paint.textSize=12*density
    }
    override fun draw(canvas:Canvas){
        if(bitmap!=null)canvas.drawBitmap(bitmap,null,bounds,paint)
        else {
            paint.color=android.graphics.Color.argb(25,128,128,128);canvas.drawRect(bounds,paint)
            paint.color=android.graphics.Color.GRAY
            val text=android.text.TextUtils.ellipsize(label,android.text.TextPaint(paint),(bounds.width()-24).coerceAtLeast(1).toFloat(),android.text.TextUtils.TruncateAt.END).toString()
            canvas.drawText(text,bounds.left+12f,bounds.exactCenterY()-((paint.descent()+paint.ascent())/2),paint)
        }
    }
    override fun setAlpha(alpha:Int){paint.alpha=alpha}
    override fun setColorFilter(colorFilter:ColorFilter?){paint.colorFilter=colorFilter}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}

@Composable internal fun imageSpanned(content:Spanned,support:MarkdownImageSupport?,width:Int,density:Float):Spanned {
    val sources=remember(content){content.getSpans(0,content.length,MarkdownImageSource::class.java).toList()}
    val loaded=remember(content,support?.revision){mutableStateMapOf<String,Bitmap>()}
    val failed=remember(content,support?.revision){mutableStateMapOf<String,String>()}
    var retry by remember(content){mutableIntStateOf(0)}
    val currentSupport by rememberUpdatedState(support)
    LaunchedEffect(content,support?.revision,retry){
        sources.distinctBy{it.source}.forEach{source->launch{
            if(loaded.containsKey(source.source))return@launch
            failed.remove(source.source)
            try{loaded[source.source]=(currentSupport?:error("连接电脑后加载图片")).load(source.source)}
            catch(e:CancellationException){throw e}
            catch(_:Exception){failed[source.source]="图片加载失败，点击重试"}
        }}
    }
    return SpannableString(content).apply {
        sources.forEach{source->
            val start=content.getSpanStart(source);val end=content.getSpanEnd(source)
            val bitmap=loaded[source.source]
            val label=failed[source.source]?:"正在加载图片：${source.label}"
            setSpan(ImageSpan(InlinePicture(bitmap,label,width,density),ImageSpan.ALIGN_BOTTOM),start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            // A linked image may already have a URL span. The image tap belongs
            // to its preview/retry action, without two competing click targets.
            getSpans(start,end,ClickableSpan::class.java).forEach{removeSpan(it)}
            setSpan(object:ClickableSpan(){override fun onClick(widget:View){if(bitmap!=null)currentSupport?.preview?.invoke(source.source)else retry++}},start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
