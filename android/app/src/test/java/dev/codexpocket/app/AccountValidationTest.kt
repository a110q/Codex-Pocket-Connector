package dev.codexpocket.app

import org.junit.Assert.*
import org.junit.Test

class AccountValidationTest {
    @Test fun passwordBoundariesMatchServerWithoutExtraCompositionRules(){
        assertNotNull(accountPasswordError("a".repeat(9)))
        assertNull(accountPasswordError("a".repeat(10)))
        assertNull(accountPasswordError("中".repeat(128)))
        assertNotNull(accountPasswordError("a".repeat(129)))
        assertNull(accountPasswordError("😀".repeat(5)))
    }
    @Test fun registrationExplainsMissingFieldsAndAcceptsServerAccountSyntax(){
        assertNotNull(accountRegistrationError("_bad","a".repeat(10),"称呼","申请"))
        assertNotNull(accountRegistrationError("valid","short","称呼","申请"))
        assertNotNull(accountRegistrationError("valid","a".repeat(10)," ","申请"))
        assertNotNull(accountRegistrationError("valid","a".repeat(10),"称呼"," "))
        assertNull(accountRegistrationError(" A.b-c_1 ","a".repeat(10),"称呼","申请"))
    }
}
