package com.kaislate.veldtplayer.ui.browse

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The batching exists so a dead queue cannot turn into a minute of sequential snackbars,
 * so the properties worth pinning are "one burst yields one message" and "a genuinely
 * separate failure is still reported separately".
 */
class PlaybackErrorBatchTest {

    private val window = ERROR_BURST_WINDOW_MS

    /**
     * Starts the batcher and does not return until it has actually subscribed.
     *
     * The source is a bufferless [MutableSharedFlow], which DROPS anything emitted while
     * nobody is listening — and the batcher subscribes a couple of dispatches deep, so a
     * test that just emits after starting it silently loses its first error and then
     * hangs. Waiting on `subscriptionCount` is deterministic; yielding a fixed number of
     * times is not.
     */
    private suspend fun CoroutineScope.batching(
        source: MutableSharedFlow<String>,
        count: Int,
    ): Deferred<List<String>> {
        val collected = async { source.batchPlaybackErrors(window).take(count).toList() }
        source.subscriptionCount.first { it > 0 }
        return collected
    }

    @Test
    fun `single error passes through verbatim`() = runTest {
        val source = MutableSharedFlow<String>()
        val collected = batching(source, count = 1)

        source.emit("Couldn't play “A”")

        assertEquals(listOf("Couldn't play “A”"), collected.await())
    }

    @Test
    fun `a burst of distinct failures collapses to one counted message`() = runTest {
        val source = MutableSharedFlow<String>()
        val collected = batching(source, count = 1)

        // Twelve dead files skipped through faster than the window — the SD-card case.
        repeat(12) { source.emit("Couldn't play “track $it”") }

        assertEquals(listOf("Couldn't play 12 tracks"), collected.await())
    }

    @Test
    fun `the window re-opens on each arrival so a steady skip-through stays one batch`() =
        runTest {
            val source = MutableSharedFlow<String>()
            val collected = batching(source, count = 1)

            // Each gap is under the window, but the total span is far over it.
            repeat(5) {
                source.emit("Couldn't play “track $it”")
                delay(window - 1)
            }

            assertEquals(listOf("Couldn't play 5 tracks"), collected.await())
        }

    @Test
    fun `failures separated by more than the window are reported separately`() = runTest {
        val source = MutableSharedFlow<String>()
        val collected = batching(source, count = 2)

        source.emit("Couldn't play “A”")
        delay(window * 3)
        source.emit("Couldn't play “B”")

        assertEquals(listOf("Couldn't play “A”", "Couldn't play “B”"), collected.await())
    }

    @Test
    fun `a repeated identical message is reported once, not counted`() = runTest {
        val source = MutableSharedFlow<String>()
        val collected = batching(source, count = 1)

        // The connect failure is what repeats verbatim; "Couldn't play 3 tracks" would
        // describe it wrongly.
        repeat(3) { source.emit("Couldn't connect to playback") }

        assertEquals(listOf("Couldn't connect to playback"), collected.await())
    }

    @Test
    fun `summarize is pure and total`() {
        assertEquals("", summarizePlaybackErrors(emptyList()))
        assertEquals("one", summarizePlaybackErrors(listOf("one")))
        assertEquals("one", summarizePlaybackErrors(listOf("one", "one")))
        assertEquals("Couldn't play 2 tracks", summarizePlaybackErrors(listOf("one", "two")))
    }
}
