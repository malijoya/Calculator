package com.caldb.calculator

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.caldb.calculator.databinding.ActivityHistoryBinding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch


class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var historyDB: HistoryDatabase



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup toolbar with back button
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            title = getString(R.string.history)
        }

        // Set status bar color to match background
        window.statusBarColor = androidx.core.content.ContextCompat.getColor(this, R.color.appBackground)

        historyDB = HistoryDatabase(this)

        // Load and display history
        loadHistory()

        // Setup clear button
        binding.clearAllBtn.setOnClickListener {
            showClearConfirmationDialog()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        historyDB.close()
    }

    /**
     * Handle toolbar back button click
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * Load history from database and display in RecyclerView
     */
    /**
     * Load history from database and display in RecyclerView
     */
    private fun loadHistory() {
        lifecycleScope.launch {
            val historyList = historyDB.getAllHistory()

            if (historyList.isEmpty()) {
                // Show empty state
                binding.historyRecyclerView.visibility = View.GONE
                binding.emptyHistoryText.visibility = View.VISIBLE
                binding.clearAllBtn.visibility = View.GONE
            } else {
                // Show history list
                binding.historyRecyclerView.visibility = View.VISIBLE
                binding.emptyHistoryText.visibility = View.GONE
                binding.clearAllBtn.visibility = View.VISIBLE

                binding.historyRecyclerView.layoutManager = LinearLayoutManager(this@HistoryActivity)
                binding.historyRecyclerView.setHasFixedSize(true)

                // Create adapter with click callback
                binding.historyRecyclerView.adapter = HistoryAdapter(historyList) { _ ->
                    // Just return to calculator without loading the expression
                    finish()
                }
            }
        }
    }


    private fun showClearConfirmationDialog() {
        lifecycleScope.launch {
            val historyList = historyDB.getAllHistory()
            if (historyList.isEmpty()) return@launch

            AlertDialog.Builder(this@HistoryActivity)
                .setTitle(getString(R.string.clear_history_title))
                .setMessage(getString(R.string.clear_history_message, historyList.size, if (historyList.size > 1) "s" else ""))
                .setPositiveButton(getString(R.string.clear)) { _, _ ->
                    clearHistory()
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
                .apply {
                    // Set button text color based on theme
                    val buttonColor = CalculatorUtils.getDialogButtonColor(this@HistoryActivity)
                    getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
                    getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
                }
        }
    }

    //Clear all history and refresh the display
    private fun clearHistory() {
        lifecycleScope.launch {
            historyDB.clearHistory()
            loadHistory() // Refresh display to show empty state
        }
    }
}
