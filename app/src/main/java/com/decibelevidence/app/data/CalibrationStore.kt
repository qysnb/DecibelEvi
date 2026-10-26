package com.decibelevidence.app.data

import android.content.Context
import com.decibelevidence.core.Calibration
import org.json.JSONObject

class CalibrationStore(context: Context) {
    private val prefs = context.getSharedPreferences("calibration", Context.MODE_PRIVATE)
    fun read(): Calibration? = runCatching {
        val json = JSONObject(prefs.getString("value", null) ?: return null)
        Calibration(json.getDouble("offsetDb"), json.getLong("createdAtMs"), json.getString("inputKey"), json.getDouble("referenceDb"))
    }.getOrNull()
    fun save(value: Calibration) { prefs.edit().putString("value", json(value).toString()).apply() }
    fun clear() { prefs.edit().remove("value").apply() }
    companion object {
        fun json(value: Calibration?): JSONObject? = value?.let {
            JSONObject().put("offsetDb", it.offsetDb).put("createdAtMs", it.createdAtMs)
                .put("inputKey", it.inputKey).put("referenceDb", it.referenceDb)
        }
    }
}
