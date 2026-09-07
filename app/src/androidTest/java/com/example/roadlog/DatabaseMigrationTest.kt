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
            db.execSQL(
                """
                INSERT INTO trip_data(
                    tripId,timestamp,latitude,longitude,speedKmh,eventCause,rawTimestamp
                ) VALUES(0,1750,27.71,85.31,4.0,'SIG',NULL)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO trip_data(tripId,timestamp,accelZ)
                VALUES(0,1200,9.8)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO trip_data(tripId,timestamp,gyroX)
                VALUES(0,1300,0.1)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO trip_data(tripId,timestamp,rotW)
                VALUES(0,1400,1.0)
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
                check(cursor.getInt(0) == 5)
            }
            db.query(
                "SELECT primaryCauseCode, provenance, tripId FROM trip_events"
            ).use { cursor ->
                check(cursor.moveToFirst())
                check(cursor.getString(0) == "SIGNAL")
                check(cursor.getString(1) == EventProvenance.LEGACY_IMPORTED)
                check(cursor.getLong(2) == 1L)
            }
            listOf("LOCATION", "ACCELEROMETER", "GYROSCOPE", "ROTATION", "EVENT").forEach { sourceType ->
                db.query(
                    "SELECT COUNT(*) FROM trip_data WHERE sourceType = ?",
                    arrayOf(sourceType)
                ).use { cursor ->
                    check(cursor.moveToFirst())
                    check(cursor.getInt(0) == 1)
                }
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

    @Test
    fun migrateV7ToV8CanonicalizesCausesAndRemovesNonPrimaryTables() {
        helper.createDatabase("migration-v7-protocol", 7).use { db ->
            db.execSQL(
                """
                INSERT INTO trips(
                    startTimeMs,endTimeMs,startNanoTime,endNanoTime,distanceMeters,eventCount,
                    gpsPointCount,accelPointCount,causeBreakdown,createdAt,status,tripUuid
                ) VALUES(1000,2000,0,0,10.0,1,1,1,'{"SIG":1,"POTHOLE":1}',1000,0,'trip-v7')
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO trip_events(
                    eventId,tripId,markerTimeMs,primaryCauseCode,confidenceCode,trafficState,
                    status,provenance,createdAt
                ) VALUES('event-v7',1,1500,'SIG',2,'QUEUED','PENDING','VOICE_RECOGNIZED',1500)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO event_secondary_causes(eventId,causeCode,codebookVersion,createdAt)
                VALUES('event-v7','BUS','2',1501)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO event_annotations(
                    annotationId,eventId,annotationVersion,annotationTimestampMs,primaryCauseCode,
                    secondaryCause1,secondaryCause2,codebookVersion,createdAt
                ) VALUES('annotation-v7','event-v7',1,1502,'SIG','BUS',NULL,'2',1502)
                """.trimIndent()
            )
        }

        helper.runMigrationsAndValidate(
            "migration-v7-protocol",
            8,
            true,
            MIGRATION_7_8
        ).use { db: SupportSQLiteDatabase ->
            db.query("SELECT causeBreakdown, exportFormatVersion, protocolVersion, exclusionCode FROM trips").use { cursor ->
                check(cursor.moveToFirst())
                check(cursor.getString(0).contains("ROUGH"))
                check(cursor.isNull(1))
                check(cursor.isNull(2))
                check(cursor.isNull(3))
            }
            db.query("SELECT provisionalCauseCode, primaryCauseCode FROM trip_events").use { cursor ->
                check(cursor.moveToFirst())
                check(cursor.getString(0) == "SIGNAL")
                check(cursor.getString(1) == "SIGNAL")
            }
            db.query("PRAGMA table_info(event_annotations)").use { cursor ->
                val columns = mutableSetOf<String>()
                while (cursor.moveToNext()) columns += cursor.getString(1)
                check("primaryCauseCode" in columns)
                check("secondaryCause1" !in columns)
                check("secondaryCause2" !in columns)
            }
            db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'event_secondary_causes'").use { cursor ->
                check(!cursor.moveToFirst())
            }
            db.query("SELECT COUNT(*) FROM audit_revisions WHERE eventId = 'event-v7'").use { cursor ->
                check(cursor.moveToFirst())
                check(cursor.getInt(0) == 2)
            }
        }
    }
}
