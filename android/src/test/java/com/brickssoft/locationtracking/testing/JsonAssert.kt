package com.brickssoft.locationtracking.testing

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.fail
import kotlin.math.abs
import kotlin.math.max

/**
 * Structural JSON comparison (org.json needs Robolectric in unit tests). Key order is ignored, numbers are
 * compared numerically (1 == 1.0), and JSON null equals Kotlin null.
 */
object JsonAssert {
    fun assertJsonEquals(expected: String, actual: JSONObject) = assertJsonEquals(JSONObject(expected), actual)

    fun assertJsonEquals(expected: Any?, actual: Any?) {
        val problem = diff(expected, actual, "$") ?: return
        fail("$problem\nexpected: $expected\nactual:   $actual")
    }

    private fun diff(expected: Any?, actual: Any?, path: String): String? {
        val e = if (expected == JSONObject.NULL) null else expected
        val a = if (actual == JSONObject.NULL) null else actual
        return when {
            e == null || a == null -> if (e == null && a == null) null else "$path: expected $e but was $a"
            e is JSONObject && a is JSONObject -> {
                val eKeys = e.keys().asSequence().toSet()
                val aKeys = a.keys().asSequence().toSet()
                if (eKeys != aKeys) {
                    "$path: keys differ; missing ${eKeys - aKeys}, unexpected ${aKeys - eKeys}"
                } else {
                    eKeys.sorted().firstNotNullOfOrNull { k -> diff(e.opt(k), a.opt(k), "$path.$k") }
                }
            }
            e is JSONArray && a is JSONArray ->
                if (e.length() != a.length()) {
                    "$path: array length ${a.length()} != ${e.length()}"
                } else {
                    (0 until e.length()).firstNotNullOfOrNull { i -> diff(e.opt(i), a.opt(i), "$path[$i]") }
                }
            e is Number && a is Number -> {
                val x = e.toDouble()
                val y = a.toDouble()
                if (abs(x - y) <= 1e-9 * max(1.0, max(abs(x), abs(y)))) null else "$path: expected $x but was $y"
            }
            else -> if (e == a) null else "$path: expected $e (${e::class.simpleName}) but was $a (${a::class.simpleName})"
        }
    }
}
