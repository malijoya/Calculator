# Calculator App - Technical Documentation

## Overview
A feature-rich Android calculator application built with Kotlin, featuring real-time expression evaluation, calculation history with SQLite persistence, and smooth animations.

---

## 🧮 Core Logic & Algorithms

### 1. Expression Evaluation Engine

#### Operator Precedence Implementation (DMAS Rule)
The calculator implements proper mathematical order of operations using a two-pass algorithm:

**First Pass - High Precedence (`×`, `÷`, `%`)**
```kotlin
while (j < operators.size) {
    when (operators[j]) {
        "×" -> {
            numbers[j] = numbers[j] * numbers[j + 1]
            numbers.removeAt(j + 1)
            operators.removeAt(j)
        }
        "÷" -> {
            if (numbers[j + 1] != 0.0) {
                numbers[j] = numbers[j] / numbers[j + 1]
            } else {
                return Double.NaN // Division by zero
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
```

**Second Pass - Low Precedence (`+`, `-`)**
```kotlin
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
```

**Logic:**
- Parse expression into numbers and operators
- Mutate lists in-place by computing high-precedence operations first
- Then compute low-precedence operations
- Final result is `numbers[0]`

---

### 2. Number Formatting System

#### Thousands Separator Logic
```kotlin
private fun formatNumberWithCommas(number: String): String {
    val negative = sanitized.startsWith("–") || sanitized.startsWith("-")
    if (negative) sanitized = sanitized.drop(1)
    
    val parts = sanitized.split('.')
    val intPart = parts[0].trimStart('0').ifEmpty { "0" }
    val decPart = if (parts.size > 1) ".${parts[1]}" else ""
    
    // Insert commas every 3 digits from right
    val sb = StringBuilder()
    for (i in intPart.indices) {
        if (i > 0 && (intPart.length - i) % 3 == 0) sb.append(',')
        sb.append(intPart[i])
    }
    return (if (negative) "–" else "") + sb.toString() + decPart
}
```

**Logic:**
- Split number into integer and decimal parts
- Insert comma every 3 digits from the right
- Preserve negative sign and decimal portion

#### Scientific Notation
Numbers are displayed in scientific notation when:
- **Upper threshold**: `|value| >= 1e12`
- **Lower threshold**: `|value| < 1e-6`

```kotlin
private fun formatScientific(value: Double): String {
    val fmt = String.format(Locale.US, "%.%dE", SCI_SIG_DIGITS)
    var s = String.format(Locale.US, fmt, value)
    val parts = s.split("E")
    var mantissa = parts[0].trimEnd('0').trimEnd('.')
    var exponent = parts[1]
    if (exponent.startsWith("+")) exponent = exponent.substring(1)
    return mantissa + "E" + exponent
}
```

---

### 3. Real-Time Preview Calculation

#### Live Result Logic
```kotlin
private fun updateLiveResult() {
    var evalExpr = expr.replace(",", "")
    
    // Remove trailing operators or dots
    while (evalExpr.isNotEmpty() && (OPERATOR_CHARS.contains(evalExpr.last()) || evalExpr.last() == '.')) {
        evalExpr = evalExpr.dropLast(1)
    }
    
    // Only show preview if there's a binary operator (not just leading minus)
    var hasBinaryOperator = false
    for (i in evalExpr.indices) {
        val c = evalExpr[i]
        if (OPERATOR_CHARS.contains(c)) {
            if (i == 0 && (c == '–' || c == '-')) continue // Leading sign
            hasBinaryOperator = true
            break
        }
    }
    
    if (hasBinaryOperator) {
        val result = evaluateExpression(evalExpr)
        binding.tvResult.text = formatNumberForDisplay(result)
    }
}
```

**Logic:**
- Strip incomplete trailing operators/dots
- Only show preview for binary operations (not unary minus)
- Update in real-time as user types

---

### 4. Cursor-Aware Formatting

