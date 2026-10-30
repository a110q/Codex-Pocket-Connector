package dev.codexpocket.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.webkit.WebView
import android.webkit.WebViewClient
import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.*
import org.json.JSONObject


@Composable fun PreviewPane(model:PocketModel,modifier:Modifier,expanded:Boolean,toggleExpand:()->Unit,onAddressFocusChange:(Boolean)->Unit={},minimized:Boolean=false,onMinimize:()->Unit={}) {
    val kind=model.previewKind?:return
    var menu by remember{mutableStateOf(false)}
    Surface(modifier.padding(horizontal=10.dp,vertical=7.dp),shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surface,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start=14.dp,end=2.dp).heightIn(min=54.dp),verticalAlignment=Alignment.CenterVertically){
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary){PocketIcon(when(kind){"browser"->"网页";"image"->"图片";else->"文件"},Modifier.size(20.dp))}
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)){
                    TextButton(onClick={menu=true},contentPadding=PaddingValues(horizontal=4.dp)){
                        Text(when(kind){"browser"->"网页预览";"image"->"图片预览";else->"文件预览"},fontSize=13.sp,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.width(5.dp));PocketIcon("下拉",Modifier.size(11.dp))
                    }
                    DropdownMenu(expanded=menu,onDismissRequest={menu=false}){
                        listOf("browser" to "网页","file" to "文件","image" to "图片").forEach{(id,name)->DropdownMenuItem(text={Text(name)},onClick={model.showPreviewTab(id);menu=false})}
                    }
                }
                PocketAction("全屏",if(expanded)"退出展开预览" else "展开预览",onClick=toggleExpand)
                PocketAction(if(minimized)"下拉" else "上拉",if(minimized)"恢复预览" else "收起面板",onClick=onMinimize)
                PocketAction("关闭","关闭预览"){model.closePreview()}
            }
            if(!minimized){
            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
            model.downloadStatus?.let{Text(it,Modifier.padding(horizontal=14.dp,vertical=4.dp),fontSize=11.sp)}
            if(kind=="browser")BrowserPreview(model,Modifier.weight(1f),onAddressFocusChange)
            else {
                val artifact=model.previewArtifact
                val matching=artifact!=null&&artifact.optString("mime").startsWith("image/")==(kind=="image")
                if(matching) {
                    Row(Modifier.fillMaxWidth().padding(end=8.dp),verticalAlignment=Alignment.CenterVertically){PocketAction("返回","返回文件列表"){model.showArtifactList(kind)};Text(artifact!!.optString("name"),Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=12.sp,fontWeight=FontWeight.Medium);TextButton(onClick={model.download(artifact)},enabled=model.connected&&model.downloadStatus==null){Text("保存",fontSize=12.sp)}}
                    if(model.previewLoading)LinearProgressIndicator(Modifier.fillMaxWidth())
                    model.previewError?.let{Column(Modifier.padding(16.dp)){Text(it,fontSize=12.sp,color=MaterialTheme.colorScheme.error);TextButton(onClick={model.preview(artifact!!)},enabled=model.connected){Text("重新加载")}}}
                    model.previewFile?.let{file->when {
                        artifact!!.optString("mime")=="application/pdf"->PdfPreview(file,Modifier.weight(1f))
                        kind=="image"&&artifact.optString("mime")!="image/svg+xml"->RasterPreview(file,Modifier.weight(1f))
                        else->TextFilePreview(file,artifact.optString("name"),Modifier.weight(1f),model::openLink)
                    }}
                }else if(model.previewLoading||model.previewError!=null){
                    Column(Modifier.fillMaxWidth().weight(1f).padding(18.dp)){
                        if(model.previewLoading){LinearProgressIndicator(Modifier.fillMaxWidth());Text("正在打开电脑上的文件…",Modifier.padding(top=12.dp),fontSize=13.sp)}
                        model.previewError?.let{Text(it,color=MaterialTheme.colorScheme.error,fontSize=13.sp)}
                    }
                }else {
                    val artifacts=model.snapshot?.optJSONArray("artifacts").objects().filter{it.optString("mime").startsWith("image/")==(kind=="image")}
                    LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                        if(artifacts.isEmpty())item{EmptyState("暂无${if(kind=="image")"图片" else "文件"}","当前对话的交付文件会显示在这里。")}
                        items(artifacts){item->ArtifactRow(item){model.preview(item)}}
                    }
                }
            }
        }
        }
    }
}


