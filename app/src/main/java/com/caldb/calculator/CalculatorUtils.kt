package com.caldb.calculator

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

object CalculatorUtils {
    
    // Constants
    val OPERATOR_CHARS = charArrayOf('+', '–', '×', '÷', '%')
    private const val SCI_NOTATION_UPPER = 1e12
    private const val SCI_NOTATION_LOWER = 1e-6
    private const val SCI_SIG_DIGITS = 10

    
    /**
     * Gets the appropriate button text color based on the current theme.
     * @param context Application context
     * @return Color.WHITE for dark theme, Color.BLACK for light theme
     */
    fun getDialogButtonColor(context: Context): Int {
        val nightMask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isDark = nightMask == Configuration.UI_MODE_NIGHT_YES
        return if (isDark) Color.WHITE else Color.BLACK
    }

    /**
     * Evaluates a mathematical expression string.
     * Supports +, -, ×, ÷, % with operator precedence.
     */
    fun evaluateExpression(expr: String): Double { // DMAS rule
        // Normalize display minus (\u2212) to ASCII '-' for numeric parsing
        val normalized = expr.replace('–', '-')

        val numbers = mutableListOf<Double>() // Operands collected in sequence
        val operators = mutableListOf<String>() // Operators collected in sequence between numbers

        var currentNum = "" // Build the current number token as string to preserve dots and sign
        for (char in normalized) { // Simple single-pass tokenizer
            if (char.isDigit() || char == '.' || (char == '-' && currentNum.isEmpty())) {
                // Append digits, dot, or leading '-' to start a negative number
                currentNum += char
            } else if (char in "+-×÷%") { // On operator, flush current number and record operator
                if (currentNum.isNotEmpty()) {
                    numbers.add(currentNum.toDouble())
                    currentNum = ""
                }
                operators.add(char.toString())
            }
        }

        if (currentNum.isNotEmpty()) numbers.add(currentNum.toDouble()) // Flush trailing number

        if (numbers.isEmpty()) return 0.0
        if (numbers.size == 1 && operators.isEmpty()) return numbers[0]

        // First pass: handle ×, ÷, % (higher precedence)
        var j = 0 // Iterate through operators, mutating numbers list in place
        while (j < operators.size) {
            when (operators[j]) {
                "×" -> {
                    numbers[j] = numbers[j] * numbers[j + 1]
                    numbers.removeAt(j + 1)
                    operators.removeAt(j)
                }
                "÷" -> {
                    if (numbers[j + 1] != 0.0) { // Guard against division by zero (return NaN for invalid)
                        numbers[j] = numbers[j] / numbers[j + 1]
                    } else {
                        return Double.NaN // Easier to propagate invalid state than throwing
                    }
                    numbers.removeAt(j + 1)
                    operators.removeAt(j)
                }
                "%" -> {
                    numbers[j] = numbers[j] % numbers[j + 1]
                    numbers.removeAt(j + 1)
                    operators.removeAt(j)
                }
                else -> j++
            }
        }

        // Second pass: handle +, - (lower precedence)
        j = 0 // Restart from beginning for remaining ops
        while (j < operators.size) {
            when (operators[j]) {
                "+" -> {
                    numbers[j] = numbers[j] + numbers[j + 1]
                    numbers.removeAt(j + 1)
                    operators.removeAt(j)
                }
                "-" -> {
                    numbers[j] = numbers[j] - numbers[j + 1]
                    numbers.removeAt(j + 1)
                    operators.removeAt(j)
                }
                else -> j++
            }
        }
        return if (numbers.isNotEmpty()) numbers[0] else 0.0
    }

    /**
     * Suspend version of evaluateExpression that runs on the Default dispatcher.
     * Use this for heavy calculations to avoid blocking the main thread.
     */
    suspend fun evaluateExpressionAsync(expr: String): Double = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        evaluateExpression(expr)
    }

    /**
     * Formats a number for display, choosing between comma-separated decimal 
     * and scientific notation for extreme magnitudes.
     */
    fun formatNumberForDisplay(value: Double, context: Context? = null): String {
        if (value.isNaN() || value.isInfinite()) return "Error"
        if (value == 0.0) return "0"
        val absVal = kotlin.math.abs(value)
        val useScientific = absVal >= SCI_NOTATION_UPPER || absVal < SCI_NOTATION_LOWER
        return if (useScientific) formatScientific(value) else formatNumberWithCommas(formatResultWithPrecision(value))
    }

    // Format using scientific notation like 3.93629765321E46 (trim trailing zeros)
    private fun formatScientific(value: Double): String {
        val fmt = String.format(java.util.Locale.US, "%%.%dE", SCI_SIG_DIGITS)
        var s = String.format(java.util.Locale.US, fmt, value)
        val parts = s.split("E")
        if (parts.size != 2) return s
        var mantissa = parts[0].trimEnd('0').trimEnd('.')
        var exponent = parts[1]
        if (exponent.startsWith("+")) exponent = exponent.substring(1)
        return mantissa + "E" + exponent
    }

    // Helper function to format result, preserving decimals and trimming trailing zeros
    private fun formatResultWithPrecision(result: Double): String {
        if (result % 1 == 0.0) {
            // It's a whole number, return without decimal
            return result.toLong().toString()
        }
        // Format with up to 10 decimal places and trim trailing zeros
        val formatted = String.format("%.10f", result).trimEnd('0')
        // Remove trailing decimal point if it exists
        return formatted.trimEnd('.')
    }

    // Format number string for display with commas (handles decimals, ignores signs and empty)
    fun formatNumberWithCommas(number: String): String {
        // Remove leading zeros unless just "0"
        var sanitized = number
        val negative = sanitized.startsWith("–") || sanitized.startsWith("-")
        if (negative) sanitized = sanitized.drop(1)
        if (sanitized.isEmpty()) return if (negative) "–" else ""
        val parts = sanitized.split('.')
        val intPart = parts[0].trimStart('0').ifEmpty { "0" }
        val decPart = if (parts.size > 1) ".${parts[1]}" else ""
        // Insert commas every 3 digits from right (works for any length)
        val sb = StringBuilder()
        for (i in intPart.indices) {
            if (i > 0 && (intPart.length - i) % 3 == 0) sb.append(',')
            sb.append(intPart[i])
        }
        return (if (negative) "–" else "") + sb.toString() + decPart
    }

    // Format an expression string; add commas to all numbers but leave operators unchanged
    fun formatExpressionWithCommas(expr: String): String {
        val sb = StringBuilder()
        var numberBuffer = StringBuilder()
        for (c in expr) {
            if (c.isDigit() || c == '.' || (numberBuffer.isEmpty() && (c == '–' || c == '-'))) {
                numberBuffer.append(c)
            } else {
                // Flush number with commas if applicable
                if (numberBuffer.isNotEmpty()) {
                    sb.append(formatNumberWithCommas(numberBuffer.toString()))
                    numberBuffer = StringBuilder()
                }
                sb.append(c)
            }
        }
        // Flush last token
        if (numberBuffer.isNotEmpty()) sb.append(formatNumberWithCommas(numberBuffer.toString()))
        return sb.toString()
    }
}
