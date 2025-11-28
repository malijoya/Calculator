package com.caldb.calculator

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.animation.Animation
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.caldb.calculator.databinding.ActivityMainBinding
import android.view.LayoutInflater
import android.view.animation.AnimationUtils
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.toColorInt // Convert hex color string to int => used in Navigation
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale // Ensure consistent scientific formatting with US locale
import kotlin.math.abs
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var isFormattingDisplay = false
    private val formatDelayMs = 120L // Debounce user typing; small delay feels responsive
    private var formatJob: Job? = null // Coroutine job for debounced formatting
    
    private var isCalculating = false // Prevent multiple simultaneous calculations
    private var lastCalculatedExpression = "" // Track last calculated expression to prevent duplicates

    private val OPERATOR_CHARS = charArrayOf('+', '–', '×', '÷', '%')
    private val SCI_NOTATION_UPPER = 1e12 // Above this magnitude shows scientific notation
    private val SCI_NOTATION_LOWER = 1e-6
    private val SCI_SIG_DIGITS = 10

    private val MAX_HISTORY_ITEMS = 12

    private lateinit var historyDB: HistoryDatabase


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.navigationBarColor = "#252525".toColorInt()


        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, 0, 0, systemBars.bottom)
            insets
        }

        historyDB = HistoryDatabase(this)


        binding.historyBtn.setOnClickListener {
            showHistoryDialog()
        }

        binding.tvDisplay.setText("")
        // Keep software keyboard hidden but allow cursor navigation
        try { 
            binding.tvDisplay.setShowSoftInputOnFocus(false) 
        } catch (e: NoSuchMethodError) { }

        binding.tvDisplay.addTextChangedListener(object : TextWatcher {

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (isFormattingDisplay) return // Skip if change came from our own formatter to prevent loops
                updateLiveResult()
                formatJob?.cancel() // Cancel any pending formatting job
                formatJob = lifecycleScope.launch {
                    delay(formatDelayMs) // Debounce delay
                    reformatDisplayWithCommas() // Insert commas without fighting the cursor
                }
            }
        })

        setupNumberButtons()
        setupOperatorButtons()
        setupExtraButtons()
    }


    override fun onDestroy() { // Cleanup resources to prevent leaks
        super.onDestroy()
        formatJob?.cancel() // Cancel any pending formatting coroutine
        historyDB.close() // Properly close database connection
    }


    // ------------------- Setup Number Buttons -------------------
    private fun setupNumberButtons() { // Assign listeners for digits and decimal point
        val numberButtons = listOf(
            binding.btn0, binding.btn1, binding.btn2, binding.btn3,
            binding.btn4, binding.btn5, binding.btn6, binding.btn7,
            binding.btn8, binding.btn9
        )


        for (button in numberButtons) {
            button.setOnClickListener {
                val value = button.text.toString() // Use button text as the digit; avoids hardcoding
                appendNumber(value)
            }
        }

        binding.btnDot.setOnClickListener {
            appendDecimal()
        }
    }


    // ------------------- Setup Operator Buttons -------------------
    private fun setupOperatorButtons() { // Assign listeners for operators and equals
        // Each operator button sets the chosen operation
        binding.plusBtn.setOnClickListener { setOperator(getString(R.string.plus)) }
        binding.minusBtn.setOnClickListener { setOperator(getString(R.string.minus)) }
        binding.multiplyBtn.setOnClickListener { setOperator(getString(R.string.multiply)) }
        binding.divideBtn.setOnClickListener { setOperator(getString(R.string.divide)) }
        binding.percentageBtn.setOnClickListener { setOperator(getString(R.string.percent)) }

        binding.equalBtn.setOnClickListener { calculateResult() }
    }

    private fun setupExtraButtons() {
        // Clear button
        binding.clearBtn.setOnClickListener {
            lastCalculatedExpression = "" // Reset calculation flag
            binding.tvDisplay.setText("")
            binding.tvResult.text = ""
        }

        // Back button
        binding.backBtn.setOnClickListener {
            lastCalculatedExpression = "" // Reset to allow recalculation
            val cursorPosition = binding.tvDisplay.selectionStart
            if (cursorPosition > 0) {
                val text = binding.tvDisplay.text.toString()
                val newText = text.substring(0, cursorPosition - 1) + text.substring(cursorPosition) // Remove one char
                binding.tvDisplay.setText(newText)
                binding.tvDisplay.setSelection(cursorPosition - 1)
            }
        }
    }

    // -------------------Append Number -------------------
    private fun appendNumber(num: String) {
        lastCalculatedExpression = "" // Reset to allow new calculation
        val cursorPosition = binding.tvDisplay.selectionStart // Where the user is typing
        val text = binding.tvDisplay.text.toString() // Current expression with commas
        val newText = text.substring(0, cursorPosition) + num + text.substring(cursorPosition) // Splice-in char
        binding.tvDisplay.setText(newText) // Apply change
        binding.tvDisplay.setSelection(cursorPosition + 1)
    }

    // ------------------- Append Decimal -------------------
    private fun appendDecimal() { // Add a decimal separator if valid in the current number segment
        val cursorPosition = binding.tvDisplay.selectionStart
        val text = binding.tvDisplay.text.toString()

        if (text.isEmpty() || cursorPosition == 0 || OPERATOR_CHARS.contains(text[cursorPosition - 1])) {
            val newText = text.substring(0, cursorPosition) + "0." + text.substring(cursorPosition) // Insert 0.
            binding.tvDisplay.setText(newText)
            binding.tvDisplay.setSelection(cursorPosition + 2)
            return
        }

        val textBeforeCursor = text.substring(0, cursorPosition) // Consider only the current left-side segment
        val lastOperatorIndex = textBeforeCursor.lastIndexOfAny(OPERATOR_CHARS) // Split by the nearest operator

        val currentSegment = if (lastOperatorIndex != -1) { // Extract current number segment
            textBeforeCursor.substring(lastOperatorIndex + 1)
        } else {
            textBeforeCursor
        }

        if (!currentSegment.contains(".")) { // Only one dot per number segment
            val newText = text.substring(0, cursorPosition) + "." + text.substring(cursorPosition)
            binding.tvDisplay.setText(newText)
            binding.tvDisplay.setSelection(cursorPosition + 1)
        }
    }

    // ------------------- Set Operator -------------------
    private fun setOperator(op: String) { // Insert/replace operator around cursor with sensible rules
        val text = binding.tvDisplay.text.toString()
        val cursor = try { binding.tvDisplay.selectionStart } catch (_: Throwable) { text.length } // Fallback if selection fails

        // If empty, allow only unary minus
        if (text.isEmpty()) { // Only unary minus makes sense at the start (for negative numbers)
            if (op == getString(R.string.minus)) {
                binding.tvDisplay.setText(op)
                binding.tvDisplay.setSelection(1)
            }
            return
        }


        if (text == getString(R.string.minus)) { // Single minus is a sign, not a binary operator
            if (op != getString(R.string.minus)) {
                // replacing unary minus with another operator not allowed
                return
            }
            return
        }

        val sb = StringBuilder(text) // Mutable text for in-place edits
        val atEnd = cursor >= sb.length // Whether cursor pointer is at end (common case)
        val leftChar = if (cursor > 0 && cursor - 1 < sb.length) sb[cursor - 1] else null // Char before cursor pointer
        val rightChar = if (cursor < sb.length) sb[cursor] else null // Char at cursor pointer

        fun isOp(c: Char?): Boolean = c != null && OPERATOR_CHARS.contains(c) // Helper to test operator membership

        var newCursor = cursor // Track new cursor pointer position after edit

        when {
            // Insert at start: allow only minus
            cursor == 0 -> {
                if (op == getString(R.string.minus)) {
                    sb.insert(0, op)
                    newCursor = 1
                } else {
                    // ignore non-minus at start
                    return
                }
            }


            isOp(leftChar) -> { // Replace operator on the left to avoid duplicates (e.g., ++)
                sb.replace(cursor - 1, cursor, op)
                newCursor = cursor
            }

            // Replace operator on the right (keep single operator)
            isOp(rightChar) -> { // Replace operator on the right if cursor pointer sits before one
                sb.replace(cursor, cursor + 1, op)
                newCursor = cursor + 1
            }

            else -> { // Normal insertion between numbers
                // Normal insert in between digits
                sb.insert(cursor, op)
                newCursor = cursor + op.length
            }
        }

        isFormattingDisplay = true // Suppress afterTextChanged loop during programmatic setText
        binding.tvDisplay.setText(sb.toString()) // Apply edited expression
        try { 
            binding.tvDisplay.setSelection(newCursor) 
        } catch (e: IndexOutOfBoundsException) {
            // Cursor position invalid, ignore
        }
        isFormattingDisplay = false

        updateLiveResult()
        formatJob?.cancel() // Cancel any pending formatting job
        formatJob = lifecycleScope.launch {
            delay(formatDelayMs)
            reformatDisplayWithCommas()
        }
    }

    // ------------------- Calculate Result -------------------
    @SuppressLint("SuspiciousIndentation")
    private fun calculateResult() { // Evaluate current expression and show result with a small animation
        // Prevent multiple simultaneous calculations
        if (isCalculating) return
        
        val expression = binding.tvDisplay.text.toString()
        if (expression.isEmpty()) return
        
        // Prevent duplicate calculations - if user already calculated this expression, don't do it again
        if (expression == lastCalculatedExpression) return

        val resultText = binding.tvResult.text.toString()

        try {
            val raw = expression.replace(",", "") // Strip formatting commas before evaluation
            val lastChar = raw.last() // Inspect last char to handle trailing operator cases
            val endsWithOperator = OPERATOR_CHARS.contains(lastChar)
            val evalExpr = if (endsWithOperator) { // If user ended on operator, create a sensible temporary expression
                val trimmed = raw.dropLast(1)
                if ( lastChar == '–') {
                    // Example: A - B -  → A - -B (so user sees expected result trend)
                    val lastOpIndex = trimmed.lastIndexOfAny(charArrayOf('+', '×', '÷', '%', '–'))
                    val lastToken = if (lastOpIndex != -1) trimmed.substring(lastOpIndex + 1) else trimmed
                    val absToken = lastToken.trim().trimStart('–') // Make a negative of the last number
                    if (absToken.isEmpty()) trimmed // fallback if malformed
                    else trimmed + "-" + absToken
                } else {
                    trimmed // Drop trailing non-minus operator (e.g., + × ÷ %)
                }
            } else raw
            if (evalExpr.isEmpty()) return // Safety
            val result = evaluateExpression(evalExpr)
            val formattedResult = formatNumberForDisplay(result)
            val formattedExpression = expression.replace("×", " × ")
                .replace("÷", " ÷ ")
                .replace("+", " + ")
                .replace("–", " – ")
                .replace("%", " % ")

            // Check if there's an actual complete operation to perform
            val hasOperation = expression.any { it in "+–×÷%" }
            val isCompleteOperation = hasOperation && !endsWithOperator
            
            if (isCompleteOperation) { // Only store and animate if a complete operation was performed
                val historyEntry = "$formattedExpression = $formattedResult" // Concise human-friendly item
                saveToHistory(historyEntry) // Persist newest-first
                
                // Mark as calculating to prevent rapid button presses
                isCalculating = true

                // Animate: move display up and result slides in
                val displayMoveUp = AnimationUtils.loadAnimation(this, R.anim.display_move_up)
                val resultSlideIn = AnimationUtils.loadAnimation(this, R.anim.result_slide_in)

                resultSlideIn.setAnimationListener(object : Animation.AnimationListener {
                    override fun onAnimationStart(animation: Animation?) {}

                    override fun onAnimationEnd(animation: Animation?) {
                        binding.tvDisplay.setText(formattedResult) // Commit result to main display
                        try {
                            binding.tvDisplay.setSelection(formattedResult.length)
                        } catch (e: IndexOutOfBoundsException) {
                            // Selection position invalid, ignore
                        }
                        binding.tvResult.text = "" // Clear preview
                        binding.tvDisplay.clearAnimation()
                        binding.tvResult.clearAnimation()
                        
                        // Store the result as last calculated to prevent duplicates
                        lastCalculatedExpression = formattedResult
                        // Re-enable calculation after animation completes
                        isCalculating = false
                    }

                    override fun onAnimationRepeat(animation: Animation?) {}
                })

                binding.tvDisplay.startAnimation(displayMoveUp) // Move display upward
                binding.tvResult.startAnimation(resultSlideIn) // Slide result in
            } else {
                // No operation, just update display without animation
                binding.tvDisplay.setText(formattedResult)
                try {
                    binding.tvDisplay.setSelection(formattedResult.length)
                } catch (e: IndexOutOfBoundsException) {
                    // Selection position invalid, ignore
                }
                binding.tvResult.text = ""
                lastCalculatedExpression = formattedResult // Store to prevent re-calculation
            }

        } catch (e: Exception) { // Fallback to error state on any unexpected parse/compute issue
            binding.tvDisplay.setText(getString(R.string.error)) // Show a friendly error instead of crashing
            binding.tvResult.text = ""
        }
    }

    // ------------------- Evaluate Expression with Operator Precedence -------------------
    private fun evaluateExpression(expr: String): Double { // DMAS rule
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

        return numbers[0] // Final result remains as the single element after reductions
    }

    // ------------------- Save History -------------------
    private fun saveToHistory(entry: String) {
        historyDB.addHistory(
            entry,
            maxHistoryItems = MAX_HISTORY_ITEMS
        )
    }

    // ------------------- Get All History -------------------
    private fun getHistory(): List<String> {
        return historyDB.getAllHistory()
    }

    // ------------------- Show History Dialog -------------------
    private fun showHistoryDialog() { // Display custom dialog with styled list, newest-first
        val historyList = getHistory()

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_history, null) // Inflate custom layout
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.historyList) // Recycler for showing entries
        val emptyHistoryText = dialogView.findViewById<TextView>(R.id.emptyHistoryText) // Shown when no items exist
        val closeButton = dialogView.findViewById<View>(R.id.closeButton) // Close button (X) in top-right

        val dialog = AlertDialog.Builder(this) // Create dialog first to access it in callbacks
            .setView(dialogView)
            .setNegativeButton(getString(R.string.clear), null) // Set null initially, will override below
            .setCancelable(true) // Allow dismiss by tapping outside
            .create()

        // Set close button click listener
        closeButton.setOnClickListener {
            dialog.dismiss()
        }

        if (historyList.isEmpty()) { // Toggle between empty state and list content
            recyclerView.visibility = View.GONE
            emptyHistoryText.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyHistoryText.visibility = View.GONE
            recyclerView.layoutManager = LinearLayoutManager(this) // Vertical list; simple and sufficient
            recyclerView.setHasFixedSize(true) // Size of items does not change → performance hint
            
            // Create adapter with click callback
            recyclerView.adapter = HistoryAdapter(historyList) { historyEntry ->
                // Extract expression part (before " = ")
                val expression = historyEntry.substringBefore(" = ")
                    .replace(" × ", "×")
                    .replace(" ÷ ", "÷")
                    .replace(" + ", "+")
                    .replace(" – ", "–")
                    .replace(" % ", "%")
                
                // Load expression back to calculator
                binding.tvDisplay.setText(expression)
                binding.tvResult.text = "" // Clear result preview
                try {
                    binding.tvDisplay.setSelection(expression.length) // Move cursor to end
                } catch (e: IndexOutOfBoundsException) {
                    // Ignore cursor positioning errors
                }
                
                dialog.dismiss() // Close dialog after loading
            }
        }

        dialog.show() // Must show before accessing dialog buttons

        // Override Clear button to show confirmation dialog
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
            if (historyList.isEmpty()) {
                dialog.dismiss()
                return@setOnClickListener
            }
            
            // Show confirmation dialog
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.clear_history_title))
                .setMessage(getString(R.string.clear_history_message, historyList.size, if (historyList.size > 1) "s" else ""))
                .setPositiveButton(getString(R.string.clear)) { _, _ ->
                    clearHistory()
                    dialog.dismiss()
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }

        // Set button text color based on theme (white in dark, black in light)
        val nightMask = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK // Detect theme
        val isDark = nightMask == Configuration.UI_MODE_NIGHT_YES // True if dark theme active
        val buttonColor = if (isDark) Color.WHITE else Color.BLACK // Ensure good contrast for buttons
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor) // Clear button
    }

    // ------------------- Clear All History -------------------
    private fun clearHistory() {
        historyDB.clearHistory()
    }

    private fun updateLiveResult() { // Show running total while typing without committing to display
        // Build full expression (no commas) for evaluation
        val expr = binding.tvDisplay.text.toString().replace(",", "")

        if (expr.isEmpty()) { // Nothing to compute
            binding.tvResult.text = ""
            return
        }

        // If input ends with an operator or trailing dot, evaluate the prefix up to the last complete number
        var evalExpr = expr // Remove trailing operator/dot to form a valid prefix expression
        while (evalExpr.isNotEmpty() && (OPERATOR_CHARS.contains(evalExpr.last()) || evalExpr.last() == '.')) {
            evalExpr = evalExpr.dropLast(1)
        }
        if (evalExpr.isEmpty()) {
            binding.tvResult.text = ""
            return
        }

        // Only show preview after a binary operator appears (ignore a leading minus)
        var hasBinaryOperator = false // Only show preview if there's at least one binary operator
        for (i in evalExpr.indices) {
            val c = evalExpr[i]
            if (OPERATOR_CHARS.contains(c)) {
                if (i == 0 && (c == '–' || c == '-')) continue // leading sign, not binary op
                hasBinaryOperator = true
                break
            }
        }
        if (!hasBinaryOperator) {
            binding.tvResult.text = ""
            return
        }
        try {
            val result = evaluateExpression(evalExpr) // Safe compute for preview
            if (result.isNaN()) { // Invalid (e.g., divide by zero) → don't show preview
                binding.tvResult.text = ""
                return
            }
            binding.tvResult.text = formatNumberForDisplay(result) // Pretty print preview
        } catch (e: Exception) {
            binding.tvResult.text = "" // Swallow errors quietly in preview to avoid noise
        }
    }

    // Format number string for display with commas (handles decimals, ignores signs and empty)
    private fun formatNumberWithCommas(number: String): String { // Insert thousands separators while preserving decimals/sign
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

    // Helper function to format result, preserving decimals and trimming trailing zeros
    private fun formatResultWithPrecision(result: Double): String { // Keep up to 10 decimals, trim trailing zeros
        if (result % 1 == 0.0) {
            // It's a whole number, return without decimal
            return result.toLong().toString()
        }
        // Format with up to 10 decimal places and trim trailing zeros
        val formatted = String.format("%.10f", result).trimEnd('0')
        // Remove trailing decimal point if it exists
        return formatted.trimEnd('.')
    }

    // Choose between comma-separated decimal and scientific notation for extreme magnitudes
    private fun formatNumberForDisplay(value: Double): String { // Choose between comma-format and scientific for extreme magnitudes
        if (value.isNaN() || value.isInfinite()) return getString(R.string.error)
        if (value == 0.0) return "0"
        val absVal = abs(value)
        val useScientific = absVal >= SCI_NOTATION_UPPER || absVal < SCI_NOTATION_LOWER
        return if (useScientific) formatScientific(value) else formatNumberWithCommas(formatResultWithPrecision(value))
    }

    // Format using scientific notation like 3.93629765321E46 (trim trailing zeros)
    private fun formatScientific(value: Double): String { // Print like 3.9362976532E46 with trimmed mantissa zeros
        val fmt = String.format(Locale.US, "%%.%dE", SCI_SIG_DIGITS)
        var s = String.format(Locale.US, fmt, value)
        val parts = s.split("E")
        if (parts.size != 2) return s
        var mantissa = parts[0].trimEnd('0').trimEnd('.')
        var exponent = parts[1]
        if (exponent.startsWith("+")) exponent = exponent.substring(1)
        return mantissa + "E" + exponent
    }

    // Format an expression string; add commas to all numbers but leave operators unchanged
    private fun formatExpressionWithCommas(expr: String): String { // Add commas to all numeric tokens without touching operators
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

    private fun reformatDisplayWithCommas() { // Non-destructive reformat preserving cursor as best as possible
        val original = binding.tvDisplay.text?.toString() ?: return
        if (original.isEmpty()) return
        val cursor = try { 
            binding.tvDisplay.selectionStart 
        } catch (e: Exception) { 
            original.length // Fallback to end
        }
        val atEndBefore = cursor >= original.length

        var rawCursor = 0
        var i = 0
        while (i < cursor && i < original.length) {
            if (original[i] != ',') rawCursor++
            i++
        }
        val raw = original.replace(",", "")
        val formattedFull = formatExpressionWithCommas(raw)
        if (formattedFull == original) return

        // Compute new cursor by formatting only the raw prefix
        val rawPrefix = if (rawCursor <= raw.length) raw.substring(0, rawCursor) else raw
        val formattedPrefix = formatExpressionWithCommas(rawPrefix)
        var newCursor = if (atEndBefore) formattedFull.length else formattedPrefix.length // Heuristic caret mapping
        if (newCursor > formattedFull.length) newCursor = formattedFull.length
        isFormattingDisplay = true // Prevent recursion
        binding.tvDisplay.setText(formattedFull)
        try { 
            binding.tvDisplay.setSelection(newCursor) 
        } catch (e: IndexOutOfBoundsException) { }
        isFormattingDisplay = false
    }
}