@Composable private fun BrowserPreview(model: PocketModel, modifier: Modifier,onAddressFocusChange:(Boolean)->Unit) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    var address by remember(model.previewUrl) { mutableStateOf(model.previewUrl) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var receivedBytes by remember(model.previewUrl,model.previewRevision) { mutableLongStateOf(0) }
    var loadGeneration by remember { mutableIntStateOf(0) }
    var proxyPort by remember(model.previewUrl, model.previewRevision) { mutableIntStateOf(0) }
    val proxy = remember(model.previewUrl, model.previewRevision) { PreviewProxy(model::rpc) }
    fun openAddress() {
        if(model.reopenBrowser(address.trim()))focus.clearFocus()
        else problem=model.error
    }
    LaunchedEffect(loading,loadGeneration,proxy){
        if(loading){
            val started=SystemClock.elapsedRealtime();var lastProgress=started;var previous=proxy.receivedBytes.get()
            while(loading){
                delay(1000)
                val now=SystemClock.elapsedRealtime();val current=proxy.receivedBytes.get();receivedBytes=current
                if(current>previous){lastProgress=now;previous=current}
                if(now-lastProgress>=45000||now-started>=180000){
                    loading=false;web?.stopLoading()
                    problem=if(current==0L)"未能从电脑收到网页，请确认项目已启动、电脑仍在线后重试。" else "网页加载未完成，请检查网络后重新打开。"
                }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { onAddressFocusChange(false) } }
    LaunchedEffect(proxy) {
        if (model.previewUrl.isBlank()) return@LaunchedEffect
        loading = true; problem = null
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) error("请更新 Android System WebView 后再使用网页预览")
            val port = withTimeout(20000) { withContext(Dispatchers.IO) { proxy.start(model.previewUrl) } }
            withTimeout(15000) { suspendCancellableCoroutine<Unit> { continuation ->
                ProxyController.getInstance().setProxyOverride(ProxyConfig.Builder().addProxyRule("127.0.0.1:$port").removeImplicitRules().build(), context.mainExecutor) {
                    if (continuation.isActive) continuation.resumeWith(Result.success(Unit))
                }
            } }
            proxyPort = port
        } catch (e: TimeoutCancellationException) { loading=false;problem="网页连接建立超时，请重新打开。" }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { loading = false; problem = e.message }
    }
    DisposableEffect(proxy) {
        onDispose { web?.stopLoading(); web?.destroy(); web = null; proxy.close()
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) ProxyController.getInstance().clearProxyOverride(context.mainExecutor) { }
        }
    }
    Column(modifier) {
        val localHtml=model.previewArtifact?.takeIf{it.optString("mime").startsWith("text/html")}
        if(localHtml!=null)Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(horizontal=14.dp),verticalAlignment=Alignment.CenterVertically){
            PocketIcon("文件",Modifier.size(18.dp));Spacer(Modifier.width(10.dp))
            Text(localHtml.optString("name"),Modifier.weight(1f),fontSize=13.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text("电脑文件",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            PocketAction("刷新","刷新网页"){model.preview(localHtml)}
        }else{
        Row(Modifier.fillMaxWidth().heightIn(min=68.dp).padding(horizontal=10.dp),verticalAlignment=Alignment.CenterVertically){
            if(web?.canGoBack()==true)PocketAction("返回","网页后退"){web?.goBack()}
            TextField(address,{address=it},modifier=Modifier.weight(1f).padding(vertical=6.dp).heightIn(min=56.dp).onFocusChanged{onAddressFocusChange(it.isFocused)}.semantics{contentDescription="网页地址"},shape=RoundedCornerShape(11.dp),singleLine=true,textStyle=LocalTextStyle.current.copy(fontSize=14.sp),placeholder={Text("输入网页地址",fontSize=14.sp)},leadingIcon={PocketIcon("网页",Modifier.size(18.dp))},trailingIcon={if(web!=null)PocketAction("刷新","刷新网页",onClick=::openAddress)},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri,imeAction=ImeAction.Go),keyboardActions=KeyboardActions(onGo={openAddress()}),colors=TextFieldDefaults.colors(focusedContainerColor=MaterialTheme.colorScheme.surfaceVariant,unfocusedContainerColor=MaterialTheme.colorScheme.surfaceVariant,focusedIndicatorColor=androidx.compose.ui.graphics.Color.Transparent,unfocusedIndicatorColor=androidx.compose.ui.graphics.Color.Transparent))
            Spacer(Modifier.width(6.dp));FilledIconButton(onClick=::openAddress,shape=RoundedCornerShape(11.dp),modifier=Modifier.size(48.dp).semantics{contentDescription="打开网页"}){PocketIcon("前往")}
        }
        }
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(if(receivedBytes==0L)"正在连接电脑上的网页…" else "正在加载网页 · 已接收 ${if(receivedBytes<1024*1024) "${receivedBytes/1024} KB" else String.format(java.util.Locale.ROOT,"%.1f MB",receivedBytes/1048576.0)}",Modifier.padding(horizontal=14.dp,vertical=6.dp),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        problem?.let { message->Column(Modifier.padding(12.dp)){
            Text(message,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
            TextButton(onClick={model.reopenBrowser(address.trim())},enabled=model.connected){Text("重新打开网页")}
        } }
        if (proxyPort > 0) key(proxyPort) {
            AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = {
                WebView(it).apply {
                    settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                    settings.allowFileAccess = false; settings.allowContentAccess = false
                    settings.setSupportZoom(true); settings.builtInZoomControls = true; settings.displayZoomControls = false
                    settings.loadWithOverviewMode = true; settings.useWideViewPort = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { loadGeneration++;loading = true; problem = null; address = url }
                        override fun onPageFinished(view: WebView, url: String) { loading = false; address = url }
                        override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean = request.url.scheme !in listOf("http", "https")
                        override fun onReceivedHttpError(view: WebView, request: android.webkit.WebResourceRequest, response: android.webkit.WebResourceResponse) { if (request.isForMainFrame) { loading = false; problem = if(response.statusCode==502)proxy.failure.get()?.let{"电脑网页连接失败：$it"}?:"网页返回 HTTP 502" else "网页返回 HTTP ${response.statusCode}" } }
                        override fun onReceivedError(view: WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError) { if (request.isForMainFrame) { loading = false; problem = "网页加载失败：${proxy.failure.get()?:error.description}" } }
                    }
                    web = this; loadUrl(model.previewUrl)
                }
            })
        } else Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) { if (model.previewUrl.isBlank()) EmptyState("打开电脑上的网页", "输入电脑本地服务或网站的完整地址。") }
    }
}


