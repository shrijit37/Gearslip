package app.seb3thehacker.gearslip.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM coverage for the updater's generation and queued-install rules (finding 10). */
class UpdateDecisionTest {

    @Test fun `reset bumps generation and invalidates in-flight work`() {
        val decision = UpdateDecision()
        val observed = decision.generation
        assertTrue(decision.isCurrent(observed))

        decision.reset()

        assertEquals(observed + 1, decision.generation)
        assertFalse(decision.isCurrent(observed))
        assertTrue(decision.isCurrent(decision.generation))
    }

    @Test fun `reset clears both queued installs`() {
        val decision = UpdateDecision()
        decision.queueWhenAllowed()
        decision.queueWhenCarGone()

        decision.reset()

        assertFalse(decision.hasAllowed())
        assertFalse(decision.hasCarGone())
    }

    @Test fun `an allowed install is consumed exactly once`() {
        val decision = UpdateDecision()
        assertFalse(decision.consumeAllowed())

        decision.queueWhenAllowed()
        assertTrue(decision.hasAllowed())
        assertTrue(decision.consumeAllowed())
        assertFalse(decision.hasAllowed())
        assertFalse(decision.consumeAllowed())
    }

    @Test fun `a car-gone install survives an early return and is cleared when it proceeds`() {
        val decision = UpdateDecision()
        decision.queueWhenCarGone()
        // A channel change returns from install() before clearing the flag; the tap stays queued.
        assertTrue(decision.hasCarGone())
        assertTrue(decision.hasCarGone())

        decision.clearCarGone()
        assertFalse(decision.hasCarGone())
    }

    @Test fun `clearAll drops both queued installs without changing generation`() {
        val decision = UpdateDecision()
        val generation = decision.generation
        decision.queueWhenAllowed()
        decision.queueWhenCarGone()

        decision.clearAll()

        assertFalse(decision.hasAllowed())
        assertFalse(decision.hasCarGone())
        assertEquals(generation, decision.generation)
    }
}
