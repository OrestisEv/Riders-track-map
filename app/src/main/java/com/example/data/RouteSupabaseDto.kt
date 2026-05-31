package com.example.data

import kotlinx.serialization.Serializable

@Serializable
data class RoutePointDto(val lat: Double, val lng: Double)

@Serializable
data class RouteSupabaseDto(
    val id: String,
    val user_id: String,
    val name: String,
    val date_ms: Long,
    val coordinates: List<RoutePointDto>,
    val distance_km: Double,
    val mode: String,
    val duration_seconds: Long = 0L,
    val max_speed: Double = 0.0,
    val avg_speed: Double = 0.0,
    val max_lean_angle: Double = 0.0,
    val max_g_force: Double = 0.0,
    val elevation_gain: Double = 0.0,
    val telemetry_json: String = ""
)

fun Route.toSupabaseDto(userId: String): RouteSupabaseDto {
    return RouteSupabaseDto(
        id = this.id,
        user_id = userId,
        name = this.name,
        date_ms = this.date,
        coordinates = this.coordinates.map { RoutePointDto(it.lat, it.lng) },
        distance_km = this.distance,
        mode = this.mode,
        duration_seconds = this.durationSeconds,
        max_speed = this.maxSpeed,
        avg_speed = this.avgSpeed,
        max_lean_angle = this.maxLeanAngle,
        max_g_force = this.maxGForce,
        elevation_gain = this.elevationGain,
        telemetry_json = this.telemetryJson
    )
}

fun RouteSupabaseDto.toRouteEntity(): Route {
    return Route(
        id = this.id,
        name = this.name,
        date = this.date_ms,
        coordinates = this.coordinates.map { RoutePoint(it.lat, it.lng) },
        distance = this.distance_km,
        mode = this.mode,
        durationSeconds = this.duration_seconds,
        maxSpeed = this.max_speed,
        avgSpeed = this.avg_speed,
        maxLeanAngle = this.max_lean_angle,
        maxGForce = this.max_g_force,
        elevationGain = this.elevation_gain,
        telemetryJson = this.telemetry_json
    )
}
