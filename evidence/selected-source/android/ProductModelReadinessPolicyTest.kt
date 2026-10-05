package com.kullchip.next.mobile.learning

import com.kullchip.next.core.derivation.CandidateModelArtifacts
import com.kullchip.next.core.derivation.Ml2UpstreamAuthorityFingerprint
import com.kullchip.next.core.derivation.Ml2UpstreamCheckpointIdentity
import com.kullchip.next.core.learning.BehaviorLearningCatalog
import com.kullchip.next.core.learning.Ml1ClassCoverage
import com.kullchip.next.core.learning.Ml1CorpusGate
import com.kullchip.next.core.learning.Ml1HoldoutAxisCoverage
import com.kullchip.next.core.learning.Ml1HoldoutCorpusGate
import com.kullchip.next.core.learning.Ml2ClassCoverage
import com.kullchip.next.core.learning.Ml2CorpusGate
import com.kullchip.next.core.runtime.PetId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductModelReadinessPolicyTest {
    @Test
    fun everyExactActiveCheckpointAndMotionCertificateProducesProductAuthority() {
        val ml1 = activeMl1Checkpoints()
        val upstreamAuthoritySha256 = ml1AuthoritySha256(ml1)
        val evidence = evidence(
            checkpoints = ml1 + ML2_EXPECTED.map { (modelId, sha256) ->
                checkpoint(
                    modelId = modelId,
                    artifactSha = sha256,
                    active = true,
                    validationState = "PRODUCT_ACTIVE",
                    upstreamAuthoritySha256 = upstreamAuthoritySha256,
                )
            },
            ml1Ready = true,
            ml2Ready = true,
        )

        val result = evaluate(evidence, motionReady = true)

        assertTrue(result.productAuthorityReady)
        assertTrue(result.stages.all(ProductModelStageReadiness::ready))
        assertEquals(null, result.nextBlockingStage)
    }

    @Test
    fun executableCandidateMotionNeverBecomesProductAuthority() {
        val result = evaluate(evidence(), motionReady = false)

        assertFalse(result.productAuthorityReady)
        assertEquals(
            ProductModelReadinessReason.MOTION_AUTHORITY_CERTIFICATE_REQUIRED,
            result.nextBlockingStage?.reason,
        )
    }

    @Test
    fun legacyProductActiveCheckpointsStayVerifyingWithout81ValueEvidence() {
        val result = evaluate(
            evidence(
                checkpoints = activeMl1Checkpoints(),
                ml1Ready = true,
            ),
            motionReady = true,
            productValueAuthorizedCheckpointIds = emptySet(),
        )

        assertFalse(result.bodyGesture.ready)
        assertEquals(ProductModelReadinessLevel.VERIFYING, result.bodyGesture.level)
        assertEquals(
            ProductModelReadinessReason.ML1_PRODUCT_VALUE_EVIDENCE_REQUIRED,
            result.bodyGesture.reason,
        )
        assertEquals(ProductModelReadinessReason.ML2_UPSTREAM_ML1_REQUIRED, result.episode.reason)
    }

    @Test
    fun missingMl1CoverageIsCollectingAndBlocksMl2Upstream() {
        val result = evaluate(evidence(), motionReady = true)

        assertEquals(ProductModelReadinessLevel.COLLECTING, result.bodyGesture.level)
        assertEquals(ProductModelReadinessReason.ML1_CORPUS_REQUIRED, result.bodyGesture.reason)
        assertEquals(ProductModelReadinessReason.ML2_UPSTREAM_ML1_REQUIRED, result.episode.reason)
    }

    @Test
    fun wrongActiveArtifactFailsClosedEvenWhenCoverageIsReady() {
        val checkpoints = listOf(
            checkpoint(MOVEMENT_MODEL_ID, WRONG_SHA, active = true, validationState = "PRODUCT_ACTIVE"),
        )

        val result = evaluate(
            evidence(checkpoints = checkpoints, ml1Ready = true),
            motionReady = true,
        )

        assertEquals(ProductModelReadinessLevel.BLOCKED, result.bodyGesture.level)
        assertEquals(ProductModelReadinessReason.ML1_CHECKPOINT_INVALID, result.bodyGesture.reason)
    }

    @Test
    fun malformedActiveCheckpointFailsClosedWithoutCrashingReadiness() {
        val checkpoints = listOf(
            checkpoint(
                MOVEMENT_MODEL_ID,
                ML1_EXPECTED.getValue(MOVEMENT_MODEL_ID),
                active = true,
                validationState = "PRODUCT_ACTIVE",
            ).copy(weightsSha256 = "corrupt"),
        )

        val result = evaluate(
            evidence(checkpoints = checkpoints, ml1Ready = true),
            motionReady = true,
        )

        assertEquals(ProductModelReadinessLevel.BLOCKED, result.bodyGesture.level)
        assertEquals(ProductModelReadinessReason.ML1_CHECKPOINT_INVALID, result.bodyGesture.reason)
    }

    @Test
    fun retiredPreviousArtifactDoesNotPermanentlyBlockCurrentGenerationTraining() {
        val checkpoints = listOf(
            checkpoint(
                modelId = MOVEMENT_MODEL_ID,
                artifactSha = WRONG_SHA,
                active = false,
                validationState = "RETIRED_ARTIFACT",
                validationReason = "schema_upgrade_requires_retraining",
            ),
        )

        val result = evaluate(
            evidence(checkpoints = checkpoints, ml1Ready = true),
            motionReady = true,
        )

        assertEquals(ProductModelReadinessLevel.COLLECTING, result.bodyGesture.level)
        assertEquals(ProductModelReadinessReason.ML1_TRAINING_REQUIRED, result.bodyGesture.reason)
        assertEquals(null, result.bodyGesture.validationDetail)
    }

    @Test
    fun recordedFieldGateFailureIsReportedInsteadOfGenericLearningProgress() {
        val checkpoints = listOf(
            checkpoint(
                modelId = MOVEMENT_MODEL_ID,
                artifactSha = ML1_EXPECTED.getValue(MOVEMENT_MODEL_ID),
                active = false,
                validationState = "AWAITING_HOLDOUT",
                validationReason = "validation_unavailable:model_field_gate_evidence_missing",
            ),
        )

        val result = evaluate(
            evidence(checkpoints = checkpoints, ml1Ready = true),
            motionReady = true,
        )

        assertEquals(ProductModelReadinessLevel.VERIFYING, result.bodyGesture.level)
        assertEquals(ProductModelReadinessReason.ML1_FIELD_EVIDENCE_REQUIRED, result.bodyGesture.reason)
    }

    @Test
    fun trainCoverageNeverSubstitutesForIndependentMl1Holdout() {
        val checkpoints = ML1_EXPECTED.map { (modelId, sha256) ->
            checkpoint(modelId, sha256, active = false, validationState = "AWAITING_HOLDOUT")
        }

        val result = evaluate(
            evidence(
                checkpoints = checkpoints,
                ml1Ready = true,
                ml1HoldoutReady = false,
            ),
            motionReady = true,
        )

        assertEquals(ProductModelReadinessLevel.VERIFYING, result.bodyGesture.level)
        assertEquals(ProductModelReadinessReason.ML1_HOLDOUT_REQUIRED, result.bodyGesture.reason)
    }

    @Test
    fun staleCandidateIsLearningProgressInsteadOfValidationFailure() {
        val checkpoints = ML1_EXPECTED.map { (modelId, sha256) ->
            checkpoint(
                modelId = modelId,
                artifactSha = sha256,
                active = false,
                validationState = "AWAITING_HOLDOUT",
                validationReason = "corpus_authority_blocked:ml1_candidate_stale:created=10:train_evidence=20",
            )
        }

        val result = evaluate(
            evidence(checkpoints = checkpoints, ml1Ready = true),
            motionReady = true,
        )

        assertEquals(ProductModelReadinessLevel.COLLECTING, result.bodyGesture.level)
        assertEquals(ProductModelReadinessReason.ML1_TRAINING_REQUIRED, result.bodyGesture.reason)
    }

    @Test
    fun ml2CheckpointCannotRunWithoutEveryMl1Authority() {
        val ml2 = checkpoint(
            modelId = ML2_MODEL_ID,
            artifactSha = ML2_EXPECTED.getValue(ML2_MODEL_ID),
            active = true,
            validationState = "PRODUCT_ACTIVE",
        )

        val result = evaluate(
            evidence(checkpoints = listOf(ml2), ml1Ready = true, ml2Ready = true),
            motionReady = true,
        )

        assertEquals(ProductModelReadinessReason.ML2_UPSTREAM_ML1_REQUIRED, result.episode.reason)
        assertFalse(result.episode.ready)
    }

    @Test
    fun leashReadinessIsRequiredForMl1ButNeverChangesMl2UpstreamAuthority() {
        val bodyCheckpoints = activeMl1Checkpoints().filterNot { checkpoint ->
            checkpoint.modelId == CandidateModelArtifacts.ml1Leash.modelId
        }
        val upstreamAuthoritySha256 = ml1AuthoritySha256(bodyCheckpoints)
        val ml2 = checkpoint(
            modelId = ML2_MODEL_ID,
            artifactSha = ML2_EXPECTED.getValue(ML2_MODEL_ID),
            active = true,
            validationState = "PRODUCT_ACTIVE",
            upstreamAuthoritySha256 = upstreamAuthoritySha256,
        )

        val result = evaluate(
            evidence(
                checkpoints = bodyCheckpoints + ml2,
                ml1Ready = true,
                ml2Ready = true,
            ),
            motionReady = true,
        )

        assertFalse(result.bodyGesture.ready)
        assertEquals(6, result.bodyGesture.readyUnitCount)
        assertEquals(7, result.bodyGesture.requiredUnitCount)
        assertTrue(result.episode.ready)
    }

    @Test
    fun trainedMl2CandidateWithMissingIndependentCorpusReportsHoldout() {
        val ml1 = activeMl1Checkpoints()
        val ml2 = checkpoint(
            modelId = ML2_MODEL_ID,
            artifactSha = ML2_EXPECTED.getValue(ML2_MODEL_ID),
            active = false,
            validationState = "AWAITING_HOLDOUT",
            upstreamAuthoritySha256 = ml1AuthoritySha256(ml1),
        )

        val result = evaluate(
            evidence(
                checkpoints = ml1 + ml2,
                ml1Ready = true,
                ml2TrainingReady = true,
            ),
            motionReady = true,
        )

        assertEquals(ProductModelReadinessLevel.VERIFYING, result.episode.level)
        assertEquals(ProductModelReadinessReason.ML2_HOLDOUT_REQUIRED, result.episode.reason)
    }

    @Test
    fun activeMl2FromDifferentMl1AuthorityIsBlocked() {
        val ml1 = activeMl1Checkpoints()
        val ml2 = checkpoint(
            modelId = ML2_EXPECTED.keys.single(),
            artifactSha = ML2_EXPECTED.values.single(),
            active = true,
            validationState = "PRODUCT_ACTIVE",
            upstreamAuthoritySha256 = WRONG_SHA,
        )

        val result = evaluate(
            evidence(
                checkpoints = ml1 + ml2,
                ml1Ready = true,
                ml2Ready = true,
            ),
            motionReady = true,
        )

        assertFalse(result.productAuthorityReady)
        assertEquals(ProductModelReadinessLevel.BLOCKED, result.episode.level)
        assertEquals(ProductModelReadinessReason.ML2_CHECKPOINT_INVALID, result.episode.reason)
    }

    private fun evaluate(
        evidence: ProductModelReadinessEvidence,
        motionReady: Boolean,
        productValueAuthorizedCheckpointIds: Set<String> = evidence.checkpoints
            .filter(ProductCheckpointReadinessEvidence::active)
            .map(ProductCheckpointReadinessEvidence::checkpointId)
            .toSet(),
    ) = ProductModelReadinessPolicy.evaluate(
        evidence = evidence,
        motionAuthorityReady = motionReady,
        productValueAuthorizedCheckpointIds = productValueAuthorizedCheckpointIds,
        expectedMl1Artifacts = ML1_EXPECTED,
        expectedMl2Artifacts = ML2_EXPECTED,
    )

    private fun evidence(
        checkpoints: List<ProductCheckpointReadinessEvidence> = emptyList(),
        ml1Ready: Boolean = false,
        ml1HoldoutReady: Boolean = ml1Ready,
        ml2TrainingReady: Boolean = false,
        ml2Ready: Boolean = false,
    ) = ProductModelReadinessEvidence(
        petId = PET_ID,
        learning = summary(),
        ml1Corpus = if (ml1Ready) readyMl1Corpus() else Ml1CorpusGate.evaluate(emptyList()),
        ml1HoldoutCorpus = if (ml1HoldoutReady) {
            readyMl1HoldoutCorpus()
        } else {
            Ml1HoldoutCorpusGate.evaluate(emptyList())
        },
        ml2Corpus = when {
            ml2Ready -> readyMl2Corpus()
            ml2TrainingReady -> trainingReadyMl2Corpus()
            else -> Ml2CorpusGate.evaluate(emptyList())
        },
        checkpoints = checkpoints,
    )

    private fun readyMl1Corpus() = Ml1CorpusGate.evaluate(
        BehaviorLearningCatalog.intervalAxes.flatMap { axis ->
            BehaviorLearningCatalog.labels(axis).map { label ->
                Ml1ClassCoverage(
                    axis = axis,
                    label = label,
                    intervalCount = Ml1CorpusGate.MIN_INTERVALS_PER_CLASS,
                    walkCount = Ml1CorpusGate.MIN_WALKS_PER_CLASS,
                )
            }
        },
    )

    private fun readyMl1HoldoutCorpus() = Ml1HoldoutCorpusGate.evaluate(
        BehaviorLearningCatalog.intervalAxes.map { axis ->
            Ml1HoldoutAxisCoverage(
                axis = axis,
                exampleCountByLabel = BehaviorLearningCatalog.labels(axis).associateWith { 1L },
                walkCount = Ml1HoldoutCorpusGate.MIN_WALKS_PER_MODEL,
            )
        },
    )

    private fun readyMl2Corpus() = Ml2CorpusGate.evaluate(
        Ml2CorpusGate.REQUIRED_LABELS.map { label ->
            Ml2ClassCoverage(
                label = label,
                trainExampleCount = Ml2CorpusGate.MIN_PRODUCT_TRAIN_EXAMPLES_PER_CLASS,
                trainWalkCount = Ml2CorpusGate.MIN_TRAIN_WALKS_PER_CLASS,
                holdoutExampleCount = Ml2CorpusGate.MIN_HOLDOUT_EXAMPLES_PER_CLASS,
                holdoutWalkCount = Ml2CorpusGate.MIN_HOLDOUT_WALKS_PER_CLASS,
            )
        },
    )

    private fun trainingReadyMl2Corpus() = Ml2CorpusGate.evaluate(
        Ml2CorpusGate.REQUIRED_LABELS.map { label ->
            Ml2ClassCoverage(
                label = label,
                trainExampleCount = Ml2CorpusGate.MIN_TRAIN_EXAMPLES_PER_CLASS,
                trainWalkCount = Ml2CorpusGate.MIN_TRAIN_WALKS_PER_CLASS,
                holdoutExampleCount = 0,
                holdoutWalkCount = 0,
            )
        },
    )

    private fun checkpoint(
        modelId: String,
        artifactSha: String,
        active: Boolean,
        validationState: String,
        validationReason: String? = null,
        upstreamAuthoritySha256: String? = null,
    ) = ProductCheckpointReadinessEvidence(
        checkpointId = "checkpoint-$modelId-${if (active) "active" else "candidate"}",
        modelId = modelId,
        baseArtifactSha256 = artifactSha,
        weightsSha256 = WEIGHTS_SHA,
        version = 1L,
        validationState = validationState,
        active = active,
        lastValidationReason = validationReason,
        lastValidatedAtEpochMillis = 1_000L,
        upstreamAuthoritySha256 = upstreamAuthoritySha256,
    )

    private fun activeMl1Checkpoints() = ML1_EXPECTED.map { (modelId, sha256) ->
        checkpoint(modelId, sha256, active = true, validationState = "PRODUCT_ACTIVE")
    }

    private fun ml1AuthoritySha256(checkpoints: List<ProductCheckpointReadinessEvidence>) =
        Ml2UpstreamAuthorityFingerprint.compute(
            checkpoints.filter { checkpoint -> checkpoint.modelId != CandidateModelArtifacts.ml1Leash.modelId }
                .map { checkpoint ->
                Ml2UpstreamCheckpointIdentity(
                    modelId = checkpoint.modelId,
                    artifactSha256 = checkpoint.baseArtifactSha256,
                    checkpointId = checkpoint.checkpointId,
                    weightsSha256 = checkpoint.weightsSha256,
                )
            },
        )

    private fun summary() = PetLearningSummaryRead(
        petId = PET_ID,
        savedLabelCount = 0,
        closedLabelCount = 0,
        pendingJobCount = 0,
        runningJobCount = 0,
        blockedJobCount = 0,
        succeededJobCount = 0,
        awaitingHoldoutCheckpointCount = 0,
        activeCheckpointCount = 0,
        trainingWalkCount = 0,
        holdoutWalkCount = 0,
        latestBlockReason = null,
        latestValidationReason = null,
    )

    private companion object {
        val PET_ID = PetId("pet-readiness")
        val ML1_EXPECTED = EXPECTED_ML1_ARTIFACTS
        val ML2_EXPECTED = EXPECTED_ML2_ARTIFACTS
        val MOVEMENT_MODEL_ID = CandidateModelArtifacts.ml1Movement.modelId
        val ML2_MODEL_ID = CandidateModelArtifacts.ml2PreOutcome.modelId
        val WRONG_SHA = "d".repeat(64)
        val WEIGHTS_SHA = "e".repeat(64)
    }
}
