package com.caldb.calculator

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class HistoryDatabase(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, 1) {

    companion object {
        private const val DB_NAME = "history.db"
        private const val TABLE_HISTORY = "history"
        private const val COL_ID = "id"
        private const val COL_ENTRY = "entry"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE_HISTORY (" +
                    "$COL_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "$COL_ENTRY TEXT NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_HISTORY")
        onCreate(db)
    }

    fun addHistory(entry: String, maxHistoryItems: Int) {
        val db = writableDatabase
        val cv = ContentValues()
        cv.put(COL_ENTRY, entry)
        db.insert(TABLE_HISTORY, null, cv)

        // LIMIT MAX ITEMS
        db.execSQL(
            "DELETE FROM $TABLE_HISTORY WHERE $COL_ID NOT IN " +
                    "(SELECT $COL_ID FROM $TABLE_HISTORY ORDER BY $COL_ID DESC LIMIT ?)",
            arrayOf(maxHistoryItems.toString())
        )
    }

    fun getAllHistory(): List<String> {
        val db = readableDatabase
        val list = mutableListOf<String>()

        val cursor = db.rawQuery(
            "SELECT $COL_ENTRY FROM $TABLE_HISTORY ORDER BY $COL_ID DESC",
            null
        )

        if (cursor.moveToFirst()) {
            do {
                list.add(cursor.getString(0))
            } while (cursor.moveToNext())
        }
        cursor.close()
        return list
    }

    fun clearHistory() {
        writableDatabase.execSQL("DELETE FROM $TABLE_HISTORY")
    }
}
