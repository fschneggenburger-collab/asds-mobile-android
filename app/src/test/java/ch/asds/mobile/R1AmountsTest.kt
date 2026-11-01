package ch.asds.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class R1AmountsTest {
    @Test fun chfRoundingAndCommaDecimals() {
        assertEquals("2.35", R1Amounts.kmAmount("3,35", "0.70"))
        assertEquals("0.00", R1Amounts.kmAmount("0", "0.70"))
        assertEquals("1.01", R1Amounts.kmAmount("1.005", "1"))
    }

    @Test fun rejectsNegativeValues() {
        assertThrows(IllegalArgumentException::class.java) {
            R1Amounts.kmAmount("-1", "0.70")
        }
    }
}
