package dev.codexpocket.app

enum class ConnectionState(val label: String, val ready: Boolean = false, val retry: Boolean = false) {
    CONNECTING("正在连接"),
    RELAY_CONNECTED("已连接中继"),
    CONNECTING_DESKTOP("正在确认电脑连接"),
    COMPUTER_OFFLINE("电脑离线，等待自动恢复"),
    READY("电脑已连接", ready=true),
    DESKTOP_UNAVAILABLE("电脑在线，正在连接 Codex"),
    DESKTOP_DISCONNECTED("Codex 连接中断，正在自动恢复"),
    INCOMPATIBLE("电脑接口已变化，请更新连接服务"),
    RECONNECTING("连接中断，正在重连", retry=true),
    CLOSED("连接已断开，正在重连", retry=true),
    HANDSHAKE_TIMEOUT("连接确认超时，正在重连", retry=true),
    INVALID_DATA("连接数据验证失败，正在重连", retry=true),
    AUTH_FAILED("设备授权已失效"),
    ACCESS_DENIED("账号或设备权限已失效，请检查登录状态"),
    REPLACED("连接已由另一处接管，可手动重新连接"),
    REVOKED("设备已被撤销，请重新配对")
}
