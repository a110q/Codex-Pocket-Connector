package dev.codexpocket.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable fun ConnectionChooser(onCloud:()->Unit,onLan:()->Unit,onSelfHost:()->Unit,onBack:(()->Unit)?=null){
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal=22.dp,vertical=20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
            CodexMark(Modifier.size(38.dp))
            Text("Codex Pocket",Modifier.weight(1f),fontSize=20.sp,fontWeight=FontWeight.Bold)
            onBack?.let{PocketAction("关闭","返回工作空间",onClick=it)}
        }
        Column(Modifier.padding(top=8.dp,bottom=4.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("让 Codex，随身而行。",fontSize=28.sp,lineHeight=38.sp,fontWeight=FontWeight.Bold)
            Text("选择连接方式，在手机上继续你的项目与对话。",fontSize=14.sp,lineHeight=22.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
            ConnectionModeCard("公网使用","手机流量或其他 Wi-Fi，也能继续工作。","登录并连接","网页",onClick=onCloud)
            ConnectionModeCard("局域网使用","连接同一 Wi-Fi，扫码直连，无需账号审批。","开始连接","局域网",onClick=onLan)
            ConnectionModeCard("自建中继","使用自己的服务器，电脑配置，手机扫码加入。","配置服务","中继",highlight=true,onClick=onSelfHost)
        }
        Text("电脑需保持开机，Codex 与电脑连接器持续运行。",fontSize=12.sp,lineHeight=19.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Codex Pocket  ${BuildConfig.VERSION_NAME}",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun ConnectionModeCard(title:String,description:String,action:String,icon:String,highlight:Boolean=false,onClick:()->Unit){
    val colors=MaterialTheme.colorScheme
    Surface(onClick=onClick,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp),color=if(highlight)colors.primaryContainer else colors.surface,border=BorderStroke(1.dp,if(highlight)colors.primary.copy(alpha=.26f)else colors.outlineVariant)){
        Row(Modifier.fillMaxWidth().heightIn(min=124.dp).padding(18.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.Top){
            Surface(color=if(highlight)colors.surface else colors.primaryContainer,shape=RoundedCornerShape(13.dp),modifier=Modifier.size(44.dp)){
                Box(contentAlignment=Alignment.Center){CompositionLocalProvider(LocalContentColor provides colors.primary){PocketIcon(icon,Modifier.size(25.dp))}}
            }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)){
                Text(title,fontSize=18.sp,lineHeight=25.sp,fontWeight=FontWeight.SemiBold)
                Text(description,fontSize=13.sp,lineHeight=20.sp,color=colors.onSurfaceVariant)
                Row(Modifier.padding(top=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    Text(action,fontSize=14.sp,fontWeight=FontWeight.SemiBold,color=colors.primary)
                    CompositionLocalProvider(LocalContentColor provides colors.primary){PocketIcon("前往",Modifier.size(17.dp))}
                }
            }
        }
    }
}

@Composable fun ConnectionSetupHeader(title:String,onBack:()->Unit){
    Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(start=8.dp,end=20.dp),verticalAlignment=Alignment.CenterVertically){
        PocketAction("返回","返回选择连接方式",onClick=onBack)
        Text(title,fontSize=18.sp,fontWeight=FontWeight.SemiBold)
    }
}

@Composable fun ConnectionGuide(local:Boolean,onBack:()->Unit,onScan:()->Unit,currentService:String?=null,onLoginCurrent:(()->Unit)?=null){
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()){
        ConnectionSetupHeader(if(local)"局域网使用"else"自建中继",onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)){
            Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=RoundedCornerShape(18.dp),modifier=Modifier.size(64.dp)){
                Box(contentAlignment=Alignment.Center){CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary){PocketIcon(if(local)"局域网"else"中继",Modifier.size(32.dp))}}
            }
            Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
                Text(if(local)"同一网络，即刻连接。"else"你的服务器，随身连接。",fontSize=27.sp,lineHeight=38.sp,fontWeight=FontWeight.Bold)
                Text(if(local)"无需登录或账号审批，扫码后由电脑确认。"else"先在电脑配置服务，手机扫码自动获取地址和证书。",fontSize=14.sp,lineHeight=23.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val steps=if(local)listOf(
                "连接同一个 Wi-Fi" to "让手机和电脑接入同一局域网。",
                "在电脑选择「局域网使用」" to "连接 Codex 后，打开「配对手机」二维码。",
                "手机扫码，电脑确认" to "在电脑允许连接，即可继续项目与对话。"
            )else listOf(
                "在电脑选择「自建中继」" to "点击「配置服务」，填写中继地址并导入公开证书。",
                "连接 Codex，显示配对码" to "服务验证通过后，在电脑进入「配对手机」。",
                "手机扫码，登录该服务" to "确认地址与证书指纹，使用该中继的账号完成配对。"
            )
            steps.forEachIndexed{index,(title,description)->
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
                    Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=RoundedCornerShape(9.dp),modifier=Modifier.size(30.dp)){
                        Box(contentAlignment=Alignment.Center){Text("${index+1}",fontSize=14.sp,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.primary)}
                    }
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){
                        Text(title,fontSize=15.sp,lineHeight=22.sp,fontWeight=FontWeight.Medium)
                        Text(description,fontSize=13.sp,lineHeight=21.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if(!local&&currentService!=null)Surface(color=MaterialTheme.colorScheme.surface,shape=RoundedCornerShape(12.dp),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){
                Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                    Text("已配置的自建服务",fontSize=13.sp,fontWeight=FontWeight.Medium)
                    Text(currentService,fontSize=13.sp,lineHeight=21.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Column(Modifier.padding(start=24.dp,end=24.dp,bottom=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Button(onClick=onScan,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),shape=RoundedCornerShape(13.dp)){
                PocketIcon("扫码",Modifier.size(22.dp));Spacer(Modifier.width(10.dp));Text(if(local)"扫码连接电脑"else"扫码配置服务",fontSize=16.sp,fontWeight=FontWeight.SemiBold)
            }
            if(!local&&currentService!=null&&onLoginCurrent!=null)OutlinedButton(onClick=onLoginCurrent,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),shape=RoundedCornerShape(13.dp)){Text("使用已配置服务")}
        }
    }
}
