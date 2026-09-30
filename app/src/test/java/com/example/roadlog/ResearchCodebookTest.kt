package com.example.roadlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchCodebookTest {
    @Test
    fun `v4 includes all requested additions while v3 codes remain available historically`() {
        assertEquals("4", ResearchCodebook.VERSION)
        assertEquals("3", ResearchVersions.LEGACY_MIGRATED_CODEBOOK_VERSION)
        assertEquals(10, ResearchCodebook.v3PrimaryCodes.size)
        assertEquals(18, ResearchCodebook.primaryCodes.size)
        assertTrue(ResearchCodebook.v3PrimaryCodes.all { it in ResearchCodebook.primaryCodes })
        assertTrue(
            setOf(
                "SLOW_LEAD_VEHICLE", "MERGING", "LEAD_TURN", "CROSSING_TURN",
                "PARKED_BIKE", "PARKED_CAR", "DELIVERY_STOP", "SPEED_BREAKER"
            ).all { it in ResearchCodebook.primaryCodes }
        )
        assertFalse(ResearchCodebook.primaryCodes.contains(TripExclusion.INCIDENT_OR_BREAKDOWN))
    }

    @Test
    fun `cause validity is taxonomy-version specific`() {
        assertNotNull(ResearchCodebook.primaryCodesForVersion("3"))
        assertTrue("TURNING" in ResearchCodebook.primaryCodesForVersion("3")!!)
        assertFalse("SPEED_BREAKER" in ResearchCodebook.primaryCodesForVersion("3")!!)
        assertTrue("SPEED_BREAKER" in ResearchCodebook.primaryCodesForVersion("4")!!)
        assertTrue("FRICTION" in ResearchCodebook.primaryCodesForVersion("4")!!)
        assertNull(ResearchCodebook.primaryCodesForVersion(null))
        assertNull(ResearchCodebook.primaryCodesForVersion("99"))
    }

    @Test
    fun `v4 definitions state evidence and residual boundaries`() {
        assertTrue(ResearchCodebook.v4Definitions.getValue("DELIVERY_STOP").contains("do not infer purpose"))
        assertTrue(ResearchCodebook.v4Definitions.getValue("SPEED_BREAKER").contains("ordinary manoeuvring alone"))
        assertTrue(ResearchCodebook.v4Definitions.getValue("TURNING").contains("own planned turn is excluded"))
        assertTrue(ResearchCodebook.v4Definitions.getValue("TURNING").contains("as MERGING"))
        assertTrue(ResearchCodebook.v4Definitions.getValue("FRICTION").contains("Vehicle interactions and road-surface defects are excluded"))
        assertTrue(ResearchCodebook.v4Definitions.getValue("FRICTION").contains("mere presence is insufficient"))
        assertEquals(10, ResearchCodebook.v4Definitions.size)
        assertTrue(ResearchCodebook.v3ResidualDefinitions.getValue("TURNING").contains("generic ‘turning’"))
        assertTrue(ResearchCodebook.v3ResidualDefinitions.getValue("FRICTION").contains("‘parked car’"))
    }

    @Test
    fun `cause presentation uses readable labels and version-qualified residual meanings`() {
        assertEquals("Slow lead vehicle", CauseDisplay.name("SLOW_LEAD_VEHICLE"))
        assertEquals("Speed breaker (v4)", CauseDisplay.versioned("SPEED_BREAKER", "4"))
        assertEquals("TURNING (v3 legacy)", CauseDisplay.versioned("TURNING", "3"))
        assertEquals("TURNING (v4 residual)", CauseDisplay.versioned("TURNING", "4"))
        assertTrue(CauseDisplay.versioned("FRICTION", null).contains("version uncertain"))
    }

    @Test
    fun `export cause normalization preserves new canonical categories`() {
        assertEquals("SIGNAL", CauseCodeMigration.toCanonicalOrKnownOrUnknown("SIG"))
        assertEquals("SPEED_BREAKER", CauseCodeMigration.toCanonicalOrKnownOrUnknown("speed_breaker"))
        assertEquals("PARKED_CAR", CauseCodeMigration.toCanonicalOrKnownOrUnknown("PARKED_CAR"))
        assertEquals("UNKNOWN", CauseCodeMigration.toCanonicalOrKnownOrUnknown("unrecognized legacy value"))
        assertEquals(null, CauseCodeMigration.toCanonicalOrKnownOrUnknown(null))
    }
}
