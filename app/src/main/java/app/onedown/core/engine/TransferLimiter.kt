package app.onedown.core.engine

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.math.ceil
import kotlin.math.min

/** Shared token bucket; monotonic time and suspension are injectable for JVM tests. */
class TransferLimiter(
    private val clock: () -> Long = System::nanoTime,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private var rate = 0L
    private var tokens = 0.0
    private var updated = clock()

    @Synchronized
    fun setLimit(bytesPerSecond: Long) {
        require(bytesPerSecond >= 0)
        refill()
        if (rate == bytesPerSecond) return
        rate = bytesPerSecond
        tokens = 0.0
        updated = clock()
    }

    private fun refill() {
        val now = clock()
        tokens = min(rate.toDouble(), tokens + (now - updated).coerceAtLeast(0) / 1e9 * rate)
        updated = now
    }

    @Synchronized
    fun readSize(): Int = if (rate == 0L) 65536 else (rate / 10).coerceIn(1, 65536).toInt()

    @Synchronized
    fun refund(bytes: Int) {
        if (rate > 0) tokens = min(rate.toDouble(), tokens + bytes.coerceAtLeast(0))
    }

    suspend fun acquire(bytes: Int) {
        require(bytes >= 0)
        var remaining = bytes.toDouble()
        while (remaining > 0) {
            currentCoroutineContext().ensureActive()
            val wait = synchronized(this) {
                if (rate == 0L) return
                refill()
                val consumed = min(tokens, remaining)
                tokens -= consumed
                remaining -= consumed
                if (remaining == 0.0) 0L else
                    ceil(min(remaining, rate.toDouble()) / rate * 1000).toLong().coerceIn(1, 100)
            }
            if (wait > 0) sleep(wait)
        }
    }
}