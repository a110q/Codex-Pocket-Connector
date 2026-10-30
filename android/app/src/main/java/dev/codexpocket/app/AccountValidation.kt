package dev.codexpocket.app

fun accountPasswordError(password:String):String?=when {
    password.length<10 -> "密码至少需要 10 个字符，当前 ${password.length} 个"
    password.length>128 -> "密码最多支持 128 个字符，当前 ${password.length} 个"
    else -> null
}

fun accountRegistrationError(username:String,password:String,displayName:String,reason:String):String?=when {
    !Regex("[A-Za-z0-9][A-Za-z0-9_.-]{2,31}").matches(username.trim()) -> "账号需要 3–32 位英文字母、数字、点、短横线或下划线，并以字母或数字开头"
    accountPasswordError(password)!=null -> accountPasswordError(password)
    displayName.isBlank() -> "请填写你的称呼"
    reason.isBlank() -> "请填写申请说明，方便管理员审批"
    else -> null
}
