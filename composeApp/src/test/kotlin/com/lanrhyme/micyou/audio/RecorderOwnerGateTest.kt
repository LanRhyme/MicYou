package com.lanrhyme.micyou.audio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RecorderOwnerGateTest {
    @Test
    fun createOwnsResourceUntilMatchingTeardownCompletes() {
        val gate = RecorderOwnerGate<Any>()
        val resource = Any()

        assertSame(resource, gate.create({ "blocked" }) { resource })
        assertFailsWith<IllegalStateException> {
            gate.create({ "blocked" }) { Any() }
        }

        val started = assertIs<RecorderOwnerGate.TeardownResult.Started>(
            gate.beginTeardown(resource)
        )
        val repeated = assertIs<RecorderOwnerGate.TeardownResult.Existing>(
            gate.beginTeardown(resource)
        )
        assertSame(started.completion, repeated.completion)
        assertFalse(started.completion.isCompleted)
        assertFailsWith<IllegalStateException> {
            gate.create({ "blocked" }) { Any() }
        }

        gate.completeTeardown(Any(), started.completion)
        assertFalse(started.completion.isCompleted)
        gate.completeTeardown(resource, started.completion)
        assertTrue(started.completion.isCompleted)

        val next = Any()
        assertSame(next, gate.create({ "blocked" }) { next })
    }

    @Test
    fun differentResourceCannotTakeOverActiveOrTeardownOwner() {
        val gate = RecorderOwnerGate<Any>()
        val owner = gate.create({ "blocked" }) { Any() }
        val other = Any()

        val activeRejection = assertIs<RecorderOwnerGate.TeardownResult.Rejected>(
            gate.beginTeardown(other)
        )
        assertTrue(activeRejection.ownerIsActive)

        val teardown = assertIs<RecorderOwnerGate.TeardownResult.Started>(
            gate.beginTeardown(owner)
        )
        val teardownRejection = assertIs<RecorderOwnerGate.TeardownResult.Rejected>(
            gate.beginTeardown(other)
        )
        assertFalse(teardownRejection.ownerIsActive)

        gate.completeTeardown(owner, teardown.completion)
    }

    @Test
    fun blockedFactoryDoesNotHoldMonitorAndConcurrentCreateFailsFast() {
        val gate = RecorderOwnerGate<Any>()
        val factoryEntered = CountDownLatch(1)
        val releaseFactory = CountDownLatch(1)
        val created = Any()
        val firstFailure = AtomicReference<Throwable?>()

        val first = thread {
            try {
                gate.create({ "blocked" }) {
                    factoryEntered.countDown()
                    check(releaseFactory.await(5, TimeUnit.SECONDS))
                    created
                }
            } catch (failure: Throwable) {
                firstFailure.set(failure)
            }
        }
        assertTrue(factoryEntered.await(5, TimeUnit.SECONDS))

        val startedAt = System.nanoTime()
        assertFailsWith<IllegalStateException> {
            gate.create({ "blocked" }) { Any() }
        }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        assertTrue(elapsedMs < 500, "second create took ${elapsedMs}ms")

        releaseFactory.countDown()
        first.join(5000)
        assertFalse(first.isAlive)
        assertNull(firstFailure.get())

        val teardown = assertIs<RecorderOwnerGate.TeardownResult.Started>(gate.beginTeardown(created))
        gate.completeTeardown(created, teardown.completion)
    }

    @Test
    fun factoryFailureReturnsGateToIdle() {
        val gate = RecorderOwnerGate<Any>()
        assertFailsWith<IllegalArgumentException> {
            gate.create({ "blocked" }) { throw IllegalArgumentException("factory") }
        }

        val resource = Any()
        assertSame(resource, gate.create({ "blocked" }) { resource })
    }

    @Test
    fun concurrentCreatesRunOnlyOneFactory() {
        val gate = RecorderOwnerGate<Any>()
        val factoryEntered = CountDownLatch(1)
        val releaseFactory = CountDownLatch(1)
        val factoryCalls = AtomicInteger()
        val rejections = AtomicInteger()
        val owner = Any()

        val first = thread {
            gate.create({ "blocked" }) {
                factoryCalls.incrementAndGet()
                factoryEntered.countDown()
                check(releaseFactory.await(5, TimeUnit.SECONDS))
                owner
            }
        }
        assertTrue(factoryEntered.await(5, TimeUnit.SECONDS))
        val contenders = List(8) {
            thread {
                try {
                    gate.create({ "blocked" }) {
                        factoryCalls.incrementAndGet()
                        Any()
                    }
                } catch (_: IllegalStateException) {
                    rejections.incrementAndGet()
                }
            }
        }
        contenders.forEach { it.join(5000) }
        releaseFactory.countDown()
        first.join(5000)

        assertTrue(contenders.none { it.isAlive })
        assertFalse(first.isAlive)
        assertTrue(factoryCalls.get() == 1)
        assertTrue(rejections.get() == contenders.size)

        val teardown = assertIs<RecorderOwnerGate.TeardownResult.Started>(gate.beginTeardown(owner))
        gate.completeTeardown(owner, teardown.completion)
    }

    @Test
    fun audioReadHealthDistinguishesDataIdleErrorAndStall() {
        assertSame(AudioReadStatus.Data, classifyAudioRead(128, 1_000, 7_000, 5_000))
        assertSame(AudioReadStatus.Waiting, classifyAudioRead(0, 1_000, 5_999, 5_000))
        assertSame(AudioReadStatus.Stalled, classifyAudioRead(0, 1_000, 6_000, 5_000))
        assertSame(AudioReadStatus.Failed, classifyAudioRead(-3, 1_000, 1_001, 5_000))
    }

    @Test
    fun activeOwnerPublishesAndClearsOnlyForExactSessionAndResource() {
        val owners = ActiveEngineOwner<Any, Any>()
        val engineA = Any()
        val engineB = Any()
        val sessionA = Any()
        val recorderA = Any()

        owners.publish(engineA, sessionA, recorderA)
        assertSame(engineA, owners.get())
        assertFalse(owners.clearIfCurrent(engineB, sessionA, recorderA))
        assertFalse(owners.clearIfCurrent(engineA, Any(), recorderA))
        assertFalse(owners.clearIfCurrent(engineA, sessionA, Any()))
        assertSame(engineA, owners.get())

        assertTrue(owners.clearIfCurrent(engineA, sessionA, recorderA))
        assertNull(owners.get())
    }
}
