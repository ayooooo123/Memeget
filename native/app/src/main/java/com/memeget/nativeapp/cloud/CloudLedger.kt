package com.memeget.nativeapp

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal data class LedgerReservation(val id: String, val epochDay: Long, val estimatedBytes: Long, val estimatedCostUsd: Double, val timestamp: Long)
internal data class LedgerSnapshot(
    val epochDay: Long, val requestsCount: Int, val maxRequests: Int,
    val bytesCount: Long, val maxBytes: Long, val costEstimateUsd: Double,
    val maxCostUsd: Double, val inFlight: Boolean,
)
internal class LedgerInFlightException(message: String) : IllegalStateException(message)
internal class LedgerLimitExceededException(message: String) : IllegalStateException(message)

internal fun writeCloudAtomic(file: File, bytes: ByteArray) {
    file.parentFile?.mkdirs()
    val temporary = File(file.parentFile, file.name + ".tmp")
    RandomAccessFile(temporary, "rw").use { output ->
        output.setLength(0)
        output.write(bytes)
        output.fd.sync()
    }
    Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}

/** The OS lock spans the HTTP call; persisted reservations survive process death without refunds. */
internal class CloudLedger(private val storageDir: File, private val timeProvider: () -> Long = { System.currentTimeMillis() }) {
    private val file = File(storageDir, "cloud_ledger.json")
    private val lockFile = File(storageDir, "cloud_ledger.lock")
    private var active: Held? = null
    private data class Held(val id: String, val channel: FileChannel, val lock: FileLock)
    private data class State(var day: Long, var requests: Int = 0, var bytes: Long = 0, var cost: Double = 0.0, var pending: String = "")

    init { require(storageDir.isDirectory || storageDir.mkdirs()) { "Cannot create cloud budget storage" } }

    @Synchronized
    fun getSnapshot(config: CloudConfig): LedgerSnapshot {
        val state = load()
        return LedgerSnapshot(state.day, state.requests, config.dailyRequestLimit, state.bytes,
            config.dailyByteLimit, state.cost, config.dailyCostBudgetUsd, active != null)
    }

    @Synchronized
    fun reserve(config: CloudConfig, estimatedBytes: Long, estimatedCostUsd: Double): LedgerReservation {
        require(estimatedBytes > 0 && estimatedBytes <= CloudPayloadPrep.MAX_REQUEST_BYTES + CloudPayloadPrep.MAX_RESPONSE_BYTES) { "Invalid bounded request reservation" }
        require(estimatedCostUsd.isFinite() && estimatedCostUsd >= 0) { "Invalid cost estimate" }
        require(config.dailyRequestLimit in 1..500 && config.dailyByteLimit in 1..100L * 1024 * 1024 && config.dailyCostBudgetUsd.isFinite() && config.dailyCostBudgetUsd > 0) { "Invalid cloud budget" }
        if (active != null) throw LedgerInFlightException("A cloud request is already active")
        val channel = RandomAccessFile(lockFile, "rw").channel
        val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
        if (lock == null) {
            channel.close()
            throw LedgerInFlightException("Another cloud request owns the budget")
        }
        try {
            val state = load()
            val now = timeProvider()
            val day = now / DAY_MILLIS
            // Never revisit a previously exhausted period after wall-clock rollback.
            if (day > state.day) {
                state.day = day
                state.requests = 0
                state.bytes = 0
                state.cost = 0.0
            }
            if (state.requests >= config.dailyRequestLimit) throw LedgerLimitExceededException("Daily cloud request limit reached")
            if (state.bytes > config.dailyByteLimit - estimatedBytes) throw LedgerLimitExceededException("Daily cloud traffic reservation limit reached")
            if (state.cost + estimatedCostUsd > config.dailyCostBudgetUsd) throw LedgerLimitExceededException("Estimated cloud cost reservation limit reached")
            val reservation = LedgerReservation(UUID.randomUUID().toString(), state.day, estimatedBytes, estimatedCostUsd, now)
            state.requests++
            state.bytes += estimatedBytes
            state.cost += estimatedCostUsd
            state.pending = reservation.id
            persist(state)
            active = Held(reservation.id, channel, lock)
            return reservation
        } catch (failure: Throwable) {
            lock.release()
            channel.close()
            throw failure
        }
    }

    @Synchronized
    fun commit(reservation: LedgerReservation) = settle(reservation)

    @Synchronized
    fun abort(reservation: LedgerReservation) = settle(reservation)

    private fun settle(reservation: LedgerReservation) {
        val held = active?.takeIf { it.id == reservation.id } ?: return
        try {
            val state = load()
            if (state.pending == reservation.id) {
                state.pending = ""
                persist(state)
            }
        } finally {
            active = null
            held.lock.release()
            held.channel.close()
        }
    }

    private fun load(): State {
        if (!file.exists()) return State(timeProvider() / DAY_MILLIS)
        require(file.length() in 2..16_384) { "Cloud budget record is unreadable; uploads are paused" }
        val json = JSONObject(file.readText(Charsets.UTF_8))
        val state = State(json.getLong("day"), json.getInt("requests"), json.getLong("bytes"), json.getDouble("cost"), json.optString("pending", ""))
        require(state.day >= 0 && state.requests >= 0 && state.bytes >= 0 && state.cost.isFinite() && state.cost >= 0) { "Invalid cloud budget record; uploads are paused" }
        return state
    }

    private fun persist(state: State) {
        val json = JSONObject().put("day", state.day).put("requests", state.requests)
            .put("bytes", state.bytes).put("cost", state.cost).put("pending", state.pending)
        writeCloudAtomic(file, json.toString().toByteArray(Charsets.UTF_8))
    }

    companion object { private const val DAY_MILLIS = 86_400_000L }
}
