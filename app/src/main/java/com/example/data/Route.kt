package com.example.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.Flow

data class TelemetrySample(
    val timeSec: Long,
    val speedKmh: Double,
    val leanAngle: Double,
    val gForce: Double,
    val altitude: Double
)

data class RoutePoint(val lat: Double, val lng: Double)

@Entity(tableName = "routes")
data class Route(
    @PrimaryKey val id: String,
    val name: String,
    val date: Long, // timestamp
    val coordinates: List<RoutePoint>,
    val distance: Double, // in km
    val mode: String, // "gps" or "manual"
    val durationSeconds: Long = 0L,
    val maxSpeed: Double = 0.0,
    val avgSpeed: Double = 0.0,
    val maxLeanAngle: Double = 0.0,
    val maxGForce: Double = 0.0,
    val elevationGain: Double = 0.0,
    val telemetryJson: String = ""
)

class Converters {
    @TypeConverter
    fun fromString(value: String?): List<RoutePoint>? {
        if (value.isNullOrEmpty()) return emptyList()
        return try {
            val jsonArray = org.json.JSONArray(value)
            val list = mutableListOf<RoutePoint>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(RoutePoint(obj.getDouble("lat"), obj.getDouble("lng")))
            }
            list
        } catch (e: Exception) {
            android.util.Log.e("Converters", "Failed parsing coordinates JSON structure", e)
            emptyList()
        }
    }

    @TypeConverter
    fun toString(list: List<RoutePoint>?): String? {
        if (list == null) return null
        return try {
            val jsonArray = org.json.JSONArray()
            for (point in list) {
                val obj = org.json.JSONObject()
                obj.put("lat", point.lat)
                obj.put("lng", point.lng)
                jsonArray.put(obj)
            }
            jsonArray.toString()
        } catch (e: Exception) {
            android.util.Log.e("Converters", "Failed serializing coordinates to JSON", e)
            "[]"
        }
    }
}

@Dao
interface RouteDao {
    @Query("SELECT * FROM routes ORDER BY date DESC")
    fun getAllRoutes(): Flow<List<Route>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoute(route: Route)

    @Query("DELETE FROM routes WHERE id = :id")
    suspend fun deleteRouteById(id: String)

    @Query("SELECT * FROM routes WHERE id = :id LIMIT 1")
    suspend fun getRouteById(id: String): Route?
}

@Database(entities = [Route::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun routeDao(): RouteDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE routes ADD COLUMN durationSeconds INTEGER NOT NULL DEFAULT 0")
                } catch (e: Exception) {
                    android.util.Log.w("AppDatabase", "Column durationSeconds may already exist: ${e.message}")
                }
                try {
                    db.execSQL("ALTER TABLE routes ADD COLUMN maxSpeed REAL NOT NULL DEFAULT 0.0")
                } catch (e: Exception) {
                    android.util.Log.w("AppDatabase", "Column maxSpeed may already exist: ${e.message}")
                }
                try {
                    db.execSQL("ALTER TABLE routes ADD COLUMN avgSpeed REAL NOT NULL DEFAULT 0.0")
                } catch (e: Exception) {
                    android.util.Log.w("AppDatabase", "Column avgSpeed may already exist: ${e.message}")
                }
                try {
                    db.execSQL("ALTER TABLE routes ADD COLUMN maxLeanAngle REAL NOT NULL DEFAULT 0.0")
                } catch (e: Exception) {
                    android.util.Log.w("AppDatabase", "Column maxLeanAngle may already exist: ${e.message}")
                }
                try {
                    db.execSQL("ALTER TABLE routes ADD COLUMN maxGForce REAL NOT NULL DEFAULT 0.0")
                } catch (e: Exception) {
                    android.util.Log.w("AppDatabase", "Column maxGForce may already exist: ${e.message}")
                }
                try {
                    db.execSQL("ALTER TABLE routes ADD COLUMN elevationGain REAL NOT NULL DEFAULT 0.0")
                } catch (e: Exception) {
                    android.util.Log.w("AppDatabase", "Column elevationGain may already exist: ${e.message}")
                }
                try {
                    db.execSQL("ALTER TABLE routes ADD COLUMN telemetryJson TEXT NOT NULL DEFAULT ''")
                } catch (e: Exception) {
                    android.util.Log.w("AppDatabase", "Column telemetryJson may already exist: ${e.message}")
                }
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "roadtracker_database"
                )
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class RouteRepository(private val routeDao: RouteDao) {
    val allRoutes: Flow<List<Route>> = routeDao.getAllRoutes()

    suspend fun insert(route: Route) {
        routeDao.insertRoute(route)
    }

    suspend fun delete(id: String) {
        routeDao.deleteRouteById(id)
    }
}
