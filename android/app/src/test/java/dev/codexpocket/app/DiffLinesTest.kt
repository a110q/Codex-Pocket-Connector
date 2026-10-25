package dev.codexpocket.app
import org.junit.Assert.*
import org.junit.Test
class DiffLinesTest{
 @Test fun fileLineNumbersFollowEachHunkAndDoNotCountNoNewlineMarkers(){val lines=unifiedDiffLines("--- a/file\n+++ b/file\n@@ -5,2 +8,2 @@\n context\n-old\n+new\n\\ No newline at end of file\n@@ -12 +20 @@\n-last\n+replacement");assertNull(lines[0].oldLine);assertEquals(5,lines[3].oldLine);assertEquals(8,lines[3].newLine);assertEquals(6,lines[4].oldLine);assertNull(lines[4].newLine);assertEquals(9,lines[5].newLine);assertNull(lines[6].newLine);assertEquals(12,lines[8].oldLine);assertEquals(20,lines[9].newLine)}
}
