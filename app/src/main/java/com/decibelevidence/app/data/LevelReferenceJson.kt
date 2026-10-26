package com.decibelevidence.app.data

import com.decibelevidence.core.LevelReference
import com.decibelevidence.core.LevelReferenceMode
import org.json.JSONObject

object LevelReferenceJson {
    fun encode(reference: LevelReference): String = JSONObject()
        .put("mode", reference.mode.name)
        .put("offsetDb", reference.offsetDb)
        .put("inputKey", reference.inputKey)
        .put("source", reference.source)
        .put("basis", reference.basis)
        .toString()

    /** Missing legacy metadata or unrecognized/corrupt metadata has no inferred reference. */
    fun decode(json: String?): LevelReference? = runCatching {
        if (json.isNullOrBlank()) return null
        val value = JSONObject(json)
        LevelReference(
            mode = LevelReferenceMode.valueOf(value.getString("mode")),
            offsetDb = value.getDouble("offsetDb"),
            inputKey = value.optString("inputKey", ""),
            source = value.optString("source", ""),
            basis = value.optString("basis", ""),
        )
    }.getOrNull()
}
