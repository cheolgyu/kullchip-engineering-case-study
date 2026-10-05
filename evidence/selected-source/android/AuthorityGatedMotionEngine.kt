package com.kullchip.next.mobile.model

import com.kullchip.next.core.derivation.HarnessGravityReference
import com.kullchip.next.core.derivation.CandidateMotionArtifact
import com.kullchip.next.core.derivation.Ml0BodyFrame
import com.kullchip.next.core.derivation.ModelBlockReason
import com.kullchip.next.core.derivation.ModelIdentity
import com.kullchip.next.core.derivation.ModelStage
import com.kullchip.next.core.derivation.ModelStageBlock
import com.kullchip.next.core.derivation.ModelStageResult
import com.kullchip.next.core.derivation.MotionEngine
import com.kullchip.next.core.derivation.MotionInference
import com.kullchip.next.core.derivation.PetNavigationAnchor
import com.kullchip.next.core.derivation.SensorFeatureWindow

fun interface MotionArtifactAuthority {
    fun authorize(artifact: CandidateMotionArtifact): ModelIdentity?
}

class AuthorityGatedMotionEngine(
    private val artifact: CandidateMotionArtifact,
    private val authority: MotionArtifactAuthority,
    private val delegate: MotionEngine,
) : MotionEngine, AutoCloseable {
    override suspend fun infer(
        window: SensorFeatureWindow,
        ml0: Ml0BodyFrame,
        gravityReference: HarnessGravityReference,
        anchor: PetNavigationAnchor?,
    ): Result<ModelStageResult<MotionInference>> {
        val authorizedModel = authority.authorize(artifact)
        if (authorizedModel == null) {
            return Result.success(
                ModelStageResult.Blocked(
                    ModelStageBlock(
                        stage = ModelStage.MOTION,
                        reason = ModelBlockReason.MOTION_MODEL_UNAVAILABLE,
                        detail = "model=${artifact.modelId},version=${artifact.modelVersion},maturity=${artifact.maturity}",
                    ),
                ),
            )
        }
        return delegate.infer(window, ml0, gravityReference, anchor).map { result ->
            when (result) {
                is ModelStageResult.Produced -> ModelStageResult.Produced(result.value.copy(model = authorizedModel))
                is ModelStageResult.Blocked -> result
            }
        }
    }

    override fun close() {
        (delegate as? AutoCloseable)?.close()
    }
}
