package com.memeget.nativeapp.cloud

import com.memeget.nativeapp.CloudConfig
import com.memeget.nativeapp.CloudLedger
import com.memeget.nativeapp.LedgerInFlightException
import com.memeget.nativeapp.LedgerLimitExceededException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LedgerStateMachineTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun reservationsChargeBeforeDeliveryAndAbortDoesNotRefund() {
        val root = directory.newFolder()
        val ledger = CloudLedger(root)
        val config = CloudConfig(dailyRequestLimit = 2, dailyByteLimit = 6000, dailyCostBudgetUsd = 0.01)
        val first = ledger.reserve(config, 3000, 0.002)
        assertTrue(ledger.getSnapshot(config).inFlight)
        assertThrows(LedgerInFlightException::class.java) { ledger.reserve(config, 1, 0.0) }
        ledger.abort(first)
        val restored = CloudLedger(root)
        assertEquals(3000L, restored.getSnapshot(config).bytesCount)
        assertEquals(0.002, restored.getSnapshot(config).costEstimateUsd, 0.000001)
        val second = restored.reserve(config, 3000, 0.002)
        restored.commit(second)
        assertFalse(restored.getSnapshot(config).inFlight)
        assertEquals(2, restored.getSnapshot(config).requestsCount)
        assertThrows(LedgerLimitExceededException::class.java) { restored.reserve(config, 1, 0.0) }
    }

    @Test fun byteAndCostCeilingsAreIndependentOfRequestCount() {
        val config = CloudConfig(dailyRequestLimit = 10, dailyByteLimit = 5000, dailyCostBudgetUsd = 0.01)
        val ledger = CloudLedger(directory.newFolder())
        ledger.commit(ledger.reserve(config, 4000, 0.009))
        assertThrows(LedgerLimitExceededException::class.java) { ledger.reserve(config, 1001, 0.0) }
        assertThrows(LedgerLimitExceededException::class.java) { ledger.reserve(config, 1, 0.002) }
        assertEquals(1, ledger.getSnapshot(config).requestsCount)
    }

    @Test fun independentInstancesCannotReleaseEachOthersLiveLease() {
        val root = directory.newFolder()
        val config = CloudConfig()
        val first = CloudLedger(root)
        val second = CloudLedger(root)
        val reservation = first.reserve(config, 1000, 0.001)
        try {
            second.abort(reservation)
            assertThrows(LedgerInFlightException::class.java) { second.reserve(config, 1000, 0.001) }
        } finally { first.abort(reservation) }
        second.commit(second.reserve(config, 1000, 0.001))
        assertEquals(2, second.getSnapshot(config).requestsCount)
    }

    @Test fun forwardPeriodResetsOnceAndRollbackCannotReplayTheBudget() {
        var now = 20_000L * 86_400_000
        val root = directory.newFolder()
        val config = CloudConfig(dailyRequestLimit = 1)
        val ledger = CloudLedger(root) { now }
        ledger.commit(ledger.reserve(config, 1000, 0.001))
        now += 86_400_000
        ledger.commit(ledger.reserve(config, 1000, 0.001))
        now -= 10 * 86_400_000L
        val reopened = CloudLedger(root) { now }
        assertThrows(LedgerLimitExceededException::class.java) { reopened.reserve(config, 1000, 0.001) }
        now += 10 * 86_400_000L
        assertThrows(LedgerLimitExceededException::class.java) { reopened.reserve(config, 1000, 0.001) }
        assertEquals(20_001L, reopened.getSnapshot(config).epochDay)
    }

    @Test fun unreadableBudgetCannotBeTreatedAsUnusedAllowance() {
        val root = directory.newFolder()
        val ledger = CloudLedger(root)
        val config = CloudConfig()
        ledger.commit(ledger.reserve(config, 1000, 0.001))
        File(root, "cloud_ledger.json").writeText("broken")
        assertThrows(Exception::class.java) { CloudLedger(root).reserve(config, 1000, 0.001) }
    }
}
