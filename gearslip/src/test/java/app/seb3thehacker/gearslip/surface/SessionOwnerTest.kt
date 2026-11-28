package app.seb3thehacker.gearslip.surface

import app.seb3thehacker.gearslip.CarFocus
import app.seb3thehacker.gearslip.SessionStatus
import app.seb3thehacker.gearslip.surface.api.SessionPhase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Finding 5: session state has one mutation owner, and stale generations cannot write to it. */
class SessionOwnerTest {

    @Test fun `only the pinned generation may mutate session state`() {
        val owner = SessionOwner(7)

        assertTrue(owner.setPhase(7, SessionPhase.CONNECTING))
        assertTrue(owner.setProjected(7, false))
        assertEquals(SessionPhase.CONNECTING, owner.phase.value)
        assertFalse(owner.projected.value)

        assertFalse(owner.setPhase(6, SessionPhase.PROJECTING))
        assertFalse(owner.setProjected(8, true))
        assertEquals(SessionPhase.CONNECTING, owner.phase.value)
        assertEquals(2, owner.rejected.value)
    }

    @Test fun `a closed owner refuses every later mutation`() {
        val owner = SessionOwner(3)
        owner.close()

        assertFalse(owner.setPhase(3, SessionPhase.FAILED))
        assertFalse(owner.setProjected(3, false))
        assertEquals(SessionPhase.IDLE, owner.phase.value)
    }

    @Test fun `the exit handler is owned by the session and dies with it`() = runBlocking {
        var exits = 0
        val owner = SessionOwner(11)
        owner.setExitHandler(11) { exits++ }
        assertTrue(owner.requestExit(11))
        assertEquals(1, exits)

        owner.close()
        assertFalse(owner.requestExit(11))
        assertEquals(1, exits)
    }

    @Test fun `CarFocus delegates to the attached owner and ignores detached requests`() {
        val first = SessionOwner(1)
        val second = SessionOwner(2)
        var exits = 0

        CarFocus.attach(first)
        first.setExitHandler(1) { exits++ }
        CarFocus.exitToCar()
        assertEquals(1, exits)

        CarFocus.detach(first)
        CarFocus.exitToCar() // no owner: ignored
        assertEquals(1, exits)

        CarFocus.attach(second)
        second.setExitHandler(2) { exits++ }
        CarFocus.exitToCar()
        assertEquals(2, exits)

        // The closed first owner cannot reach the newer session's focus state.
        assertFalse(first.setProjected(1, false))
        CarFocus.detach(second)
        assertTrue(CarFocus.projected.value)
    }

    @Test fun `SessionStatus delegates phase to the attached owner`() {
        val owner = SessionOwner(9)
        SessionStatus.attach(owner)
        try {
            SessionStatus.connecting("handshake")
            assertEquals(SessionPhase.CONNECTING, owner.phase.value)
            SessionStatus.projecting()
            assertEquals(SessionPhase.PROJECTING, owner.phase.value)
        } finally {
            SessionStatus.detach(owner)
        }
    }

    @Test fun `focus state is observable through the owner`() = runBlocking {
        val owner = SessionOwner(5)
        owner.setProjected(5, false)
        assertEquals(false, owner.projected.first())
    }
}
