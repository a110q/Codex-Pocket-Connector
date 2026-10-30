package dev.codexpocket.app

import org.junit.Assert.*
import org.junit.Test

class UpdateVersionTest{
    @Test fun thirdAndFourthSegmentsCompareNumerically(){
        listOf("0.9.10" to "0.9.9.3","0.9.9.10" to "0.9.9.3","0.10.0" to "0.9.99.99","1.0.0" to "0.99.99.99").forEach{(new,old)->assertTrue(UpdateVersion.compare(new,old)>0);assertTrue(UpdateVersion.compare(old,new)<0)}
        assertEquals(0,UpdateVersion.compare("0.9.10","0.9.10.0"))
    }
    @Test fun invalidOrAmbiguousVersionsAreRejected(){
        listOf("v0.9.10","0.9","0.9.10-beta","0.09.10","1.2.3.4.5").forEach{value->assertThrows(IllegalArgumentException::class.java){UpdateVersion.parts(value)}}
    }
}
