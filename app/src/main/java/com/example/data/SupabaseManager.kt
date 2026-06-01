package com.example.data

import android.content.Context
import com.example.BuildConfig
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.channel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.IOException

object SupabaseManager {
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        DebugLogger.e("COROUTINE_ERROR", "Unhandled exception in Supabase Scope", exception)
    }
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob() + exceptionHandler)

    // Supabase client instance with robust url parsing and exception resilience
    val supabase = try {
        val url = if (BuildConfig.SUPABASE_URL.isNotBlank() && (BuildConfig.SUPABASE_URL.startsWith("http://") || BuildConfig.SUPABASE_URL.startsWith("https://"))) {
            BuildConfig.SUPABASE_URL
        } else {
            "https://placeholder-project.supabase.co"
        }
        val key = if (BuildConfig.SUPABASE_ANON_KEY.isNotBlank()) BuildConfig.SUPABASE_ANON_KEY else "dummy-key"
        
        createSupabaseClient(
            supabaseUrl = url,
            supabaseKey = key
        ) {
            install(Auth)
            install(Postgrest)
            install(Realtime)
        }
    } catch (e: Exception) {
        createSupabaseClient(
            supabaseUrl = "https://placeholder-project.supabase.co",
            supabaseKey = "dummy-key"
        ) {
            install(Auth)
            install(Postgrest)
            install(Realtime)
        }
    }

    private lateinit var database: AppDatabase
    private lateinit var sharedPrefs: android.content.SharedPreferences

    private val _sessionState = MutableStateFlow<SessionStatus>(SessionStatus.NotAuthenticated(isSignOut = false))
    val sessionState: StateFlow<SessionStatus> = _sessionState.asStateFlow()

    private var realtimeJob: kotlinx.coroutines.Job? = null
    private const val QUEUE_PREF_KEY = "pending_supabase_operations"

    fun initialize(context: Context) {
        database = AppDatabase.getDatabase(context)
        sharedPrefs = context.applicationContext.getSharedPreferences("roadtracker_supabase_queue", Context.MODE_PRIVATE)

        // Observe session state changes safely
        coroutineScope.launch {
            try {
                supabase.auth.sessionStatus.collect { status ->
                    _sessionState.value = status
                    if (status is SessionStatus.Authenticated) {
                        val userId = status.session.user?.id
                        if (userId != null) {
                            DebugLogger.sys("SUPABASE", "User session established: '${status.session.user?.email}' ($userId)")
                            // Trigger bi-directional sync and subscribe safely
                            try {
                                syncFromCloudToRoom(userId)
                                startRealtimeSync(userId)
                                flushOfflineQueue(userId)
                            } catch (e: Exception) {
                                DebugLogger.e("SUPABASE", "Internal syncing flow exception occurred", e)
                            }
                        }
                    } else {
                        realtimeJob?.cancel()
                        realtimeJob = null
                    }
                }
            } catch (e: Exception) {
                DebugLogger.e("SUPABASE", "Exception initialized in sessionStatus collector", e)
            }
        }
    }

    val currentSessionOrNull: io.github.jan.supabase.auth.user.UserSession?
        get() = supabase.auth.currentSessionOrNull()

    fun getCurrentUserId(): String? {
        return supabase.auth.currentSessionOrNull()?.user?.id
    }

    fun getCurrentUserEmail(): String? {
        return supabase.auth.currentSessionOrNull()?.user?.email
    }

    fun getCurrentUserName(): String? {
        val user = supabase.auth.currentSessionOrNull()?.user ?: return null
        val metadata = user.userMetadata
        val name = metadata?.get("name")?.toString()?.trim { it == '"' }
            ?: metadata?.get("full_name")?.toString()?.trim { it == '"' }
        return name ?: user.email?.substringBefore("@")
    }

    suspend fun loginWithGoogle(idToken: String): Boolean {
        return try {
            DebugLogger.i("SUPABASE", "Signing in to Supabase via Google ID Token...")
            supabase.auth.signInWith(IDToken) {
                this.idToken = idToken
                this.provider = Google
            }
            true
        } catch (e: Exception) {
            DebugLogger.e("SUPABASE", "Supabase Google login failed", e)
            false
        }
    }

    suspend fun signOut() {
        try {
            DebugLogger.i("SUPABASE", "Signing out of Supabase...")
            supabase.auth.signOut()
            _sessionState.value = SessionStatus.NotAuthenticated(isSignOut = true)
        } catch (e: Exception) {
            DebugLogger.e("SUPABASE", "Supabase sign out failed", e)
        }
    }

    suspend fun syncFromCloudToRoom(userId: String) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                DebugLogger.i("SUPABASE", "Downloading remote routes for user: $userId")
                val remoteRoutes = supabase.from("routes")
                    .select {
                        filter {
                            eq("user_id", userId)
                        }
                    }
                    .decodeList<RouteSupabaseDto>()

                DebugLogger.sys("SUPABASE", "Downloaded ${remoteRoutes.size} remote routes. Upserting into local Room Cache...")
                for (dto in remoteRoutes) {
                    val route = dto.toRouteEntity()
                    database.routeDao().insertRoute(route)
                }
                DebugLogger.sys("SUPABASE", "Local database cache cache updated successfully.")
            } catch (e: Exception) {
                DebugLogger.e("SUPABASE", "Failed downloading remote routes from Supabase", e)
            }
        }
    }

    fun startRealtimeSync(userId: String) {
        realtimeJob?.cancel()
        realtimeJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                DebugLogger.sys("REALTIME", "Subscribing to Postgres Realtime changes on 'routes'")
                val channel = supabase.realtime.channel("routes_sync_channel")
                
                val changeFlow = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "routes"
                }

                // Actually join the channel
                channel.subscribe()

                changeFlow.collect { action ->
                    when (action) {
                        is PostgresAction.Insert -> {
                            try {
                                val dto = action.decodeRecord<RouteSupabaseDto>()
                                if (dto.user_id == userId) {
                                    DebugLogger.sys("REALTIME", "New remote route inserted: '${dto.name}'. Updating local Room DB.")
                                    database.routeDao().insertRoute(dto.toRouteEntity())
                                }
                            } catch (e: Exception) {
                                DebugLogger.e("REALTIME", "Error processing Realtime insert", e)
                            }
                        }
                        is PostgresAction.Delete -> {
                            try {
                                val recordId = action.oldRecord["id"]?.toString()?.trim { it == '"' }
                                if (recordId != null) {
                                    DebugLogger.sys("REALTIME", "Remote route deleted: ID '$recordId'. Removing from local Room DB.")
                                    database.routeDao().deleteRouteById(recordId)
                                }
                            } catch (e: Exception) {
                                DebugLogger.e("REALTIME", "Error processing Realtime delete", e)
                            }
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                DebugLogger.e("REALTIME", "Realtime workspace binding failed", e)
            }
        }
    }

    // --- Offline Queue Mechanism ---

    private fun getQueue(): Set<String> {
        return sharedPrefs.getStringSet(QUEUE_PREF_KEY, emptySet()) ?: emptySet()
    }

    private fun saveQueue(queue: Set<String>) {
        sharedPrefs.edit().putStringSet(QUEUE_PREF_KEY, queue).apply()
    }

    @Synchronized
    fun enqueueInsert(routeId: String) {
        val current = getQueue().toMutableSet()
        current.remove("delete:$routeId")
        current.add("insert:$routeId")
        saveQueue(current)
        DebugLogger.i("OFFLINE", "Queued offline insert transaction for routeId: $routeId")
    }

    @Synchronized
    fun enqueueDelete(routeId: String) {
        val current = getQueue().toMutableSet()
        current.remove("insert:$routeId")
        current.add("delete:$routeId")
        saveQueue(current)
        DebugLogger.i("OFFLINE", "Queued offline delete transaction for routeId: $routeId")
    }

    fun executeInsert(route: Route) {
        val userId = getCurrentUserId()
        if (userId == null) {
            enqueueInsert(route.id)
            return
        }
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val dto = route.toSupabaseDto(userId)
                supabase.from("routes").insert(dto)
                DebugLogger.sys("SUPABASE", "Route successfully saved directly to Supabase cloud: '${route.name}'")
            } catch (e: Exception) {
                DebugLogger.e("SUPABASE", "Direct Supabase save failed for '${route.name}'. Queuing for retry.", e)
                enqueueInsert(route.id)
            }
        }
    }

    fun executeDelete(routeId: String) {
        val userId = getCurrentUserId()
        if (userId == null) {
            enqueueDelete(routeId)
            return
        }
        coroutineScope.launch(Dispatchers.IO) {
            try {
                supabase.from("routes").delete {
                    filter {
                        eq("id", routeId)
                    }
                }
                DebugLogger.sys("SUPABASE", "Route successfully deleted directly from Supabase cloud: $routeId")
            } catch (e: Exception) {
                DebugLogger.e("SUPABASE", "Direct Supabase delete failed for ID $routeId. Queuing for retry.", e)
                enqueueDelete(routeId)
            }
        }
    }

    fun flushOfflineQueue(userId: String) {
        coroutineScope.launch(Dispatchers.IO) {
            val currentQueue = getQueue()
            if (currentQueue.isEmpty()) return@launch
            DebugLogger.sys("OFFLINE", "Attempting to sync ${currentQueue.size} pending offline operations...")

            val remaining = currentQueue.toMutableSet()
            for (op in currentQueue) {
                val parts = op.split(":", limit = 2)
                if (parts.size < 2) continue
                val action = parts[0]
                val routeId = parts[1]
                var success = false
                try {
                    if (action == "insert") {
                        val route = database.routeDao().getRouteById(routeId)
                        if (route != null) {
                            val dto = route.toSupabaseDto(userId)
                            supabase.from("routes").insert(dto)
                        }
                        success = true
                    } else if (action == "delete") {
                        supabase.from("routes").delete {
                            filter {
                                eq("id", routeId)
                            }
                        }
                        success = true
                    }
                } catch (e: Exception) {
                    DebugLogger.e("OFFLINE", "Failed syncing pending item: '$op'", e)
                    if (e is IOException || e.message?.contains("timeout") == true || e.message?.contains("connect") == true) {
                        DebugLogger.w("OFFLINE", "Persistent network issue detected. Queue sync paused.")
                        break
                    }
                    // For structural/authorization/bad-data failures, remove from queue to avoid blockages
                    success = true
                }
                if (success) {
                    remaining.remove(op)
                }
            }
            saveQueue(remaining)
            DebugLogger.sys("OFFLINE", "Sync sweep complete. Leftover pending items: ${remaining.size}")
        }
    }

    fun getPendingOperationsCount(): Int {
        return getQueue().size
    }
}
