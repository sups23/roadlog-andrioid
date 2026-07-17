package com.example.roadlog

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database for persistent trip backup and history viewing.
 */

@Entity(
    tableName = "trip_data",
    indices = [Index(value = ["tripId"])]
)
data class TripData(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long = 0,
    val timestamp: Long,
    val latitude: Double?,
    val longitude: Double?,
    val speedKmh: Float?,
    val accelX: Float? = null,
    val accelY: Float? = null,
    val accelZ: Float? = null,
    val gyroX: Float? = null,
    val gyroY: Float? = null,
    val gyroZ: Float? = null,
    val rotX: Float? = null,
    val rotY: Float? = null,
    val rotZ: Float? = null,
    val rotW: Float? = null,
    val eventCause: String?,
    val rawTimestamp: Long? = null
)

object TripStatus {
    const val COMPLETED = 0
    const val RECORDING = 1
    const val ABANDONED = 2
}

@Entity(tableName = "trips")
data class Trip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val startNanoTime: Long = 0,
    val endNanoTime: Long = 0,
    val distanceMeters: Double,
    val eventCount: Int,
    val gpsPointCount: Int,
    val accelPointCount: Int,
    val causeBreakdown: String,
    val createdAt: Long,
    val status: Int = TripStatus.COMPLETED
)

@Entity(
    tableName = "trip_photos",
    indices = [Index(value = ["tripId"])]
)
data class TripPhoto(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val timestamp: Long,
    val latitude: Double?,
    val longitude: Double?,
    val filePath: String
)

@Dao
interface TripDao {
    @Insert
    suspend fun insertAll(rows: List<TripData>)

    @Insert
    suspend fun insertTrip(trip: Trip): Long

    @Query("SELECT * FROM trip_data ORDER BY timestamp")
    suspend fun getAll(): List<TripData>

    @Query("DELETE FROM trip_data")
    suspend fun deleteAll()

    @Query("SELECT * FROM trips WHERE status = ${TripStatus.COMPLETED} ORDER BY startTimeMs DESC")
    suspend fun getAllTrips(): List<Trip>

    @Query("SELECT * FROM trips WHERE id = :tripId LIMIT 1")
    suspend fun getTripById(tripId: Long): Trip?

    @Query("SELECT id FROM trips WHERE startTimeMs = :startMs LIMIT 1")
    suspend fun getTripIdByStartTime(startMs: Long): Long?

    @Query("SELECT * FROM trip_data WHERE timestamp BETWEEN :fromMs AND :toMs AND latitude IS NOT NULL AND longitude IS NOT NULL ORDER BY timestamp")
    suspend fun getGpsForTimeRange(fromMs: Long, toMs: Long): List<TripData>

    @Query("SELECT * FROM trip_data WHERE timestamp BETWEEN :fromMs AND :toMs AND eventCause IS NOT NULL ORDER BY timestamp")
    suspend fun getEventsForTimeRange(fromMs: Long, toMs: Long): List<TripData>

    @Query("SELECT * FROM trip_data WHERE (tripId = :tripId OR tripId = 0) AND timestamp BETWEEN :fromMs AND :toMs AND latitude IS NOT NULL AND longitude IS NOT NULL ORDER BY timestamp")
    suspend fun getGpsForTrip(tripId: Long, fromMs: Long, toMs: Long): List<TripData>

    @Query("SELECT * FROM trip_data WHERE (tripId = :tripId OR tripId = 0) AND timestamp BETWEEN :fromMs AND :toMs AND eventCause IS NOT NULL ORDER BY timestamp")
    suspend fun getEventsForTrip(tripId: Long, fromMs: Long, toMs: Long): List<TripData>

    @Query("SELECT * FROM trip_data WHERE (tripId = :tripId OR tripId = 0) AND timestamp BETWEEN :fromMs AND :toMs AND accelZ IS NOT NULL ORDER BY timestamp")
    suspend fun getAccelForTrip(tripId: Long, fromMs: Long, toMs: Long): List<TripData>

    @Query("SELECT * FROM trip_data WHERE (tripId = :tripId OR tripId = 0) AND timestamp BETWEEN :fromMs AND :toMs AND gyroX IS NOT NULL ORDER BY timestamp")
    suspend fun getGyroForTrip(tripId: Long, fromMs: Long, toMs: Long): List<TripData>

    @Query("SELECT * FROM trip_data WHERE (tripId = :tripId OR tripId = 0) AND timestamp BETWEEN :fromMs AND :toMs AND rotW IS NOT NULL ORDER BY timestamp")
    suspend fun getRotationForTrip(tripId: Long, fromMs: Long, toMs: Long): List<TripData>

    @Query("SELECT * FROM trip_data WHERE (tripId = :tripId OR tripId = 0) AND timestamp BETWEEN :fromMs AND :toMs AND latitude IS NOT NULL AND longitude IS NOT NULL ORDER BY timestamp LIMIT :limit")
    suspend fun getGpsForTripCapped(tripId: Long, fromMs: Long, toMs: Long, limit: Int): List<TripData>

