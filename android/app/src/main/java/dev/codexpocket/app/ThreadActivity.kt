package dev.codexpocket.app

import org.json.JSONArray
import org.json.JSONObject

internal enum class ThreadActivity(val label: String, val wireStatus: String) {
    RUNNING("运行中", "active"),
    APPROVAL("等待审批", "waitingForApproval"),
    INPUT("等待输入", "waitingForUserInput"),
    WAITING("等待操作", "waiting"),
    ERROR("出错", "systemError"),
    IDLE("可继续", "idle"),
    UNKNOWN("状态未知", "unknown");

    val waiting get() = this == APPROVAL || this == INPUT || this == WAITING
    val inFlight get() = this == RUNNING || waiting
    val actionable get() = inFlight || this == ERROR
}

internal fun activityForStatus(status: String): ThreadActivity = when (status) {
    "running", "inProgress", "active", "working" -> ThreadActivity.RUNNING
    "waitingForApproval", "awaitingApproval" -> ThreadActivity.APPROVAL
    "waitingForUserInput", "awaitingUserInput", "waitingForInput" -> ThreadActivity.INPUT
    "needs_attention", "waiting" -> ThreadActivity.WAITING
    "error", "failed", "systemError" -> ThreadActivity.ERROR
    "idle", "completed", "interrupted", "stopped", "cancelled" -> ThreadActivity.IDLE
    else -> ThreadActivity.UNKNOWN
}

// Runtime flags and open requests can distinguish approval/input from an active
// turn. An explicit idle status wins over stale turn-history inProgress flags.
internal fun threadActivity(thread: JSONObject, requests: JSONArray? = null): ThreadActivity {
    val pending = requests.objects().filter { !it.optBoolean("completed") && it.optString("status") !in listOf("completed", "cancelled", "resolved") }
    if (pending.any { it.optString("method").contains("requestApproval", true) }) return ThreadActivity.APPROVAL
    if (pending.isNotEmpty()) return ThreadActivity.INPUT
    val raw = thread.optJSONObject("status") ?: thread.optJSONObject("threadRuntimeStatus")
    val activity = activityForStatus(raw?.optString("type") ?: thread.optString("status"))
    val flags = (raw?.optJSONArray("activeFlags") ?: thread.optJSONArray("activeFlags"))?.let { values ->
        (0 until values.length()).map { values.optJSONObject(it)?.optString("type") ?: values.optString(it) }
    }.orEmpty()
    if (activity == ThreadActivity.RUNNING || activity == ThreadActivity.UNKNOWN) {
        flags.map(::activityForStatus).firstOrNull { it.waiting }?.let { return it }
    }
    if (activity != ThreadActivity.UNKNOWN) return activity
    if (thread.optString("activeTurnId").let { it.isNotBlank() && it != "null" }) return ThreadActivity.RUNNING
    return activityForStatus(thread.optString("lastTurnStatus"))
}

internal fun threadIdentity(thread: JSONObject) = thread.optString("hostId", "local") + ":" + thread.optString("id")
