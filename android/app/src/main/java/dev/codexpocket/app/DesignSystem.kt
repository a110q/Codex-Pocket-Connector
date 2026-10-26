package dev.codexpocket.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val PocketGreen = Color(0xFF238367)

@Composable fun pocketSuccess():Color = if(MaterialTheme.colorScheme.background.luminance()>.5f)PocketGreen else Color(0xFF7BD5B1)

@Composable fun PocketTheme(appearance:String="system",content:@Composable ()->Unit) {
    val dark=when(appearance){"dark"->true;"light"->false;else->isSystemInDarkTheme()}
    val view=LocalView.current
    SideEffect{(view.context as? android.app.Activity)?.window?.let{window->
        androidx.core.view.WindowCompat.getInsetsController(window,view).apply{isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark}
    }}
    val colors=if(dark)darkColorScheme(
        primary=Color(0xFF9BB8FF),onPrimary=Color(0xFF102651),primaryContainer=Color(0xFF263958),onPrimaryContainer=Color(0xFFDCE6FF),
        secondary=Color(0xFF9BB8FF),onSecondary=Color(0xFF102651),secondaryContainer=Color(0xFF243650),onSecondaryContainer=Color(0xFFDCE6FF),
        tertiary=Color(0xFFE7B868),tertiaryContainer=Color(0xFF372C1C),onTertiaryContainer=Color(0xFFF3D49E),
        background=Color(0xFF111923),onBackground=Color(0xFFE8EEF8),surface=Color(0xFF18222F),onSurface=Color(0xFFE8EEF8),
        surfaceVariant=Color(0xFF253243),onSurfaceVariant=Color(0xFFACB9CB),outline=Color(0xFF8394AD),outlineVariant=Color(0xFF344257),
        surfaceContainerLowest=Color(0xFF111923),surfaceContainerLow=Color(0xFF18222F),surfaceContainer=Color(0xFF1D2A3A),surfaceContainerHigh=Color(0xFF253243),surfaceContainerHighest=Color(0xFF344257),
        error=Color(0xFFFFB4AC),errorContainer=Color(0xFF402522),onErrorContainer=Color(0xFFFFDAD4),surfaceTint=Color.Transparent
    )else lightColorScheme(
        primary=Color(0xFF3266E3),onPrimary=Color.White,primaryContainer=Color(0xFFEDF2FF),onPrimaryContainer=Color(0xFF2855BB),
        secondary=Color(0xFF3266E3),onSecondary=Color.White,secondaryContainer=Color(0xFFEEF3FC),onSecondaryContainer=Color(0xFF3B5F9E),
        tertiary=Color(0xFF9A6416),tertiaryContainer=Color(0xFFFFF6E6),onTertiaryContainer=Color(0xFF765015),
        background=Color(0xFFF4F6FA),onBackground=Color(0xFF1C273B),surface=Color.White,onSurface=Color(0xFF1C273B),
        surfaceVariant=Color(0xFFEEF2F8),onSurfaceVariant=Color(0xFF67758A),outline=Color(0xFF7F8CA1),outlineVariant=Color(0xFFE1E7F0),
        surfaceContainerLowest=Color.White,surfaceContainerLow=Color(0xFFF4F6FA),surfaceContainer=Color(0xFFF0F3F8),surfaceContainerHigh=Color(0xFFEAF0F8),surfaceContainerHighest=Color(0xFFE1E7F0),
        error=Color(0xFFBD423A),errorContainer=Color(0xFFFFEFEC),onErrorContainer=Color(0xFF8F2B26),surfaceTint=Color.Transparent
    )
    MaterialTheme(colorScheme=colors,shapes=Shapes(small=RoundedCornerShape(12.dp),medium=RoundedCornerShape(16.dp),large=RoundedCornerShape(24.dp)),typography=Typography(
        bodyLarge=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=15.sp,lineHeight=24.sp),
        bodyMedium=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=14.sp,lineHeight=22.sp),
        bodySmall=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=12.sp,lineHeight=18.sp),
        titleLarge=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=22.sp,lineHeight=30.sp,fontWeight=FontWeight.SemiBold),
        titleMedium=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=16.sp,lineHeight=24.sp,fontWeight=FontWeight.SemiBold),
        labelLarge=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=14.sp,fontWeight=FontWeight.Medium)
    ),content=content)
}

