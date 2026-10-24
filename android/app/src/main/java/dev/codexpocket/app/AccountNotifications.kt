package dev.codexpocket.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object AccountNotifications {
    private const val CHANNEL="account_alerts"
    private const val WORK="pocket-admin-alerts"
    fun schedule(context:Context) {
        val request=PeriodicWorkRequestBuilder<AccountAlertWorker>(15,TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK,ExistingPeriodicWorkPolicy.KEEP,request)
    }
    fun cancel(context:Context){WorkManager.getInstance(context).cancelUniqueWork(WORK);context.getSystemService(NotificationManager::class.java).cancelAll()}
    fun show(context:Context,events:List<JSONObject>){
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"账号申请与安全提醒",NotificationManager.IMPORTANCE_DEFAULT))
        if(Build.VERSION.SDK_INT>=33&&context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
        val preferences=context.getSharedPreferences("admin-alerts",Context.MODE_PRIVATE)
        val seen=preferences.getStringSet("seen",emptySet()).orEmpty().toMutableSet()
        for(event in events.asReversed()){
            if(event.optString("type")!="application"&&event.optString("severity")!="warning")continue
            if(!event.isNull("acknowledgedAt"))continue
            val id=event.getString("id");if(id in seen)continue
            val intent=Intent(context,MainActivity::class.java).putExtra("openAdmin",true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val content=PendingIntent.getActivity(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val title=if(event.optString("severity")=="warning")"客户端异常报告" else "新的使用申请"
            val summary=event.optString("username")+" · "+event.optString("summary")
            manager.notify(id.hashCode(),NotificationCompat.Builder(context,CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title).setContentText(summary).setStyle(NotificationCompat.BigTextStyle().bigText(summary))
                .setContentIntent(content).setAutoCancel(true).build())
            seen.add(id)
        }
        preferences.edit().putStringSet("seen",seen.toList().takeLast(1000).toSet()).apply()
    }
}

class AccountAlertWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params){
    override suspend fun doWork():Result=withContext(Dispatchers.IO){
        try{
            val saved=SecureStore(applicationContext,"pocket-account.enc").load()
            val token=saved.optString("token");if(token.isBlank())return@withContext Result.success()
            val api=AccountApi(AccountApi.defaults(applicationContext))
            val user=api.post("/api/account/me",token=token).getJSONObject("user")
            if(user.optString("role")!="admin"||user.optString("accessState")!="active")return@withContext Result.success()
            AccountNotifications.show(applicationContext,api.post("/api/admin/events",token=token).optJSONArray("events").objects())
            Result.success()
        }catch(e:AccountException){if(e.status==401||e.status==403)Result.success()else Result.retry()}
        catch(_:Exception){Result.retry()}
    }
}
