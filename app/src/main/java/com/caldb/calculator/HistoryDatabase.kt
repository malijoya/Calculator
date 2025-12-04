package com.caldb.calculator

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

 // Database helper class for managing calculator history.
 // @param context The application context used to create/open the database
class HistoryDatabase(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, 1) {

    companion object {
        // Database configuration constants
        private const val DB_NAME = "history.db"              // Name of the database file
        private const val TABLE_HISTORY = "history"           // Name of the history table
        private const val COL_ID = "id"                       // Primary key column name
        private const val COL_ENTRY = "entry"                 // Column for storing calculation entries
    }


    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE_HISTORY (" +
                    "$COL_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "$COL_ENTRY TEXT NOT NULL)"
        )
    }


    //Called when the database needs to be upgraded.
    //@param db The database instance
    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        if (oldV < 2) {
            // Migration for version 2: Add timestamp column
            db.execSQL("ALTER TABLE history ADD COLUMN timestamp TEXT DEFAULT ''")
        }
    }

    suspend fun addHistory(entry: String, maxHistoryItems: Int) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val cv = ContentValues()
        cv.put(COL_ENTRY, entry)
        db.insert(TABLE_HISTORY, null, cv)

        // LIMIT MAX ITEMS: Delete oldest entries that exceed the maximum limit
        // This query keeps only the most recent 'maxHistoryItems' entries
        db.execSQL(
            "DELETE FROM $TABLE_HISTORY WHERE $COL_ID NOT IN " +
                    "(SELECT $COL_ID FROM $TABLE_HISTORY ORDER BY $COL_ID DESC LIMIT ?)",
            arrayOf(maxHistoryItems.toString())
        )
    }

     //return A list of calculation history entries, ordered from newest to oldest

    suspend fun getAllHistory(): List<String> = withContext(Dispatchers.IO) {
        val db = readableDatabase
        val list = mutableListOf<String>()

        // Query to get all entries ordered by ID in descending order (newest first)
        val cursor = db.rawQuery(
            "SELECT $COL_ENTRY FROM $TABLE_HISTORY ORDER BY $COL_ID DESC",
            null
        )

        // Iterate through cursor and add all entries to the list
        if (cursor.moveToFirst()) {
            do {
                list.add(cursor.getString(0))
            } while (cursor.moveToNext())
        }
        cursor.close()
        return@withContext list
    }


    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        writableDatabase.execSQL("DELETE FROM $TABLE_HISTORY")
    }
}
