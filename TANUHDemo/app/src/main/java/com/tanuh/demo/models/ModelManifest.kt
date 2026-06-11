package com.tanuh.demo.models

import org.json.JSONObject

/** Versioned catalog downloaded from the model-hosting repository. */
data class ModelManifest(
    val schemaVersion: Int = 1,
    val models: List<ModelSpec>,
) {
    companion object {
        /** Parses the wire format and requires all integrity fields per model. */
        fun parse(json: String): ModelManifest {
            val root = JSONObject(json)
            val modelsJson = root.getJSONArray("models")
            val models = buildList {
                for (index in 0 until modelsJson.length()) {
                    val item = modelsJson.getJSONObject(index)
                    add(
                        ModelSpec(
                            id = item.getString("id"),
                            version = item.getString("version"),
                            runtime = item.getString("runtime"),
                            format = item.getString("format"),
                            url = item.getString("url"),
                            sha256 = item.getString("sha256"),
                            size = item.getLong("size"),
                        ),
                    )
                }
            }
            return ModelManifest(root.optInt("schemaVersion", 1), models)
        }
    }
}

/** Describes one immutable, integrity-addressed model artifact. */
data class ModelSpec(
    val id: String,
    val version: String,
    val runtime: String,
    val format: String,
    val url: String,
    val sha256: String,
    val size: Long,
)

/** A model that has passed verification and activation into app-private storage. */
data class InstalledModel(
    val spec: ModelSpec,
    val path: String,
)
