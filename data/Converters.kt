package com.example.myapplication.data

import androidx.room.TypeConverter
import org.json.JSONObject

object Converters {
    @TypeConverter
    @JvmStatic
    fun mapToJson(map: Map<String, String?>?): String? {
        if (map == null) return null
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        return obj.toString()
    }

    @TypeConverter
    @JvmStatic
    fun jsonToMap(json: String?): Map<String, String?>? {
        if (json == null) return null
        val obj = JSONObject(json)
        val keys = obj.keys()
        val map = mutableMapOf<String, String?>()
        while (keys.hasNext()) {
            val k = keys.next()
            map[k] = if (obj.isNull(k)) null else obj.optString(k, null)
        }
        return map
    }
}