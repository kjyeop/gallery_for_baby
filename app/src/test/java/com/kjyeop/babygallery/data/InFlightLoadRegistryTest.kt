package com.kjyeop.babygallery.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class InFlightLoadRegistryTest {
    @Test
    fun `concurrent requests for the same key share one load`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val registry = InFlightLoadRegistry<String, String>(scope)
        val loadCount = AtomicInteger()
        val firstLoadStarted = CompletableDeferred<Unit>()
        val releaseLoad = CompletableDeferred<Unit>()

        try {
            val first = async {
                registry.load("same-key") {
                    loadCount.incrementAndGet()
                    firstLoadStarted.complete(Unit)
                    releaseLoad.await()
                    "ready"
                }
            }
            firstLoadStarted.await()
            val second = async {
                registry.load("same-key") {
                    loadCount.incrementAndGet()
                    "unexpected"
                }
            }

            releaseLoad.complete(Unit)

            assertEquals("ready", first.await())
            assertEquals("ready", second.await())
            assertEquals(1, loadCount.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `cancels an in-flight load after its last requester leaves`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val registry = InFlightLoadRegistry<String, String>(scope)
        val loadStarted = CompletableDeferred<Unit>()
        val loadCancelled = CompletableDeferred<Unit>()

        try {
            val requester = async {
                registry.load("only-requester") {
                    loadStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        loadCancelled.complete(Unit)
                    }
                }
            }
            loadStarted.await()

            requester.cancel()
            requester.join()

            withTimeout(1_000) { loadCancelled.await() }
        } finally {
            scope.cancel()
        }
    }
}
