package app.olauncher.helper

import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.pow

/** Tiny arithmetic evaluator for drawer search: + - * / ^ %, parentheses, "x" and "," as decimal point. */
object Calculator {
    private val looksLikeMath = Regex("""^[\d\s.,+\-*/x×÷^%()]+$""")

    fun evaluate(query: String): String? {
        val input = query.trim()
        if (input.length < 3 || !looksLikeMath.matches(input) || input.none { it in "+-*/x×÷^%" } || input.none(Char::isDigit)) return null
        return runCatching {
            val parser = Parser(input.replace(',', '.').replace('x', '*').replace('×', '*').replace('÷', '/').replace(" ", ""))
            val value = parser.parse()
            if (value.isNaN() || value.isInfinite()) null else format(value)
        }.getOrNull()
    }

    private fun format(value: Double): String {
        val rounded = BigDecimal(value).round(MathContext(12)).stripTrailingZeros()
        return if (rounded.scale() <= 0) rounded.toBigInteger().toString() else rounded.toPlainString()
    }

    private class Parser(private val text: String) {
        private var pos = 0

        fun parse(): Double {
            val value = expression()
            require(pos == text.length)
            return value
        }

        private fun expression(): Double {
            var value = term()
            while (pos < text.length) {
                value = when (text[pos]) {
                    '+' -> { pos++; value + term() }
                    '-' -> { pos++; value - term() }
                    else -> return value
                }
            }
            return value
        }

        private fun term(): Double {
            var value = power()
            while (pos < text.length) {
                value = when (text[pos]) {
                    '*' -> { pos++; value * power() }
                    '/' -> { pos++; value / power() }
                    '%' -> { pos++; if (pos < text.length && (text[pos].isDigit() || text[pos] == '(')) value % power() else value / 100 }
                    else -> return value
                }
            }
            return value
        }

        private fun power(): Double {
            val base = unary()
            if (pos < text.length && text[pos] == '^') {
                pos++
                return base.pow(power())
            }
            return base
        }

        private fun unary(): Double = when {
            pos < text.length && text[pos] == '-' -> { pos++; -unary() }
            pos < text.length && text[pos] == '+' -> { pos++; unary() }
            else -> primary()
        }

        private fun primary(): Double {
            if (pos < text.length && text[pos] == '(') {
                pos++
                val value = expression()
                require(pos < text.length && text[pos] == ')')
                pos++
                return value
            }
            val start = pos
            while (pos < text.length && (text[pos].isDigit() || text[pos] == '.')) pos++
            require(pos > start)
            return text.substring(start, pos).toDouble()
        }
    }
}