@Composable internal fun RasterPreview(file:java.io.File,modifier:Modifier) {
    var bitmap by remember(file){mutableStateOf<Bitmap?>(null)};var failure by remember(file){mutableStateOf<String?>(null)}
    LaunchedEffect(file){try{bitmap=withContext(Dispatchers.IO){val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(file.path,bounds);var sample=1;while(bounds.outWidth/sample>4096||bounds.outHeight/sample>4096)sample*=2;BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply{inSampleSize=sample})?:error("图片格式暂不支持")}}catch(e:CancellationException){throw e}catch(e:Exception){failure=e.message}}
    var zoom by remember(file){mutableFloatStateOf(1f)};var x by remember(file){mutableFloatStateOf(0f)};var y by remember(file){mutableFloatStateOf(0f)};var area by remember{mutableStateOf(IntSize.Zero)}
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(0.dp)).background(MaterialTheme.colorScheme.surfaceVariant).onSizeChanged{area=it},contentAlignment=Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(),"图片预览 ${(zoom*100).toInt()}%",Modifier.fillMaxSize().pointerInput(file){detectTapGestures(onDoubleTap={zoom=1f;x=0f;y=0f})}.pointerInput(file){detectTransformGestures{_,pan,scale,_->zoom=(zoom*scale).coerceIn(1f,8f);val maxX=area.width*(zoom-1)/2;val maxY=area.height*(zoom-1)/2;x=(x+pan.x).coerceIn(-maxX,maxX);y=(y+pan.y).coerceIn(-maxY,maxY)}}.graphicsLayer{scaleX=zoom;scaleY=zoom;translationX=x;translationY=y}) }
        Row(Modifier.align(Alignment.TopEnd).padding(8.dp),verticalAlignment=Alignment.CenterVertically){Surface(color=MaterialTheme.colorScheme.surface.copy(alpha=.95f),shape=RoundedCornerShape(12.dp)){Row(verticalAlignment=Alignment.CenterVertically){TextButton(onClick={zoom=(zoom/1.5f).coerceAtLeast(1f);x=0f;y=0f}){Text("−",fontSize=18.sp)};Text("${(zoom*100).toInt()}%",fontSize=11.sp);TextButton(onClick={zoom=(zoom*1.5f).coerceAtMost(8f);x=0f;y=0f}){Text("＋",fontSize=18.sp)};TextButton(onClick={zoom=1f;x=0f;y=0f}){Text("重置",fontSize=11.sp)}}}}
        failure?.let{Text(it,Modifier.padding(14.dp))}
    }
}

