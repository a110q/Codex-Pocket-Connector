package dev.codexpocket.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import org.json.JSONObject

@Composable
internal fun DeleteThreadDialog(model: PocketModel, thread: JSONObject, blocked: String?, onDismiss: () -> Unit) {
    val computer = model.profile?.computerName() ?: "电脑"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除对话？") },
        text = {
            Text("将从 $computer 删除“${thread.label()}”及其对话记录，无法恢复。项目文件不会删除。" +
                (blocked?.let { "\n\n$it" } ?: ""))
        },
        confirmButton = {
            TextButton(enabled = blocked == null, onClick = {
                onDismiss()
                model.manageThread(thread, "delete")
            }) { Text("确认删除", color = if (blocked == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