#### Debounced Formatting with Cursor Preservation
```kotlin
binding.tvDisplay.addTextChangedListener(object : TextWatcher {
    override fun afterTextChanged(s: Editable?) {
        if (isFormattingDisplay) return // Prevent recursion
        updateLiveResult()
        formatJob?.cancel()
        formatJob = lifecycleScope.launch {
            delay(formatDelayMs) // Debounce 120ms
            reformatDisplayWithCommas()
        }
    }
})
```

**Cursor Position Logic:**
```kotlin
private fun reformatDisplayWithCommas() {
    val cursor = binding.tvDisplay.selectionStart
    val atEndBefore = cursor >= original.length
    
    // Count non-comma characters before cursor
    var rawCursor = 0
    for (i in 0 until cursor) {
        if (original[i] != ',') rawCursor++
    }
    
    // Reformat and map cursor position
    val raw = original.replace(",", "")
    val formattedFull = formatExpressionWithCommas(raw)
    val rawPrefix = raw.substring(0, rawCursor)
    val formattedPrefix = formatExpressionWithCommas(rawPrefix)
    
    var newCursor = if (atEndBefore) formattedFull.length else formattedPrefix.length
    binding.tvDisplay.setText(formattedFull)
    binding.tvDisplay.setSelection(newCursor)
}
```

**Logic:**
- Debounce formatting to avoid fighting user input
- Track cursor position relative to non-comma characters
- Remap cursor after formatting by formatting only the prefix
- Handle edge case of cursor at end

---

### 5. History Persistence with SQLite

#### Database Schema
```sql
CREATE TABLE history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    entry TEXT NOT NULL
)
```

#### Auto-Limit History Logic
```kotlin
fun addHistory(entry: String, maxHistoryItems: Int) {
    val db = writableDatabase
    val cv = ContentValues()
    cv.put(COL_ENTRY, entry)
    db.insert(TABLE_HISTORY, null, cv)
    
    // Keep only the newest N items
    db.execSQL(
        "DELETE FROM $TABLE_HISTORY WHERE $COL_ID NOT IN " +
        "(SELECT $COL_ID FROM $TABLE_HISTORY ORDER BY $COL_ID DESC LIMIT ?)",
        arrayOf(maxHistoryItems.toString())
    )
}
```

**Logic:**
- Insert new entry
- Delete all except the most recent `maxHistoryItems` (default: 12)
- Newest-first ordering by ID

---

### 6. Complete Operation Validation

#### History Save Logic
```kotlin
val hasOperation = expression.any { it in "+–×÷%" }
val isCompleteOperation = hasOperation && !endsWithOperator

if (isCompleteOperation) {
    val historyEntry = "$formattedExpression = $formattedResult"
    saveToHistory(historyEntry)
    // Animate and save
}
```

**Logic:**
- Only save to history if expression contains an operator
- AND the expression doesn't end with an operator
- Prevents incomplete expressions like `123+` from being saved

---

### 7. Smart Operator Insertion

#### Operator Placement Rules
```kotlin
when {
    cursor == 0 -> {
        // Only allow minus at start (for negative numbers)
        if (op == getString(R.string.minus)) {
            sb.insert(0, op)
            newCursor = 1
        }
    }
    isOp(leftChar) -> {
        // Replace operator on the left
        sb.replace(cursor - 1, cursor, op)
        newCursor = cursor
    }
    isOp(rightChar) -> {
        // Replace operator on the right
        sb.replace(cursor, cursor + 1, op)
        newCursor = cursor + 1
    }
    else -> {
        // Normal insertion between numbers
        sb.insert(cursor, op)
        newCursor = cursor + op.length
    }
}
```

**Logic:**
- Prevents multiple consecutive operators
- Allows unary minus at beginning
- Intelligently replaces operators when inserting adjacent to existing ones

---

### 8. Decimal Point Validation