@Composable fun PocketIcon(kind:String,modifier:Modifier=Modifier) {
    val color=LocalContentColor.current
    Canvas(modifier.size(22.dp)) {
        val s=size.minDimension;val stroke=Stroke(s*.062f,cap=StrokeCap.Round)
        fun line(x:Float,y:Float,x2:Float,y2:Float)=drawLine(color,Offset(x*s,y*s),Offset(x2*s,y2*s),s*.062f,cap=StrokeCap.Round)
        fun rect(x:Float,y:Float,w:Float,h:Float,r:Float=.08f)=drawRoundRect(color,Offset(s*x,s*y),Size(s*w,s*h),CornerRadius(s*r),style=stroke)
        when(kind){
            "项目","工作台","文件夹"->{val p=Path().apply{moveTo(.1f*s,.26f*s);quadraticTo(.1f*s,.19f*s,.18f*s,.19f*s);lineTo(.4f*s,.19f*s);lineTo(.51f*s,.32f*s);lineTo(.82f*s,.32f*s);quadraticTo(.9f*s,.32f*s,.9f*s,.4f*s);lineTo(.9f*s,.78f*s);quadraticTo(.9f*s,.85f*s,.82f*s,.85f*s);lineTo(.18f*s,.85f*s);quadraticTo(.1f*s,.85f*s,.1f*s,.78f*s);close()};drawPath(p,color,style=stroke)}
            "终端"->{rect(.1f,.14f,.8f,.72f);line(.25f,.33f,.4f,.48f);line(.4f,.48f,.25f,.63f);line(.51f,.64f,.72f,.64f)}
            "会话"->{rect(.13f,.16f,.74f,.58f,.15f);line(.3f,.74f,.23f,.87f);line(.23f,.87f,.48f,.74f);for(x in listOf(.33f,.5f,.67f))drawCircle(color,s*.028f,Offset(s*x,s*.45f))}
            "电脑"->{rect(.1f,.13f,.8f,.58f);line(.5f,.72f,.5f,.88f);line(.3f,.88f,.7f,.88f)}
            "搜索"->{drawCircle(color,s*.28f,Offset(s*.43f,s*.43f),style=stroke);line(.65f,.65f,.87f,.87f)}
            "新增"->{line(.5f,.2f,.5f,.8f);line(.2f,.5f,.8f,.5f)}
            "返回"->{line(.63f,.2f,.33f,.5f);line(.33f,.5f,.63f,.8f)}
            "展开"->{line(.36f,.25f,.61f,.5f);line(.61f,.5f,.36f,.75f)}
            "下拉"->{line(.25f,.37f,.5f,.62f);line(.5f,.62f,.75f,.37f)}
            "上拉"->{line(.25f,.62f,.5f,.37f);line(.5f,.37f,.75f,.62f)}
            "关闭"->{line(.25f,.25f,.75f,.75f);line(.75f,.25f,.25f,.75f)}
            "更多"->{for(x in listOf(.2f,.5f,.8f))drawCircle(color,s*.06f,Offset(s*x,s*.5f))}
            "发送"->{val p=Path().apply{moveTo(.24f*s,.15f*s);lineTo(.88f*s,.5f*s);lineTo(.24f*s,.85f*s);lineTo(.36f*s,.5f*s);close()};drawPath(p,color,style=stroke);line(.39f,.5f,.82f,.5f)}
            "前往"->{line(.16f,.5f,.84f,.5f);line(.56f,.23f,.84f,.5f);line(.84f,.5f,.56f,.77f)}
            "停止"->drawRoundRect(color,Offset(s*.25f,s*.25f),Size(s*.5f,s*.5f),CornerRadius(s*.06f))
            "检查"->{line(.2f,.52f,.4f,.72f);line(.4f,.72f,.8f,.28f)}
            "网页"->{drawCircle(color,s*.38f,style=stroke);drawOval(color,Offset(.33f*s,.12f*s),Size(.34f*s,.76f*s),style=stroke);line(.13f,.5f,.87f,.5f)}
            "局域网"->{drawArc(color,215f,110f,false,Offset(s*.06f,s*.16f),Size(s*.88f,s*.88f),style=stroke);drawArc(color,215f,110f,false,Offset(s*.23f,s*.36f),Size(s*.54f,s*.54f),style=stroke);drawArc(color,215f,110f,false,Offset(s*.4f,s*.56f),Size(s*.2f,s*.2f),style=stroke);drawCircle(color,s*.04f,Offset(s*.5f,s*.86f))}
            "中继"->{rect(.15f,.12f,.7f,.31f,.06f);rect(.15f,.57f,.7f,.31f,.06f);drawCircle(color,s*.025f,Offset(s*.3f,s*.275f));drawCircle(color,s*.025f,Offset(s*.3f,s*.725f));line(.6f,.275f,.71f,.275f);line(.6f,.725f,.71f,.725f)}
            "扫码"->{line(.13f,.35f,.13f,.13f);line(.13f,.13f,.35f,.13f);line(.65f,.13f,.87f,.13f);line(.87f,.13f,.87f,.35f);line(.87f,.65f,.87f,.87f);line(.87f,.87f,.65f,.87f);line(.35f,.87f,.13f,.87f);line(.13f,.87f,.13f,.65f);rect(.3f,.3f,.13f,.13f,.01f);rect(.57f,.3f,.13f,.13f,.01f);rect(.3f,.57f,.13f,.13f,.01f);line(.57f,.57f,.7f,.57f);line(.7f,.57f,.7f,.7f)}
            "文件"->{val p=Path().apply{moveTo(.23f*s,.12f*s);lineTo(.59f*s,.12f*s);lineTo(.8f*s,.33f*s);lineTo(.8f*s,.88f*s);lineTo(.23f*s,.88f*s);close()};drawPath(p,color,style=stroke);line(.58f,.13f,.58f,.34f);line(.58f,.34f,.79f,.34f)}
            "编辑"->{val p=Path().apply{moveTo(.16f*s,.65f*s);lineTo(.68f*s,.14f*s);lineTo(.85f*s,.31f*s);lineTo(.34f*s,.82f*s);lineTo(.13f*s,.87f*s);close()};drawPath(p,color,style=stroke);line(.57f,.25f,.74f,.42f)}
            "图片"->{rect(.1f,.17f,.8f,.66f);drawCircle(color,s*.06f,Offset(s*.32f,s*.36f));line(.13f,.76f,.41f,.51f);line(.41f,.51f,.57f,.65f);line(.57f,.65f,.72f,.46f);line(.72f,.46f,.87f,.67f)}
            "复制"->{rect(.31f,.28f,.52f,.58f,.05f);line(.2f,.65f,.16f,.65f);line(.16f,.65f,.16f,.13f);line(.16f,.13f,.64f,.13f)}
            "附件"->{val p=Path().apply{moveTo(.66f*s,.32f*s);lineTo(.38f*s,.60f*s);cubicTo(.23f*s,.75f*s,.39f*s,.91f*s,.54f*s,.76f*s);lineTo(.81f*s,.49f*s);cubicTo(.98f*s,.32f*s,.74f*s,.08f*s,.57f*s,.25f*s);lineTo(.22f*s,.56f*s);cubicTo(.02f*s,.76f*s,.37f*s,.98f*s,.57f*s,.78f*s);lineTo(.83f*s,.52f*s)};drawPath(p,color,style=stroke)}
            "刷新"->{drawArc(color,35f,285f,false,Offset(s*.16f,s*.16f),Size(s*.68f,s*.68f),style=stroke);line(.86f,.2f,.86f,.43f);line(.86f,.43f,.64f,.43f)}
            "全屏"->{line(.16f,.39f,.16f,.16f);line(.16f,.16f,.39f,.16f);line(.61f,.84f,.84f,.84f);line(.84f,.84f,.84f,.61f);line(.61f,.16f,.84f,.16f);line(.84f,.16f,.84f,.39f);line(.16f,.61f,.16f,.84f);line(.16f,.84f,.39f,.84f)}
            "查看","只读"->{drawOval(color,Offset(s*.08f,s*.27f),Size(s*.84f,s*.46f),style=stroke);drawCircle(color,s*.12f,style=stroke)}
            "锁"->{rect(.23f,.42f,.54f,.45f);drawArc(color,180f,180f,false,Offset(s*.33f,s*.13f),Size(s*.34f,s*.58f),style=stroke)}
            "推理","任务"->{drawArc(color,140f,260f,false,Offset(s*.25f,s*.12f),Size(s*.5f,s*.56f),style=stroke);line(.34f,.61f,.38f,.77f);line(.66f,.61f,.62f,.77f);line(.38f,.77f,.62f,.77f);line(.42f,.88f,.58f,.88f)}
            "差异"->{line(.3f,.15f,.3f,.84f);line(.7f,.15f,.7f,.84f);line(.14f,.36f,.46f,.36f);line(.54f,.64f,.86f,.64f);line(.7f,.48f,.7f,.8f)}
            "时钟"->{drawCircle(color,s*.37f,style=stroke);line(.5f,.24f,.5f,.5f);line(.5f,.5f,.67f,.62f)}
            "用量"->{line(.17f,.87f,.17f,.62f);line(.39f,.87f,.39f,.42f);line(.61f,.87f,.61f,.22f);line(.83f,.87f,.83f,.12f)}
            "权限"->{val p=Path().apply{moveTo(.5f*s,.1f*s);lineTo(.82f*s,.25f*s);lineTo(.77f*s,.62f*s);lineTo(.5f*s,.89f*s);lineTo(.23f*s,.62f*s);lineTo(.18f*s,.25f*s);close()};drawPath(p,color,style=stroke);line(.36f,.48f,.46f,.59f);line(.46f,.59f,.66f,.37f)}
            "工具"->{rect(.15f,.15f,.27f,.27f,.04f);rect(.58f,.15f,.27f,.27f,.04f);rect(.15f,.58f,.27f,.27f,.04f);rect(.58f,.58f,.27f,.27f,.04f)}
            else->{drawCircle(color,s*.26f,style=stroke);drawCircle(color,s*.08f,style=stroke);for(i in 0..7){val a=i*Math.PI/4;drawLine(color,Offset((s*.5+s*.31*kotlin.math.cos(a)).toFloat(),(s*.5+s*.31*kotlin.math.sin(a)).toFloat()),Offset((s*.5+s*.42*kotlin.math.cos(a)).toFloat(),(s*.5+s*.42*kotlin.math.sin(a)).toFloat()),s*.06f,cap=StrokeCap.Round)}}
        }
    }
}

@Composable fun PocketAction(kind:String,label:String=kind,enabled:Boolean=true,onClick:()->Unit){
    IconButton(onClick=onClick,enabled=enabled,modifier=Modifier.size(48.dp).semantics{contentDescription=label}){PocketIcon(kind)}
}

@Composable fun CodexMark(modifier:Modifier=Modifier){
    Image(painter=painterResource(R.drawable.codex_mark),contentDescription="Codex Pocket",modifier=modifier.size(28.dp))
}


@Composable internal fun PocketIconTile(kind:String,modifier:Modifier=Modifier,tint:Color=MaterialTheme.colorScheme.primary,filled:Boolean=false){
    Surface(modifier.size(42.dp),shape=RoundedCornerShape(13.dp),color=if(filled)tint else tint.copy(alpha=.09f),contentColor=if(filled)MaterialTheme.colorScheme.onPrimary else tint){
        Box(contentAlignment=Alignment.Center){PocketIcon(kind,Modifier.size(22.dp))}
    }
}
