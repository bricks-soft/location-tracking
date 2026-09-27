package com.brickssoft.locationtracking.http

import com.brickssoft.locationtracking.config.HttpConfig
import com.brickssoft.locationtracking.core.Logger
import com.brickssoft.locationtracking.model.JsonUtil
import com.brickssoft.locationtracking.model.Record
import com.brickssoft.locationtracking.model.RecordJson
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds request bodies (architecture §2). Every record carries `sent_at`; a template, when configured, replaces
 * the default record shape.
 *
 * - Single record (`batchSync:false`): `{"<rootProperty>": {...}, ...params}`.
 * - Batch (`batchSync:true`): `{"<rootProperty>": [{...}, ...], ...params}`, records in the given order.
 * - `rootProperty` "." (or blank): a single record's fields are merged into the root together with params; a batch
 *   is a bare JSON array and params are ignored.
 *
 * Params never overwrite a key the body already has (the record data wins).
 */
internal object BodyBuilder {
    private const val TAG = "LT.Http"

    /** The `rootProperty` value meaning "no wrapping". */
    const val MERGE_ROOT = "."

    /** Body for [records]: a batch if `http.batchSync`, otherwise [records] must hold exactly one record. */
    fun build(records: List<Record>, http: HttpConfig, sentAt: Long): String =
        if (http.batchSync) {
            batch(records, http, sentAt)
        } else {
            require(records.size == 1) { "a non-batch body holds exactly one record, got ${records.size}" }
            single(records[0], http, sentAt)
        }

    fun single(record: Record, http: HttpConfig, sentAt: Long): String {
        val value = recordValue(record, http, sentAt)
        if (!isMerge(http)) return withParams(JSONObject().put(http.rootProperty, value), http).toString()
        // A template may render an array, which cannot be merged: send it bare.
        return if (value is JSONObject) withParams(value, http).toString() else value.toString()
    }

    fun batch(records: List<Record>, http: HttpConfig, sentAt: Long): String {
        val array = JSONArray()
        for (record in records) array.put(recordValue(record, http, sentAt))
        if (isMerge(http)) return array.toString()
        return withParams(JSONObject().put(http.rootProperty, array), http).toString()
    }

    /** The rendered template for [record] if one applies and renders valid JSON, else `RecordJson.toJson`. */
    fun recordValue(record: Record, http: HttpConfig, sentAt: Long): Any {
        val template = TemplateRenderer.templateFor(record, http)
        if (template != null) TemplateRenderer.render(template, record, sentAt)?.let { return it }
        return RecordJson.toJson(record, sentAt)
    }

    private fun isMerge(http: HttpConfig): Boolean = http.rootProperty.isBlank() || http.rootProperty == MERGE_ROOT

    private fun withParams(body: JSONObject, http: HttpConfig): JSONObject {
        val params = JsonUtil.parseObject(http.params)
        if (params == null) {
            if (http.params.isNotBlank()) Logger.w(TAG, "http.params is not a JSON object; ignored")
            return body
        }
        val keys = params.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!body.has(key)) body.put(key, params.get(key))
        }
        return body
    }
}
