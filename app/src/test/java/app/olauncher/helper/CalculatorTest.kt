package app.olauncher.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalculatorTest {
    @Test
    fun evaluatesArithmetic() {
        assertEquals("21.75", Calculator.evaluate("(12*7+3)/4"))
        assertEquals("14", Calculator.evaluate("2 + 3 x 4"))
        assertEquals("1024", Calculator.evaluate("2^10"))
        assertEquals("2.5", Calculator.evaluate("1,5+1"))
        assertEquals("15", Calculator.evaluate("150*10%"))
        assertEquals("0.333333333333", Calculator.evaluate("1/3"))
    }

    @Test
    fun ignoresNonMath() {
        assertNull(Calculator.evaluate("youtube"))
        assertNull(Calculator.evaluate("2024"))
        assertNull(Calculator.evaluate("1/0"))
        assertNull(Calculator.evaluate("(1+"))
        assertNull(Calculator.evaluate("--"))
    }
}
