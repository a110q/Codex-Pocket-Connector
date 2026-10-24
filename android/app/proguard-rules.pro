# Required Android entry points and JavaScript bridge methods stay reachable.
# Other application implementation is shrunk and obfuscated in release builds.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class dev.codexpocket.app.AccountAlertWorker { public <init>(android.content.Context, androidx.work.WorkerParameters); }
-keepnames class dev.codexpocket.app.AccountAlertWorker
# WorkManager's Room database is instantiated by name and a no-arg constructor.
# Keep that constructor when optimizing the release build.
-keep class androidx.work.impl.WorkDatabase_Impl {
    public <init>();
}
