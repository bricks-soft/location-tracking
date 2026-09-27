package com.brickssoft.locationtracking.data

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import com.brickssoft.locationtracking.core.ErrorCode
import com.brickssoft.locationtracking.core.TrackingException
import org.json.JSONException
import kotlin.coroutines.cancellation.CancellationException

/** Maximum bound parameters per statement (old SQLite builds allow 999). */
internal const val MAX_SQL_PARAMS = 500

/** `?,?,...,?` with [count] placeholders. */
internal fun placeholders(count: Int): String = List(count) { "?" }.joinToString(",")

/** Runs [body] in an immediate transaction; commits if it returns normally, rolls back if it throws. */
internal inline fun <T> SQLiteDatabase.inTransaction(body: () -> T): T {
    beginTransactionNonExclusive()
    try {
        val result = body()
        setTransactionSuccessful()
        return result
    } finally {
        endTransaction()
    }
}

/** Deletes the rows whose [column] is one of [keys], in chunks of at most [MAX_SQL_PARAMS], in one transaction. */
internal fun SQLiteDatabase.deleteIn(table: String, column: String, keys: Collection<String>): Int = inTransaction {
    var deleted = 0
    for (chunk in keys.distinct().chunked(MAX_SQL_PARAMS)) {
        deleted += delete(table, "$column IN (${placeholders(chunk.size)})", chunk.toTypedArray())
    }
    deleted
}

/** A row whose JSON could not be decoded: its key and the exact text that was read. */
internal class UndecodableRow(val key: String, val json: String)

/**
 * Deletes each of [rows] only if its [jsonColumn] still holds the text that failed to decode, so a valid row
 * written concurrently under the same key survives. Returns the number of rows deleted.
 */
internal fun SQLiteDatabase.deleteUndecodable(
    table: String,
    keyColumn: String,
    jsonColumn: String,
    rows: List<UndecodableRow>,
): Int = inTransaction {
    rows.sumOf { delete(table, "$keyColumn = ? AND $jsonColumn = ?", arrayOf(it.key, it.json)) }
}

/** Compiles [sql], lets [bind] bind its arguments and runs it; returns the number of changed rows. */
internal inline fun SQLiteDatabase.executeUpdateDelete(sql: String, bind: SQLiteStatement.() -> Unit): Int =
    compileStatement(sql).use { statement ->
        statement.bind()
        statement.executeUpdateDelete()
    }

/** Runs [block] (JSON encoding); a `JSONException` (e.g. from NaN) becomes `TrackingException(INVALID_ARGUMENT)`. */
internal inline fun <T> encoding(what: String, block: () -> T): T = try {
    block()
} catch (e: JSONException) {
    throw TrackingException(ErrorCode.INVALID_ARGUMENT, "$what cannot be stored: ${e.message}", e)
}

/**
 * Runs [block] and converts any failure except cancellation and [TrackingException] into
 * `TrackingException(IO_ERROR)`, the only exception type that crosses component boundaries.
 */
internal inline fun <T> ioErrors(what: String, block: () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: TrackingException) {
    throw e
} catch (e: Exception) {
    throw TrackingException(ErrorCode.IO_ERROR, "$what failed: ${e.message ?: e.javaClass.simpleName}", e)
}
