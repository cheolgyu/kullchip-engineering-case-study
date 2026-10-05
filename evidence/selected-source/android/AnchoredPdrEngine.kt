package com.kullchip.next.core.derivation

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class AnchoredPdrEngine(
    private val config: PdrConfig = PdrConfig(),
) : PdrEngine {
    override fun estimateTrace(
        anchor: PetNavigationAnchor?,
        frames: List<PdrMotionFrame>,
    ): List<PdrEstimate> {
        if (frames.isEmpty()) return emptyList()
        if (anchor == null || !validCoordinate(anchor.latitude, anchor.longitude)) {
            return frames.map { frame ->
                PdrEstimate(
                    sourceGnssPacketId = null,
                    state = PdrState.GPS_MISSING,
                    latitude = Double.NaN,
                    longitude = Double.NaN,
                    accuracyMeters = Float.POSITIVE_INFINITY,
                    driftScore = Float.POSITIVE_INFINITY,
                    harnessStability = frame.harnessStability.coerceIn(0f, 1f),
                    cumulativeDistanceMeters = 0f,
                    timelineAtEpochMillis = frame.timelineAtEpochMillis,
                    sequenceNo = frame.sequenceNo,
                    featureWindowId = frame.featureWindowId,
                    ml0ModelVersion = frame.ml0ModelVersion,
                    productUsable = false,
                )
            }
        }

        var northMeters = 0.0
        var eastMeters = 0.0
        var distanceMeters = 0.0
        var minimumHarnessStability = 1f
        var qualitySum = 0f
        var validFrameCount = 0
        val validFrames = frames.filter(::validFrame)
        val output = ArrayList<PdrEstimate>(validFrames.size)
        validFrames.forEach { frame ->
            val headingRadians = Math.toRadians(frame.globalHeadingDegrees.toDouble())
            val forward = frame.deltaForwardMeters.toDouble()
            val right = frame.deltaRightMeters.toDouble()
            northMeters += forward * cos(headingRadians) - right * sin(headingRadians)
            eastMeters += forward * sin(headingRadians) + right * cos(headingRadians)
            distanceMeters += hypot(forward, right)
            minimumHarnessStability = minOf(minimumHarnessStability, frame.harnessStability.coerceIn(0f, 1f))
            qualitySum += frame.accelQuality.coerceIn(0f, 1f) * frame.gyroQuality.coerceIn(0f, 1f)
            validFrameCount += 1

            val latitude = anchor.latitude + northMeters / METERS_PER_DEGREE_LATITUDE
            val longitude = anchor.longitude + eastMeters / metersPerDegreeLongitude(anchor.latitude)
            val elapsedSeconds = (
                (frame.timelineAtEpochMillis - anchor.timelineAtEpochMillis).coerceAtLeast(0L).toFloat() /
                    1_000f
                )
            val averageQuality = (qualitySum / validFrameCount).coerceIn(0f, 1f)
            val instability = 1f - minOf(minimumHarnessStability, averageQuality)
            val incrementalDrift =
                distanceMeters.toFloat() * config.driftPerMeter +
                elapsedSeconds * config.staleAnchorPenaltyPerSecondMeters +
                instability * config.harnessInstabilityPenaltyMeters
            val accumulatedDrift = anchor.accumulatedDriftMeters + incrementalDrift
            val accuracy = anchor.accuracyMeters.coerceAtLeast(0f) + incrementalDrift
            val driftScore = accumulatedDrift / config.maxIncrementalDriftMeters.coerceAtLeast(0.1f)
            val state = when {
                driftScore > 1f -> PdrState.DRIFT_HIGH
                anchor.source == PetNavigationAnchorSource.PET_GNSS && distanceMeters < 0.2 -> PdrState.GPS_ANCHORED
                else -> PdrState.PDR_ESTIMATED
            }
            output += PdrEstimate(
                sourceGnssPacketId = anchor.sourceGnssPacketId,
                state = state,
                latitude = latitude,
                longitude = longitude,
                accuracyMeters = accuracy,
                driftScore = driftScore,
                harnessStability = minimumHarnessStability,
                cumulativeDistanceMeters = distanceMeters.toFloat(),
                timelineAtEpochMillis = frame.timelineAtEpochMillis,
                sequenceNo = frame.sequenceNo,
                featureWindowId = frame.featureWindowId,
                ml0ModelVersion = frame.ml0ModelVersion,
                productUsable = state == PdrState.GPS_ANCHORED || state == PdrState.PDR_ESTIMATED,
            )
        }
        return output
    }

    private fun validFrame(frame: PdrMotionFrame): Boolean =
        frame.timelineAtEpochMillis > 0L &&
            frame.deltaForwardMeters.isFinite() &&
            frame.deltaRightMeters.isFinite() &&
            frame.globalHeadingDegrees.isFinite() &&
            frame.harnessStability.isFinite() &&
            frame.accelQuality.isFinite() &&
            frame.gyroQuality.isFinite()

    private fun validCoordinate(latitude: Double, longitude: Double): Boolean =
        latitude.isFinite() &&
            longitude.isFinite() &&
            latitude in -90.0..90.0 &&
            longitude in -180.0..180.0 &&
            !(latitude == 0.0 && longitude == 0.0)

    private fun metersPerDegreeLongitude(latitude: Double): Double =
        (METERS_PER_DEGREE_LATITUDE * abs(cos(Math.toRadians(latitude)))).coerceAtLeast(1.0)

    companion object {
        private const val METERS_PER_DEGREE_LATITUDE: Double = 111_320.0
    }
}
