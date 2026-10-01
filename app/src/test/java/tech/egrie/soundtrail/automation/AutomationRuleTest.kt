package tech.egrie.soundtrail.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.egrie.soundtrail.data.GeoPoint

class MotionClassifierTest {
    @Test fun `classifies speed bands`() {
        assertEquals(MotionKind.STATIONARY, MotionClassifier.classify(0.0f))
        assertEquals(MotionKind.STATIONARY, MotionClassifier.classify(0.4f))
        assertEquals(MotionKind.WALKING, MotionClassifier.classify(0.5f))
        assertEquals(MotionKind.WALKING, MotionClassifier.classify(1.4f))
        assertEquals(MotionKind.RUNNING, MotionClassifier.classify(2.6f))
        assertEquals(MotionKind.RUNNING, MotionClassifier.classify(5.0f))
        assertEquals(MotionKind.DRIVING, MotionClassifier.classify(7.5f))
        assertEquals(MotionKind.DRIVING, MotionClassifier.classify(31.0f))
    }

    @Test fun `absent or invalid speed reads as stationary`() {
        assertEquals(MotionKind.STATIONARY, MotionClassifier.classify(Float.NaN))
        assertEquals(MotionKind.STATIONARY, MotionClassifier.classify(Float.POSITIVE_INFINITY))
        assertEquals(MotionKind.STATIONARY, MotionClassifier.classify(-1f))
    }
}

class AutomationRuleTest {
    private val place = GeoPoint(51.5, -0.1)
    private val rule = AutomationRule(
        id = "r1", name = "Café mix", playlistId = "p1",
        place = place, radiusMeters = 150.0,
        fromMinute = 9 * 60, toMinute = 11 * 60,
        cooldownMinutes = 15L
    )
    private val near = TriggerContext(MotionKind.STATIONARY, GeoPoint(51.5005, -0.1), 10 * 60)

    @Test fun `all set conditions must hold`() {
        assertTrue(rule.satisfiedBy(near))
        assertFalse(rule.satisfiedBy(near.copy(minuteOfDay = 12 * 60)))
        assertFalse(rule.satisfiedBy(near.copy(location = GeoPoint(52.0, -0.1))))
        assertFalse(rule.satisfiedBy(near.copy(location = null)))
    }

    @Test fun `time windows wrap midnight inclusively`() {
        assertTrue(AutomationRule.timeWindowContains(22 * 60, 22 * 60, 2 * 60))
        assertTrue(AutomationRule.timeWindowContains(23 * 60 + 50, 22 * 60, 2 * 60))
        assertTrue(AutomationRule.timeWindowContains(0, 22 * 60, 2 * 60))
        assertTrue(AutomationRule.timeWindowContains(2 * 60, 22 * 60, 2 * 60))
        assertFalse(AutomationRule.timeWindowContains(2 * 60 + 1, 22 * 60, 2 * 60))
        assertTrue(AutomationRule.timeWindowContains(10 * 60, 9 * 60, 11 * 60))
        assertFalse(AutomationRule.timeWindowContains(11 * 60 + 1, 9 * 60, 11 * 60))
        assertFalse(AutomationRule.timeWindowContains(600, -1, 20))
        assertFalse(AutomationRule.timeWindowContains(1440, 0, 1439))
    }

    @Test fun `activity rule matches the same motion only`() {
        val running = AutomationRule("r2", "Run set", "p1", activity = MotionKind.RUNNING)
        assertTrue(running.satisfiedBy(TriggerContext(MotionKind.RUNNING, null, 600)))
        assertFalse(running.satisfiedBy(TriggerContext(MotionKind.DRIVING, null, 600)))
        assertFalse(running.satisfiedBy(TriggerContext(MotionKind.STATIONARY, null, 600)))
    }

    @Test fun `disabled and condition-less rules never fire`() {
        assertFalse(rule.copy(enabled = false).satisfiedBy(near))
        val empty = AutomationRule("r3", "Empty", "p1")
        assertFalse(empty.satisfiedBy(near))
    }

    @Test fun `engine fires once then waits out the cooldown`() {
        val fired = mutableMapOf("r1" to 1_000_000L)
        val context = TriggerContext(MotionKind.STATIONARY, GeoPoint(51.5005, -0.1), 600)
        fun due(nowMs: Long) = AutomationEngine.due(listOf(rule), context, nowMs) { fired[it] }

        val cooldownMs = rule.cooldownMinutes * 60_000L
        assertTrue(due(1_000_000L + cooldownMs - 1).isEmpty())
        // Exactly one cooldown later, and any time after, the rule is due again.
        assertEquals(listOf(rule), due(1_000_000L + cooldownMs))
        assertEquals(listOf(rule), due(1_000_000L + cooldownMs + 1))
        // A rule that has never fired is due immediately.
        assertTrue(AutomationEngine.due(listOf(rule), context, 0L) { null }.isNotEmpty())
    }
}
