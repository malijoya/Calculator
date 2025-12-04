package com.caldb.calculator

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.animation.Animation
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.caldb.calculator.databinding.ActivityMainBinding
import android.view.animation.AnimationUtils
import androidx.core.graphics.toColorInt // Convert hex color string to int => used in Navigation
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



    private val MAX_HISTORY_ITEMS = 14

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

        binding.historyBtn.setOnClickListener {
            // Launch HistoryActivity
            startActivity(Intent(this, HistoryActivity::class.java))
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

        if (text.isEmpty() || cursorPosition == 0 || CalculatorUtils.OPERATOR_CHARS.contains(text[cursorPosition - 1])) {
            val newText = text.substring(0, cursorPosition) + "0." + text.substring(cursorPosition) // Insert 0.
            binding.tvDisplay.setText(newText)
            binding.tvDisplay.setSelection(cursorPosition + 2)
            return
        }

        val textBeforeCursor = text.substring(0, cursorPosition) // Consider only the current left-side segment
        val lastOperatorIndex = textBeforeCursor.lastIndexOfAny(CalculatorUtils.OPERATOR_CHARS) // Split by the nearest operator

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

        fun isOp(c: Char?): Boolean = c != null && CalculatorUtils.OPERATOR_CHARS.contains(c) // Helper to test operator membership

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
    private fun calculateResult() { // Evaluate current expression and show result with a small animation
        // Prevent multiple simultaneous calculations
        if (isCalculating) return
        
        val expression = binding.tvDisplay.text.toString()
        if (expression.isEmpty()) return
        
        // Prevent duplicate calculations - if user already calculated this expression, don't do it again
        if (expression == lastCalculatedExpression) return

        // Mark as calculating to prevent rapid button presses
        isCalculating = true

        lifecycleScope.launch {
            try {
                val raw = expression.replace(",", "") // Strip formatting commas before evaluation
                val lastChar = raw.last() // Inspect last char to handle trailing operator cases
                val endsWithOperator = CalculatorUtils.OPERATOR_CHARS.contains(lastChar)
                val evalExpr = if (endsWithOperator) { // If user ended on operator, create a sensible temporary expression
                    val trimmed = raw.dropLast(1)
                    if ( lastChar == '–') {
                        // Example: A - B -  → A - -B (so user sees expected result trend)
                        val lastOpIndex = trimmed.lastIndexOfAny(charArrayOf('+', '×', '÷', '%', '–'))
                        val lastToken = if (lastOpIndex != -1) trimmed.substring(lastOpIndex + 1) else trimmed
                        val absToken = lastToken.trim().trimStart('–') // Make a negative of the last number
                        if (absToken.isEmpty()) trimmed // fallback if malformed
                        else "$trimmed-$absToken"
                    } else {
                        trimmed // Drop trailing non-minus operator (e.g., + × ÷ %)
                    }
                } else raw
                
                if (evalExpr.isEmpty()) {
                    isCalculating = false
                    return@launch
                }

                // Run heavy calculation on background thread
                val result = CalculatorUtils.evaluateExpressionAsync(evalExpr)
                val formattedResult = CalculatorUtils.formatNumberForDisplay(result)
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
                    
                    // Animate: move display up and result slides in
                    val displayMoveUp = AnimationUtils.loadAnimation(this@MainActivity, R.anim.display_move_up)
                    val resultSlideIn = AnimationUtils.loadAnimation(this@MainActivity, R.anim.result_slide_in)

                    resultSlideIn.setAnimationListener(object : Animation.AnimationListener {
                        override fun onAnimationStart(animation: Animation?) {}

                        override fun onAnimationEnd(animation: Animation?) {
                            binding.tvDisplay.setText(formattedResult) // Commit result to main display
                            try {
                                binding.tvDisplay.setSelection(formattedResult.length)
                            } catch (e: IndexOutOfBoundsException) { }
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
                    isCalculating = false
                }

            } catch (e: Exception) { // Fallback to error state on any unexpected parse/compute issue
                binding.tvDisplay.setText(getString(R.string.error)) // Show a friendly error instead of crashing
                binding.tvResult.text = ""
                isCalculating = false
            }
        }
    }



    private fun saveToHistory(entry: String) {
        lifecycleScope.launch {
            // Create temporary DB instance for writing only
            val db = HistoryDatabase(this@MainActivity)
            db.addHistory(entry, maxHistoryItems = MAX_HISTORY_ITEMS)
            db.close()
        }
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
        while (evalExpr.isNotEmpty() && (CalculatorUtils.OPERATOR_CHARS.contains(evalExpr.last()) || evalExpr.last() == '.')) {
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
            if (CalculatorUtils.OPERATOR_CHARS.contains(c)) {
                if (i == 0 && (c == '–' || c == '-')) continue // leading sign, not binary op
                hasBinaryOperator = true
                break
            }
        }
        if (!hasBinaryOperator) {
            binding.tvResult.text = ""
            return
        }
        
        lifecycleScope.launch {
            try {
                val result = CalculatorUtils.evaluateExpressionAsync(evalExpr) // Safe compute for preview
                if (result.isNaN()) { // Invalid (e.g., divide by zero) → don't show preview
                    binding.tvResult.text = ""
                    return@launch
                }
                binding.tvResult.text = CalculatorUtils.formatNumberForDisplay(result) // Pretty print preview
            } catch (e: Exception) {
                binding.tvResult.text = "" // Swallow errors quietly in preview to avoid noise
            }
        }
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
        val formattedFull = CalculatorUtils.formatExpressionWithCommas(raw)
        if (formattedFull == original) return

        // Compute new cursor by formatting only the raw prefix
        val rawPrefix = if (rawCursor <= raw.length) raw.substring(0, rawCursor) else raw
        val formattedPrefix = CalculatorUtils.formatExpressionWithCommas(rawPrefix)
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
