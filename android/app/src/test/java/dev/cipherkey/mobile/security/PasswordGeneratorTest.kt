package dev.cipherkey.mobile.security

import org.junit.Assert.*
import org.junit.Test

class PasswordGeneratorTest {
    @Test fun respectsLengthAndClasses() {
        repeat(200) {
            val p = PasswordGenerator.generate(PasswordGenerator.Options(length = 16))
            assertEquals(16, p.length)
            assertTrue(p.any { it.isUpperCase() }); assertTrue(p.any { it.isLowerCase() })
            assertTrue(p.any { it.isDigit() }); assertTrue(p.any { !it.isLetterOrDigit() })
        }
    }
    @Test fun excludesAmbiguous() {
        repeat(200) {
            val p = PasswordGenerator.generate(PasswordGenerator.Options(length = 32, excludeAmbiguous = true))
            assertTrue(p.none { it in "O0oIl1|`'\"B8S5Z2" })
        }
    }
    @Test fun strengthMonotonic() {
        assertTrue(PasswordGenerator.entropyBits("abc") < PasswordGenerator.entropyBits("aZ3!kQ9#xLp2&wRt"))
    }
}