#### One Decimal Per Number Segment
```kotlin
private fun appendDecimal() {
    val textBeforeCursor = text.substring(0, cursorPosition)
    val lastOperatorIndex = textBeforeCursor.lastIndexOfAny(OPERATOR_CHARS)
    
    val currentSegment = if (lastOperatorIndex != -1) {
        textBeforeCursor.substring(lastOperatorIndex + 1)
    } else {
        textBeforeCursor
    }
    
    if (!currentSegment.contains(".")) {
        // Add decimal point
    }
}
```

**Logic:**
- Find the current number segment (between operators)
- Only allow one decimal point per segment
- Auto-prepend `0` if starting with decimal

---

### 9. Animation System

#### Result Reveal Animation
```kotlin
if (isCompleteOperation) {
    val displayMoveUp = AnimationUtils.loadAnimation(this, R.anim.display_move_up)
    val resultSlideIn = AnimationUtils.loadAnimation(this, R.anim.result_slide_in)
    
    resultSlideIn.setAnimationListener(object : Animation.AnimationListener {
        override fun onAnimationEnd(animation: Animation?) {
            binding.tvDisplay.setText(formattedResult)
            binding.tvResult.text = ""
            lastCalculatedExpression = formattedResult
            isCalculating = false
        }
    })
    
    binding.tvDisplay.startAnimation(displayMoveUp)
    binding.tvResult.startAnimation(resultSlideIn)
}
```

**Logic:**
- Prevent multiple simultaneous calculations with `isCalculating` flag
- Display moves up while result slides in
- Commit result to display after animation completes
- Only animate for complete operations

---

### 10. Duplicate Calculation Prevention

```kotlin
private var lastCalculatedExpression = ""

private fun calculateResult() {
    val expression = binding.tvDisplay.text.toString()
    if (expression == lastCalculatedExpression) return
    
    // ... perform calculation ...
    
    lastCalculatedExpression = formattedResult
}
```

**Logic:**
- Track the last calculated expression
- Prevent re-calculating if user presses equals multiple times on same expression

---

## 📊 Key Constants

```kotlin
private val OPERATOR_CHARS = charArrayOf('+', '–', '×', '÷', '%')
private val SCI_NOTATION_UPPER = 1e12
private val SCI_NOTATION_LOWER = 1e-6
private val SCI_SIG_DIGITS = 10
private val MAX_HISTORY_ITEMS = 12
private val formatDelayMs = 120L
```

---

## 🗄️ Database Constants

```kotlin
companion object {
    private const val DB_NAME = "history.db"
    private const val TABLE_HISTORY = "history"
    private const val COL_ID = "id"
    private const val COL_ENTRY = "entry"
}
```

---

## 🎨 Features

1. **Real-time expression evaluation** with live preview
2. **Cursor-aware formatting** preserves editing position
3. **Proper operator precedence** (DMAS)
4. **Scientific notation** for very large/small numbers
5. **Thousands separators** with commas
6. **Persistent history** (SQLite, max 12 items)
7. **Smooth animations** for result reveal
8. **Smart operator insertion** prevents consecutive operators
9. **Decimal validation** (one per number segment)
10. **Complete operation validation** (only saves finished calculations)

---

## 🛠️ Technologies Used

- **Language**: Kotlin
- **UI**: ViewBinding
- **Database**: SQLite (SQLiteOpenHelper)
- **Concurrency**: Kotlin Coroutines (lifecycleScope)
- **Animations**: Android Animation Framework
- **Architecture**: Single Activity with clean separation of concerns

---

## 📝 Code Quality Improvements

- Extracted hardcoded strings to `strings.xml`
- Database constants in companion object
- Magic numbers replaced with named constants
- Comprehensive inline comments
- Resource leak prevention (`onDestroy` cleanup)

---

## 🚀 Recent Fixes

1. **History Save Logic**: Only saves complete operations (e.g., `123+2` not `123+`)
2. **Resource Cleanup**: Removed unused string resources
3. **Code Refactoring**: Centralized constants and extracted hardcoded values
#   C a l c u l a t o r  
 