    @Query("SELECT * FROM trip_data WHERE (tripId = :tripId OR tripId = 0) AND timestamp BETWEEN :fromMs AND :toMs AND accelZ IS NOT NULL ORDER BY timestamp LIMIT :limit")
    suspend fun getAccelForTripCapped(tripId: Long, fromMs: Long, toMs: Long, limit: Int): List<TripData>

    @Query("SELECT * FROM trip_data WHERE (tripId = :tripId OR tripId = 0) AND timestamp BETWEEN :fromMs AND :toMs AND gyroX IS NOT NULL ORDER BY timestamp LIMIT :limit")
    suspend fun getGyroForTripCapped(tripId: Long, fromMs: Long, toMs: Long, limit: Int): List<TripData>

    @Query("SELECT * FROM trip_data WHERE (tripId = :tripId OR tripId = 0) AND timestamp BETWEEN :fromMs AND :toMs AND rotW IS NOT NULL ORDER BY timestamp LIMIT :limit")
    suspend fun getRotationForTripCapped(tripId: Long, fromMs: Long, toMs: Long, limit: Int): List<TripData>

    @Query("DELETE FROM trips WHERE id = :tripId")
    suspend fun deleteTrip(tripId: Long)

    @Transaction
    suspend fun deleteTripCascade(tripId: Long) {
        deletePhotosForTrip(tripId)
        deleteTripDataForTrip(tripId)
        deleteTrip(tripId)
    }

    @Transaction
    suspend fun finalizeTrip(
        tripId: Long,
        endTimeMs: Long,
        endNanoTime: Long,
        distanceMeters: Double,
        eventCount: Int,
        gpsPointCount: Int,
        accelPointCount: Int,
        causeBreakdown: String,
        createdAt: Long
    ) {
        updateTripSummary(tripId, endTimeMs, endNanoTime, distanceMeters, eventCount, gpsPointCount, accelPointCount, causeBreakdown, createdAt)
        markTripCompleted(tripId)
    }

    @Query("""
        UPDATE trips SET
            endTimeMs = :endTimeMs,
            endNanoTime = :endNanoTime,
            distanceMeters = :distanceMeters,
            eventCount = :eventCount,
            gpsPointCount = :gpsPointCount,
            accelPointCount = :accelPointCount,
            causeBreakdown = :causeBreakdown,
            createdAt = :createdAt
        WHERE id = :tripId
    """)
    suspend fun updateTripSummary(
        tripId: Long,
        endTimeMs: Long,
        endNanoTime: Long,
        distanceMeters: Double,
        eventCount: Int,
        gpsPointCount: Int,
        accelPointCount: Int,
        causeBreakdown: String,
        createdAt: Long
    )

    @Query("UPDATE trips SET status = ${TripStatus.COMPLETED} WHERE id = :tripId")
    suspend fun markTripCompleted(tripId: Long)

    @Query("SELECT * FROM trips WHERE status = ${TripStatus.RECORDING}")
    suspend fun getAbandonedTrips(): List<Trip>

    @Query("DELETE FROM trip_data WHERE tripId = :tripId")
    suspend fun deleteTripDataForTrip(tripId: Long)

    @Insert
    suspend fun insertPhoto(photo: TripPhoto): Long

    @Query("SELECT * FROM trip_photos WHERE tripId = :tripId ORDER BY timestamp ASC")
    suspend fun getPhotosForTrip(tripId: Long): List<TripPhoto>

    @Query("DELETE FROM trip_photos WHERE tripId = :tripId")
    suspend fun deletePhotosForTrip(tripId: Long)

    @Update
    suspend fun updatePhoto(photo: TripPhoto)
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS trips (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                startTimeMs INTEGER NOT NULL,
                endTimeMs INTEGER NOT NULL,
                distanceMeters REAL NOT NULL,
                eventCount INTEGER NOT NULL,
                gpsPointCount INTEGER NOT NULL,
                accelPointCount INTEGER NOT NULL,
                causeBreakdown TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE trip_data ADD COLUMN tripId INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE trip_data ADD COLUMN rawTimestamp INTEGER")
        db.execSQL("ALTER TABLE trips ADD COLUMN startNanoTime INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE trips ADD COLUMN endNanoTime INTEGER NOT NULL DEFAULT 0")

        backfillLegacyAccelData(db)
    }

