package io.github.iamjosephmj.bench

import org.json.JSONArray
import org.json.JSONObject

object Report {
    fun toJson(records: List<RunRecord>, deviceInfo: Map<String, String>): String {
        val root = JSONObject()
        root.put("device", JSONObject(deviceInfo))
        val arr = JSONArray()
        for (r in records) {
            arr.put(JSONObject().apply {
                put("itemId", r.itemId); put("backend", r.backend)
                put("enqueuedAt", r.enqueuedAt)
                put("firstStartAt", r.firstStartAt); put("completedAt", r.completedAt)
                put("attempts", r.attempts); put("chunksReplayed", r.chunksReplayed)
                put("timeToFirstStartMs",
                    r.firstStartAt?.let { it - r.enqueuedAt })
                put("timeToCompleteMs",
                    r.completedAt?.let { it - r.enqueuedAt })
            })
        }
        root.put("records", arr)
        return root.toString()
    }
}