@Composable private fun TextFilePreview(file: java.io.File, name: String, modifier: Modifier, onLink:(String)->Unit) {
    var text by remember(file) { mutableStateOf("") }
    LaunchedEffect(file) {
        try { text=withContext(Dispatchers.IO) { file.inputStream().use { readPreviewText(it,file.length()) } } }
        catch(e:CancellationException) { throw e }
        catch(e:Exception) { text="文件读取失败：${e.message}" }
    }
    Box(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp)) {
        if (name.endsWith(".md", true)) MarkdownText(text, onLink = onLink)
        else SelectionContainer { Text(text, Modifier.horizontalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 19.sp) }
    }
}


@Composable private fun PdfPreview(file:java.io.File,modifier:Modifier) {
    var page by remember(file){mutableIntStateOf(0)};var total by remember(file){mutableIntStateOf(0)};var bitmap by remember(file){mutableStateOf<Bitmap?>(null)};var problem by remember(file){mutableStateOf<String?>(null)};var loading by remember(file){mutableStateOf(false)}
    LaunchedEffect(file,page){
        loading=true;problem=null
        try{val rendered=withContext(Dispatchers.IO){PdfRenderer(ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)).use{pdf->require(pdf.pageCount>0){"PDF 没有可显示的页面"};pdf.openPage(page).use{p->val scale=minOf(2f,2048f/maxOf(p.width,p.height));val image=Bitmap.createBitmap((p.width*scale).toInt().coerceAtLeast(1),(p.height*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888);image.eraseColor(android.graphics.Color.WHITE);p.render(image,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);pdf.pageCount to image}}};total=rendered.first;bitmap=rendered.second}catch(e:CancellationException){throw e}catch(e:Exception){problem=e.message}finally{loading=false}
    }
    Column(modifier){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically){TextButton(onClick={page--},enabled=page>0&&!loading){Text("上一页",fontSize=12.sp)};Text(if(total==0)"读取中…" else "${page+1} / $total",fontSize=12.sp);TextButton(onClick={page++},enabled=page+1<total&&!loading){Text("下一页",fontSize=12.sp)}}
        if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())
        bitmap?.let{Image(it.asImageBitmap(),"PDF 第 ${page+1} 页",Modifier.weight(1f).fillMaxWidth())}
        problem?.let{Text(it,Modifier.padding(14.dp),color=MaterialTheme.colorScheme.error,fontSize=12.sp)}
    }
}