    /**
     * Best-effort backfill for accelerometer rows recorded before the v3 schema.
     *
     * Old rows were stored with raw System.nanoTime() in the `timestamp` column and
     * had no trip association. Because the old LoggerService inserted accelerometer
     * rows before GPS rows for each trip, we can locate an old trip's accel block as
     * the accel rows whose `id` lies between the previous trip's GPS block and the
     * current trip's first GPS row.
     */
    private fun backfillLegacyAccelData(db: SupportSQLiteDatabase) {
        // Load all existing trips sorted by start time.
        val trips = mutableListOf<TripBounds>()
        db.query("SELECT id, startTimeMs, endTimeMs FROM trips ORDER BY startTimeMs ASC").use { cursor ->
            while (cursor.moveToNext()) {
                trips.add(
                    TripBounds(
                        tripId = cursor.getLong(0),
                        startMs = cursor.getLong(1),
                        endMs = cursor.getLong(2)
                    )
                )
            }
        }
        if (trips.isEmpty()) return

        // Load all old GPS rows once. Their `id` order matches insertion order.
        val gpsRows = mutableListOf<GpsRow>()
        db.query(
            "SELECT id, timestamp FROM trip_data WHERE tripId = 0 AND latitude IS NOT NULL AND longitude IS NOT NULL ORDER BY id ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                gpsRows.add(GpsRow(id = cursor.getLong(0), timestamp = cursor.getLong(1)))
            }
        }
        if (gpsRows.isEmpty()) return

        // Assign GPS rows to trips by timestamp to obtain per-trip id bounds.
        var tripIndex = 0
        for (gps in gpsRows) {
            while (tripIndex < trips.size && gps.timestamp > trips[tripIndex].endMs) {
                tripIndex++
            }
            if (tripIndex >= trips.size) break
            val bounds = trips[tripIndex]
            if (gps.timestamp in bounds.startMs..bounds.endMs) {
                if (bounds.minGpsId == null || gps.id < bounds.minGpsId!!) {
                    bounds.minGpsId = gps.id
                }
                if (bounds.maxGpsId == null || gps.id > bounds.maxGpsId!!) {
                    bounds.maxGpsId = gps.id
                }
            }
        }

        // Convert the accel block for each trip from nanotime to wall-clock ms.
        var previousMaxId: Long = 0
        for (bounds in trips) {
            val minId = bounds.minGpsId ?: continue
            val lowerId = previousMaxId + 1
            val upperId = minId - 1
            if (lowerId > upperId) {
                previousMaxId = bounds.maxGpsId ?: minId
                continue
            }

            val nanoRange = db.accelNanoRange(lowerId, upperId)
            if (nanoRange == null) {
                previousMaxId = bounds.maxGpsId ?: minId
                continue
            }
            val (startNano, endNano) = nanoRange

            db.execSQL(
                "UPDATE trip_data SET rawTimestamp = timestamp, tripId = ?, timestamp = ? + ((timestamp - ?) / 1000000) WHERE tripId = 0 AND accelZ IS NOT NULL AND id >= ? AND id <= ?",
                arrayOf<Any?>(
                    bounds.tripId,
                    bounds.startMs,
                    startNano,
                    lowerId,
                    upperId
                )
            )

            db.execSQL(
                "UPDATE trips SET startNanoTime = ?, endNanoTime = ? WHERE id = ?",
                arrayOf<Any?>(startNano, endNano, bounds.tripId)
            )

            previousMaxId = bounds.maxGpsId ?: minId
        }
    }

    private fun SupportSQLiteDatabase.accelNanoRange(lowerId: Long, upperId: Long): Pair<Long, Long>? {
        query(
            "SELECT MIN(timestamp), MAX(timestamp) FROM trip_data WHERE tripId = 0 AND accelZ IS NOT NULL AND id >= ? AND id <= ?",
            arrayOf<Any?>(lowerId, upperId)
        ).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0) && !cursor.isNull(1)) {
                return cursor.getLong(0) to cursor.getLong(1)
            }
        }
        return null
    }

}

private data class TripBounds(
    val tripId: Long,
    val startMs: Long,
    val endMs: Long,
    var minGpsId: Long? = null,
    var maxGpsId: Long? = null
)

private data class GpsRow(val id: Long, val timestamp: Long)

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE trip_data ADD COLUMN accelX REAL")
        db.execSQL("ALTER TABLE trip_data ADD COLUMN accelY REAL")
        db.execSQL("ALTER TABLE trip_data ADD COLUMN gyroX REAL")
        db.execSQL("ALTER TABLE trip_data ADD COLUMN gyroY REAL")
        db.execSQL("ALTER TABLE trip_data ADD COLUMN gyroZ REAL")
        db.execSQL("ALTER TABLE trip_data ADD COLUMN rotX REAL")
        db.execSQL("ALTER TABLE trip_data ADD COLUMN rotY REAL")
        db.execSQL("ALTER TABLE trip_data ADD COLUMN rotZ REAL")
        db.execSQL("ALTER TABLE trip_data ADD COLUMN rotW REAL")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS trip_photos (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                tripId INTEGER NOT NULL,
                timestamp INTEGER NOT NULL,
                latitude REAL,
                longitude REAL,
                filePath TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_trip_photos_tripId ON trip_photos(tripId)")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE trips ADD COLUMN status INTEGER NOT NULL DEFAULT ${TripStatus.COMPLETED}")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_trip_data_tripId ON trip_data(tripId)")
    }
}

@Database(entities = [TripData::class, Trip::class, TripPhoto::class], version = 6)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "roadlog_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
