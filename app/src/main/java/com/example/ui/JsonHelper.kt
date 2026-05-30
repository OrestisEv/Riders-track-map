package com.example.ui

import com.example.data.Route
import com.example.data.RoutePoint
import com.example.data.TelemetrySample
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

object JsonHelper {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    fun telemetryToJson(samples: List<TelemetrySample>): String {
        val type = Types.newParameterizedType(List::class.java, TelemetrySample::class.java)
        return moshi.adapter<List<TelemetrySample>>(type).toJson(samples)
    }

    fun jsonToTelemetry(json: String): List<TelemetrySample> {
        if (json.isEmpty()) return emptyList()
        return try {
            val type = Types.newParameterizedType(List::class.java, TelemetrySample::class.java)
            moshi.adapter<List<TelemetrySample>>(type).fromJson(json) ?: emptyList()
        } catch (e: Exception) {
            android.util.Log.e("JsonHelper", "Failed parsing telemetry JSON", e)
            emptyList()
        }
    }

    fun routesToJson(routes: List<Route>): String {
        val type = Types.newParameterizedType(List::class.java, Route::class.java)
        return moshi.adapter<List<Route>>(type).toJson(routes)
    }

    fun pointsToJson(points: List<RoutePoint>): String {
        val type = Types.newParameterizedType(List::class.java, RoutePoint::class.java)
        return moshi.adapter<List<RoutePoint>>(type).toJson(points)
    }

    fun stringToJson(value: String): String {
        return moshi.adapter(String::class.java).toJson(value)
    }

    fun jsonToRoutes(json: String): List<Route>? {
        return try {
            val type = Types.newParameterizedType(List::class.java, Route::class.java)
            moshi.adapter<List<Route>>(type).fromJson(json)
        } catch (e: Exception) {
            android.util.Log.e("JsonHelper", "Failed parsing routes JSON", e)
            null
        }
    }
}
