package com.example.roadlog

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList()
    )

    @Test
    fun migrateV6ToV7PreservesTripAndBackfillsStableIds() {
        helper.createDatabase("migration-v6", 6).use { db ->
            db.execSQL(
                """
                INSERT INTO trips(
                    startTimeMs,endTimeMs,distanceMeters,eventCount,gpsPointCount,
                    accelPointCount,causeBreakdown,createdAt,status
                ) VALUES(1000,2000,10.0,0,1,1,'{}',1000,0)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO trip_data(
                    tripId,timestamp,latitude,longitude,speedKmh,eventCause,rawTimestamp
                ) VALUES(0,1500,27.7,85.3,10.0,NULL,NULL)
                """.trimIndent()
            )
        }

        helper.runMigrationsAndValidate(
            "migration-v6",
            7,
            true,
            MIGRATION_6_7
        ).use { db: SupportSQLiteDatabase ->
                db.query("SELECT tripUuid, sessionId, corridorId, studyDateLocal, qaStatus FROM trips").use { cursor ->
                    check(cursor.moveToFirst())
                    check(cursor.getString(0).isNotBlank())
                    check(cursor.getString(1) == ResearchStudy.SESSION_ID)
                    check(cursor.getString(2) == ResearchStudy.CORRIDOR_ID)
                    check(cursor.getString(4) == TripQaStatus.UNREVIEWED)
                }
            db.query("SELECT COUNT(*) FROM trip_data WHERE tripId = 1").use { cursor ->
                check(cursor.moveToFirst())
                check(cursor.getInt(0) == 1)
            }
            db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('sessions', 'corridors')").use { cursor ->
                check(!cursor.moveToFirst())
            }
            db.query("PRAGMA table_info(trips)").use { cursor ->
                val columns = mutableSetOf<String>()
                while (cursor.moveToNext()) columns += cursor.getString(1)
                check("passNumber" !in columns)
                check("observerId" !in columns)
                check("vehicleId" !in columns)
                check("mountPosition" !in columns)
                check("mountOrientation" !in columns)
            }
        }
    }
}
