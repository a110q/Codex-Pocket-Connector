package dev.codexpocket.app

import org.junit.Assert.*
import org.junit.Test

class PatchSummaryTest{
    @Test fun countsHunksWithoutCountingHeadersOrFollowingProse(){
        val patch="diff --git a/src/a.kt b/src/a.kt\n--- a/src/a.kt\n+++ b/src/a.kt\n@@ -1,2 +1,3 @@\n same\n-old\n+new\n+added\n+this is trailing prose\n"
        assertEquals(listOf(PatchFile("src/a.kt",2,1)),patchFiles(patch))
    }
    @Test fun supportsNewDeletedAndRenamedFiles(){
        val patch="diff --git a/new.kt b/new.kt\n--- /dev/null\n+++ b/new.kt\n@@ -0,0 +1 @@\n+new\ndiff --git a/gone.kt b/gone.kt\n--- a/gone.kt\n+++ /dev/null\n@@ -1 +0,0 @@\n-old\ndiff --git a/old.kt b/renamed.kt\nsimilarity index 100%\nrename from old.kt\nrename to renamed.kt\n"
        assertEquals(listOf(PatchFile("new.kt",1,0),PatchFile("gone.kt",0,1),PatchFile("renamed.kt",0,0)),patchFiles(patch))
    }
    @Test fun usesStructuredPathForBareHunks(){
        assertEquals(listOf(PatchFile("中文目录/页面.kt",1,1)),patchFiles("@@ -1 +1 @@\n-old\n+new","中文目录/页面.kt"))
    }
    @Test fun headerLookingContentInsideHunkIsStillContent(){
        assertEquals(listOf(PatchFile("sample",1,1)),patchFiles("@@ -1 +1 @@\n--- old text\n+++ new text","sample"))
    }
    @Test fun readsPocketFileChangePathsBetweenHunks(){
        val patch="src/页面.kt\n@@ -1 +1 @@\n-old\n+new\nother/file.kt\n@@ -0,0 +1 @@\n+added\n"
        assertEquals(listOf(PatchFile("src/页面.kt",1,1),PatchFile("other/file.kt",1,0)),patchFiles(patch,fileChange=true))
    }
    @Test fun deletingAFileInsideDirectoryNamedBKeepsItsPath(){
        assertEquals(listOf(PatchFile("b/old.kt",0,1)),patchFiles("--- a/b/old.kt\n+++ /dev/null\n@@ -1 +0,0 @@\n-old"))
    }
    @Test fun arbitraryTextDoesNotInventFilesOrCounts(){assertTrue(patchFiles("Changed two files\n+5 -2").isEmpty())}
}
