package dev.codexpocket.app

import org.junit.Assert.*
import org.junit.Test

class DraftBookTest {
    @Test fun draftsSurviveSwitchingChatsAndReloading() {
        val drafts = DraftBook().withDraft("local", "a", "尚未发送\n保留格式")
            .withDraft("local", "b", "other message")
        val reloaded = DraftBook(drafts.entries.toMap())
        assertEquals("尚未发送\n保留格式", reloaded.text("local", "a"))
        assertEquals("other message", reloaded.text("local", "b"))
        assertEquals("", reloaded.text("local", "new"))
    }
    @Test fun clearingOneDraftPreservesOtherChatsAndHosts() {
        val original = DraftBook().withDraft("local", "same", "one")
            .withDraft("remote", "same", "two")
        val sent = original.withDraft("local", "same", "")
        assertEquals("", sent.text("local", "same"))
        assertEquals("two", sent.text("remote", "same"))
        assertEquals("one", original.text("local", "same"))
        assertEquals(1, sent.entries.size)
    }
    @Test fun compositeKeysCannotAliasEachOther() {
        val drafts = DraftBook().withDraft("a:b", "c", "first").withDraft("a", "b:c", "second")
        assertEquals("first", drafts.text("a:b", "c"))
        assertEquals("second", drafts.text("a", "b:c"))
    }
}
