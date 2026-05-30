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
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val listType = Types.newParameterizedType(List::class.java, RoutePoint::class.java)
    private val adapter = moshi.adapter<List<RoutePoint>>(listType)

    @TypeConverter
    fun fromString(value: String?): List<RoutePoint>? {
        return value?.let { adapter.fromJson(it) }
    }

    @TypeConverter
    fun toString(list: List<RoutePoint>?): String? {
        return list?.let { adapter.toJson(it) }
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
}

@Database(entities = [Route::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun routeDao(): RouteDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "roadtracker_database"
                )
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
