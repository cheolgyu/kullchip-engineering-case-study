package com.kullchip.next.core.derivation

import com.kullchip.next.core.sensor.PacketId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnchoredPdrEngineTest {
    private val engine = AnchoredPdrEngine()

    @Test
    fun fiftyMl0FramesProduceFiftyPdrCoordinatesBetweenGnssAnchors() {
        val anchor = PetNavigationAnchor(
            sourceGnssPacketId = PacketId("gnss-1"),
            latitude = 37.5,
            longitude = 127.0,
            accuracyMeters = 4f,
            accumulatedDriftMeters = 0f,
            timelineAtEpochMillis = 1_000L,
            sequenceNo = 10L,
            globalHeadingDegrees = 0f,
            headingAccuracyDegrees = 4f,
            source = PetNavigationAnchorSource.PET_GNSS,
        )
        val frames = (1L..50L).map { index ->
            frame(
                timestamp = 1_000L + index * 20L,
                sequence = index,
                forwardMeters = 0.02f,
                headingDegrees = 0f,
            )
        }

        val trace = engine.estimateTrace(anchor, frames)

        assertEquals(50, trace.size)
        assertTrue(trace.last().latitude > anchor.latitude)
        assertEquals(anchor.longitude, trace.last().longitude, 1e-10)
        assertEquals(1f, trace.last().cumulativeDistanceMeters, 0.0001f)
    }

    @Test
    fun pdrCoordinatesStartAtTheExactGnssAnchor() {
        val anchor = PetNavigationAnchor(
            sourceGnssPacketId = PacketId("gnss-2"),
            latitude = 37.5,
            longitude = 127.0,
            accuracyMeters = 4f,
            accumulatedDriftMeters = 0f,
            timelineAtEpochMillis = 1_000L,
            sequenceNo = 10L,
            globalHeadingDegrees = 0f,
            headingAccuracyDegrees = 4f,
            source = PetNavigationAnchorSource.PET_GNSS,
        )
        val frames = (1L..50L).map { index ->
            frame(
                timestamp = 1_000L + index * 20L,
                sequence = index,
                forwardMeters = 0.001f,
                headingDegrees = 0f,
            )
        }

        val trace = engine.estimateTrace(anchor, frames)
        val latitudes = listOf(anchor.latitude) + trace.map(PdrEstimate::latitude)
        val stepMeters = latitudes.zipWithNext { previous, next ->
            (next - previous) * 111_320.0
        }

        assertEquals(50, trace.size)
        assertTrue(stepMeters.all { it in 0.0..0.002 })
        assertEquals(0.05, (trace.last().latitude - anchor.latitude) * 111_320.0, 0.001)
    }

    @Test
    fun modelConfirmedStationaryWindowDoesNotMoveTheGnssAnchor() {
        val anchor = PetNavigationAnchor(
            sourceGnssPacketId = PacketId("gnss-stationary"),
            latitude = 37.5,
            longitude = 127.0,
            accuracyMeters = 4f,
            accumulatedDriftMeters = 0f,
            timelineAtEpochMillis = 1_000L,
            sequenceNo = 10L,
            globalHeadingDegrees = 0f,
            headingAccuracyDegrees = 4f,
            source = PetNavigationAnchorSource.PET_GNSS,
        )
        val trace = engine.estimateTrace(
            anchor,
            (1L..50L).map { index ->
                frame(
                    timestamp = 1_000L + index * 20L,
                    sequence = index,
                    forwardMeters = 0f,
                    headingDegrees = 0f,
                )
            },
        )

        assertEquals(50, trace.size)
        assertTrue(trace.all { estimate -> estimate.latitude == anchor.latitude })
        assertTrue(trace.all { estimate -> estimate.longitude == anchor.longitude })
        assertTrue(trace.all { estimate -> estimate.cumulativeDistanceMeters == 0f })
    }

    @Test
    fun watchQuaternionCannotEnterGlobalHeadingThroughThisContract() {
        val frameFields = PdrMotionFrame::class.java.declaredFields.map { it.name }

        assertFalse(frameFields.any { it.contains("quat", ignoreCase = true) })
        assertTrue(frameFields.contains("globalHeadingDegrees"))
    }

    @Test
    fun missingGnssAnchorNeverCreatesMapUsableCoordinates() {
        val estimate = engine.estimateTrace(
            anchor = null,
            frames = listOf(frame(1_000L, 1L, 0.1f, 90f)),
        ).single()

        assertEquals(PdrState.GPS_MISSING, estimate.state)
        assertFalse(estimate.productUsable)
        assertTrue(estimate.latitude.isNaN())
        assertTrue(estimate.longitude.isNaN())
    }

    @Test
    fun pdrContinuationAnchorNeverMasqueradesAsFreshGnss() {
        val anchor = PetNavigationAnchor(
            sourceGnssPacketId = PacketId("gnss-1"),
            latitude = 37.5,
            longitude = 127.0,
            accuracyMeters = 4f,
            accumulatedDriftMeters = 0f,
            timelineAtEpochMillis = 1_000L,
            sequenceNo = 10L,
            globalHeadingDegrees = 0f,
            headingAccuracyDegrees = null,
            source = PetNavigationAnchorSource.PET_PDR,
        )

        val estimate = engine.estimateTrace(
            anchor = anchor,
            frames = listOf(frame(1_020L, 11L, 0.01f, 0f)),
        ).single()

        assertEquals(PdrState.PDR_ESTIMATED, estimate.state)
        assertTrue(estimate.productUsable)
        assertTrue(estimate.latitude > anchor.latitude)
    }

    @Test
    fun consecutiveWindowsContinueFromPreviousEndpointWhenGnssIsStale() {
        val gnss = PetGnssAnchor(
            sourcePacketId = PacketId("gnss-1"),
            latitude = 37.5,
            longitude = 127.0,
            accuracyMeters = 3f,
            timelineAtEpochMillis = 1_000L,
            sequenceNo = 1L,
            bearingDegrees = 0f,
        )
        val firstAnchor = requireNotNull(PetNavigationAnchorResolver.resolve(gnss, emptyList()))
        val first = engine.estimateTrace(
            firstAnchor,
            listOf(
                frame(2_520L, 10L, 0.5f, 0f),
                frame(2_540L, 11L, 0.5f, 0f),
            ),
        )
        val continuationAnchor = requireNotNull(
            PetNavigationAnchorResolver.resolve(
                gnss = gnss,
                pdrNewestFirst = first.takeLast(2).asReversed().map { estimate ->
                    PetPdrAnchorPoint(
                        sourceGnssPacketId = estimate.sourceGnssPacketId,
                        sourceGnssAccuracyMeters = gnss.accuracyMeters,
                        latitude = estimate.latitude,
                        longitude = estimate.longitude,
                        accuracyMeters = estimate.accuracyMeters,
                        timelineAtEpochMillis = estimate.timelineAtEpochMillis,
                        sequenceNo = estimate.sequenceNo,
                    )
                },
            ),
        )

        val second = engine.estimateTrace(
            continuationAnchor,
            listOf(frame(2_560L, 12L, 0.5f, requireNotNull(continuationAnchor.globalHeadingDegrees))),
        ).single()

        assertEquals(PetNavigationAnchorSource.PET_PDR, continuationAnchor.source)
        assertTrue(second.latitude > first.last().latitude)
        assertTrue(second.latitude - firstAnchor.latitude > first.last().latitude - firstAnchor.latitude)
    }

    @Test
    fun freshGnssBetweenWindowsRestartsPdrAtTheExactCoordinate() {
        val firstGnss = PetGnssAnchor(
            sourcePacketId = PacketId("gnss-1"),
            latitude = 37.5,
            longitude = 127.0,
            accuracyMeters = 3f,
            timelineAtEpochMillis = 1_000L,
            sequenceNo = 1L,
            bearingDegrees = 0f,
        )
        val firstAnchor = requireNotNull(PetNavigationAnchorResolver.resolve(firstGnss, emptyList()))
        val first = engine.estimateTrace(
            firstAnchor,
            (1L..50L).map { index ->
                frame(
                    timestamp = 1_000L + index * 20L,
                    sequence = index,
                    forwardMeters = 0f,
                    headingDegrees = 0f,
                )
            },
        )
        val secondGnss = firstGnss.copy(
            sourcePacketId = PacketId("gnss-2"),
            latitude = firstGnss.latitude + 6.0 / 111_320.0,
            timelineAtEpochMillis = 2_000L,
            sequenceNo = 2L,
        )
        val secondAnchor = requireNotNull(
            PetNavigationAnchorResolver.resolve(
                gnss = secondGnss,
                pdrNewestFirst = first.takeLast(2).asReversed().map { estimate ->
                    PetPdrAnchorPoint(
                        sourceGnssPacketId = estimate.sourceGnssPacketId,
                        sourceGnssAccuracyMeters = firstGnss.accuracyMeters,
                        latitude = estimate.latitude,
                        longitude = estimate.longitude,
                        accuracyMeters = estimate.accuracyMeters,
                        timelineAtEpochMillis = estimate.timelineAtEpochMillis,
                        sequenceNo = estimate.sequenceNo,
                    )
                },
            ),
        )
        val second = engine.estimateTrace(
            secondAnchor,
            (51L..100L).map { index ->
                frame(
                    timestamp = 1_000L + index * 20L,
                    sequence = index,
                    forwardMeters = 0.001f,
                    headingDegrees = 0f,
                )
            },
        )
        assertEquals(PetNavigationAnchorSource.PET_GNSS, secondAnchor.source)
        assertEquals(secondGnss.sourcePacketId, secondAnchor.sourceGnssPacketId)
        assertEquals(secondGnss.latitude, secondAnchor.latitude, 1e-9)
        assertEquals(secondGnss.longitude, secondAnchor.longitude, 1e-9)
        assertEquals(secondGnss.timelineAtEpochMillis, secondAnchor.timelineAtEpochMillis)
        assertTrue(second.first().latitude > secondAnchor.latitude)
        assertTrue(second.last().latitude > secondAnchor.latitude)
        assertTrue(second.all { it.state == PdrState.GPS_ANCHORED })
    }

    @Test
    fun poorAbsoluteGnssAccuracyDoesNotRejectBoundedIncrementalPdr() {
        val anchor = PetNavigationAnchor(
            sourceGnssPacketId = PacketId("gnss-poor-accuracy"),
            latitude = 37.5,
            longitude = 127.0,
            accuracyMeters = 25f,
            accumulatedDriftMeters = 0f,
            timelineAtEpochMillis = 1_000L,
            sequenceNo = 10L,
            globalHeadingDegrees = 0f,
            headingAccuracyDegrees = 4f,
            source = PetNavigationAnchorSource.PET_GNSS,
        )

        val estimate = engine.estimateTrace(
            anchor,
            listOf(frame(1_020L, 11L, 0.1f, 0f)),
        ).single()

        assertEquals(PdrState.GPS_ANCHORED, estimate.state)
        assertTrue(estimate.productUsable)
        assertTrue(estimate.accuracyMeters > anchor.accuracyMeters)
        assertTrue(estimate.driftScore < 1f)
    }

    @Test
    fun accumulatedPdrDriftStillExhaustsBudgetAcrossWindows() {
        val anchor = PetNavigationAnchor(
            sourceGnssPacketId = PacketId("gnss-stale"),
            latitude = 37.5,
            longitude = 127.0,
            accuracyMeters = 14.9f,
            accumulatedDriftMeters = 11.9f,
            timelineAtEpochMillis = 3_000L,
            sequenceNo = 100L,
            globalHeadingDegrees = 0f,
            headingAccuracyDegrees = null,
            source = PetNavigationAnchorSource.PET_PDR,
        )

        val estimate = engine.estimateTrace(
            anchor,
            listOf(frame(3_020L, 101L, 2f, 0f)),
        ).single()

        assertEquals(PdrState.DRIFT_HIGH, estimate.state)
        assertFalse(estimate.productUsable)
        assertTrue(estimate.driftScore > 1f)
    }

    private fun frame(
        timestamp: Long,
        sequence: Long,
        forwardMeters: Float,
        headingDegrees: Float,
    ) = PdrMotionFrame(
        timelineAtEpochMillis = timestamp,
        sequenceNo = sequence,
        deltaForwardMeters = forwardMeters,
        deltaRightMeters = 0f,
        globalHeadingDegrees = headingDegrees,
        harnessStability = 1f,
        accelQuality = 1f,
        gyroQuality = 1f,
        featureWindowId = "window-1",
        ml0ModelVersion = "ml0-test",
    )
}
