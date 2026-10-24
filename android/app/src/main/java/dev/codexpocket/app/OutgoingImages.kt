package dev.codexpocket.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class OutgoingImage(val id:String,val path:String,val mime:String="image/jpeg") {
    fun json()=JSONObject().put("id",id).put("path",path).put("mime",mime)
    companion object {
        fun restore(json:JSONObject)=OutgoingImage(json.getString("id"),json.getString("path"),json.getString("mime"))
        fun import(context:Context,uri:Uri):OutgoingImage {
            // System picker grants access only to the selected image. Decode at
            // bounded resolution, apply EXIF orientation, and omit its metadata.
            context.contentResolver.openAssetFileDescriptor(uri,"r")?.use { require(it.length <= 50L*1024*1024){"原图超过 50 MB，请选择较小的图片"} }
            val bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver,uri)){decoder,info,_->
                val scale=minOf(1.0,2048.0/maxOf(info.size.width,info.size.height))
                decoder.setTargetSize(maxOf(1,(info.size.width*scale).toInt()),maxOf(1,(info.size.height*scale).toInt()))
                decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
            }
            val id=UUID.randomUUID().toString();val dir=File(context.filesDir,"outgoing-images").apply{mkdirs()};val file=File(dir,"$id.jpg")
            try { file.outputStream().use{check(bitmap.compress(Bitmap.CompressFormat.JPEG,90,it))};require(file.length() in 12..10L*1024*1024){"处理后的图片超过 10 MB"};return OutgoingImage(id,file.path) }
            catch(e:Exception){file.delete();throw e}finally{bitmap.recycle()}
        }
    }
}

val LocalOutgoingImagePicker=staticCompositionLocalOf<((PocketModel,String)->Unit)?>{null}

@Composable fun rememberImagePicker(model:PocketModel,key:String):()->Unit {
    val activityPicker=LocalOutgoingImagePicker.current
    if(activityPicker!=null)return {activityPicker(model,key)}
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()){uris->model.addImages(uris,key)}
    return {launcher.launch("image/*")}
}

@Composable fun OutgoingImageStrip(model:PocketModel,key:String) {
    val images=model.imagesFor(key)
    if(images.isEmpty())return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical=7.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        images.forEachIndexed{index,image->
            var bitmap by remember(image.path){mutableStateOf<Bitmap?>(null)}
            LaunchedEffect(image.path){bitmap=withContext(Dispatchers.IO){BitmapFactory.decodeFile(image.path,BitmapFactory.Options().apply{inSampleSize=4})}}
            Surface(shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surfaceVariant){
                Box(Modifier.size(84.dp)){
                    bitmap?.let{Image(it.asImageBitmap(),"待发送图片 ${index+1}",Modifier.fillMaxSize(),contentScale=ContentScale.Crop)}
                    IconButton(onClick={model.removeImage(key,image.id)},enabled=!model.busy,modifier=Modifier.align(Alignment.TopEnd).size(32.dp).semantics{contentDescription="移除图片 ${index+1}"}){
                        Surface(color=MaterialTheme.colorScheme.surface.copy(alpha=.9f),shape=RoundedCornerShape(20.dp)){Text("×",Modifier.padding(horizontal=6.dp),fontSize=20.sp)}
                    }
                }
            }
        }
    }
}
