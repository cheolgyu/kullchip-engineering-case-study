package com.kullchip.next.mobile.model

import com.kullchip.next.core.derivation.CandidateMotionArtifacts
import com.kullchip.next.core.derivation.HarnessGravityReference
import com.kullchip.next.core.derivation.Ml0AlignmentMode
import com.kullchip.next.core.derivation.Ml0BodyFrame
import com.kullchip.next.core.derivation.ModelBlockReason
import com.kullchip.next.core.derivation.ModelStageResult
import com.kullchip.next.core.derivation.MotionEngine
import com.kullchip.next.core.derivation.MotionInference
import com.kullchip.next.core.derivation.PetNavigationAnchor
import com.kullchip.next.core.derivation.SensorFeatureWindow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorityGatedMotionEngineTest {
    private val model = CandidateMotionArtifacts.displacement.identity

    @Test
    fun deniedCandidateBlocksBeforeDelegateExecution() = runTest {
        val delegate = RecordingMotionEngine()
        val engine = AuthorityGatedMotionEngine(
            CandidateMotionArtifacts.displacement,
            MotionArtifactAuthority { null },
            delegate,
        )

        val result = engine.infer(window(), ml0(), gravity(), null).getOrThrow()

        assertFalse(delegate.called)
        assertTrue(result is ModelStageResult.Blocked)
        assertEquals(ModelBlockReason.MOTION_MODEL_UNAVAILABLE, (result as ModelStageResult.Blocked).block.reason)
    }

    @Test
    fun allowedCandidateUsesSameMotionEngine() = runTest {
        val delegate = RecordingMotionEngine()
        val authorized = model.copy(maturity = com.kullchip.next.core.derivation.ModelArtifactMaturity.PRODUCT_AUTHORITY)
        val engine = AuthorityGatedMotionEngine(
            CandidateMotionArtifacts.displacement,
            MotionArtifactAuthority { authorized },
            delegate,
        )

        val result = engine.infer(window(), ml0(), gravity(), null).getOrThrow()

        assertTrue(delegate.called)
        assertTrue(result is ModelStageResult.Produced)
        assertEquals(authorized, (result as ModelStageResult.Produced).value.model)
    }

    private class RecordingMotionEngine : MotionEngine {
        var called = false

        override suspend fun infer(
            window: SensorFeatureWindow,
            ml0: Ml0BodyFrame,
            gravityReference: HarnessGravityReference,
            anchor: PetNavigationAnchor?,
        ): Result<ModelStageResult<MotionInference>> {
            called = true
            return Result.success(
                ModelStageResult.Produced(
                    MotionInference(
                        windowId = window.windowId,
                        model = CandidateMotionArtifacts.displacement.identity,
                        featureSchemaVersion = "motion-v1",
                        featureHash = "2".repeat(64),
                        navigationAlgorithmVersion = "pdr-test-v1",
                        frames = emptyList(),
                    ),
                ),
            )
        }
    }

    private fun window() = SensorFeatureWindow(
        windowId = "window-1",
        walkId = com.kullchip.next.core.runtime.WalkId("walk-1"),
        petId = com.kullchip.next.core.runtime.PetId("pet-1"),
        sourceDeviceId = "watch-1",
        sourceSessionId = "session-1",
        firstSequenceNo = 1L,
        lastSequenceNo = 50L,
        startedAtEpochMillis = 1_000L,
        endedAtEpochMillis = 2_000L,
        featureSchemaVersion = "raw-v1",
        rawContentHash = "1".repeat(64),
        rawPackets = emptyList(),
    )

    private fun ml0() = Ml0BodyFrame(
        windowId = "window-1",
        algorithmVersion = Ml0BodyFrame.ALGORITHM_VERSION,
        featureSchemaVersion = Ml0BodyFrame.FEATURE_SCHEMA_VERSION,
        featureHash = "3".repeat(64),
        rawContentHash = "1".repeat(64),
        alignmentMode = Ml0AlignmentMode.GRAVITY_MOUNT_FRAME,
        quaternionCoverage = 0f,
        alignmentQuality = 0.5f,
        values = FloatArray(Ml0BodyFrame.VALUE_COUNT),
    )

    private fun gravity() = HarnessGravityReference(
        referenceId = "4".repeat(64),
        sourceWindowId = "window-1",
        sourceRawContentHash = "5".repeat(64),
        unitX = 0f,
        unitY = 0f,
        unitZ = 1f,
    )
}
