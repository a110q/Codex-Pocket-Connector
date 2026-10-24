package dev.codexpocket.app

/** Drafts follow their chat and host, including after reconnecting or restarting. */
data class DraftBook(val entries: Map<String, String> = emptyMap()) {
    fun text(host: String, thread: String): String = entries[key(host, thread)].orEmpty()
    fun withDraft(host: String, thread: String, text: String): DraftBook {
        val key = key(host, thread)
        return DraftBook(if (text.isEmpty()) entries - key else entries + (key to text))
    }
    private fun key(host: String, thread: String) = "${host.length}:$host:$thread"
}
