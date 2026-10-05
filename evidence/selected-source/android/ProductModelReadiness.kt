package com.kullchip.next.mobile.learning

import com.kullchip.next.core.derivation.CandidateMotionArtifact
import com.kullchip.next.core.derivation.CandidateMotionArtifacts
import com.kullchip.next.core.derivation.ModelArtifactMaturity
import com.kullchip.next.core.learning.BehaviorAxis
import com.kullchip.next.core.learning.BehaviorLearningCatalog
import com.kullchip.next.core.learning.Ml1ClassCoverage
import com.kullchip.next.core.learning.Ml1CorpusGate
import com.kullchip.next.core.learning.Ml1CorpusGateResult
import com.kullchip.next.core.learning.Ml1HoldoutAxisCoverage
import com.kullchip.next.core.learning.Ml1HoldoutCorpusGate
import com.kullchip.next.core.learning.Ml1HoldoutCorpusGateResult
import com.kullchip.next.core.learning.Ml2ClassCoverage
import com.kullchip.next.core.learning.Ml2CorpusGate
import com.kullchip.next.core.learning.Ml2CorpusGateResult
import com.kullchip.next.core.learning.Ml2EpisodeLearningContract
import com.kullchip.next.core.runtime.PetId
import com.kullchip.next.mobile.model.MotionArtifactAuthority
import com.kullchip.next.mobile.model.PRODUCT_ML1_ARTIFACTS
import com.kullchip.next.mobile.model.PRODUCT_ML1_READINESS_ARTIFACTS
import com.kullchip.next.mobile.model.PRODUCT_ML2_ARTIFACTS
import com.kullchip.next.mobile.model.ProductCheckpointAuthorityEvidence
import com.kullchip.next.mobile.model.ProductCheckpointAuthorityPolicy
import com.kullchip.next.mobile.model.ProductCheckpointAuthoritySelection
import com.kullchip.next.mobile.model.ProductValueArtifactKey
import com.kullchip.next.mobile.model.ProductValueAuthority
import com.kullchip.next.mobile.model.ProductValueStage
import com.kullchip.next.mobile.storage.db.BehaviorModelLabelCoverageRow
import com.kullchip.next.mobile.storage.db.KullChipDatabase
import com.kullchip.next.mobile.storage.db.Ml2CorpusCoverageRow
import com.kullchip.next.mobile.storage.db.ProductModelCheckpointReadinessRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

enum class ProductModelStage {
    MOTION,
    BODY_GESTURE,
    EPISODE,
}

enum class ProductModelReadinessLevel {
    READY,
    COLLECTING,
    VERIFYING,
    BLOCKED,
}

enum class ProductModelReadinessReason {
    READY,
    MOTION_AUTHORITY_CERTIFICATE_REQUIRED,
    ML1_CORPUS_REQUIRED,
    ML1_TRAINING_REQUIRED,
    ML1_HOLDOUT_REQUIRED,
    ML1_FIELD_EVIDENCE_REQUIRED,
    ML1_PRODUCT_VALUE_EVIDENCE_REQUIRED,
    ML1_CHECKPOINT_INVALID,
    ML1_VALIDATION_BLOCKED,
    ML2_UPSTREAM_ML1_REQUIRED,
    ML2_CORPUS_REQUIRED,
    ML2_TRAINING_REQUIRED,
    ML2_HOLDOUT_REQUIRED,
    ML2_FIELD_EVIDENCE_REQUIRED,
    ML2_PRODUCT_VALUE_EVIDENCE_REQUIRED,
    ML2_CHECKPOINT_INVALID,
    ML2_VALIDATION_BLOCKED,
}

data class ProductModelStageReadiness(
    val stage: ProductModelStage,
    val level: ProductModelReadinessLevel,
    val reason: ProductModelReadinessReason,
    val readyUnitCount: Int,
    val requiredUnitCount: Int,
    val missingModelIds: List<String> = emptyList(),
    val validationDetail: String? = null,
) {
    init {
        require(readyUnitCount in 0..requiredUnitCount)
        require(missingModelIds == missingModelIds.distinct().sorted())
    }

    val ready: Boolean
        get() = level == ProductModelReadinessLevel.READY && readyUnitCount == requiredUnitCount
}

data class ProductModelReadinessState(
    val petId: PetId,
    val learning: PetLearningSummaryRead,
    val motion: ProductModelStageReadiness,
    val bodyGesture: ProductModelStageReadiness,
    val episode: ProductModelStageReadiness,
    val ml1Corpus: Ml1CorpusGateResult = Ml1CorpusGate.evaluate(emptyList()),
    val ml1HoldoutCorpus: Ml1HoldoutCorpusGateResult = Ml1HoldoutCorpusGate.evaluate(emptyList()),
    val ml2Corpus: Ml2CorpusGateResult = Ml2CorpusGate.evaluate(emptyList()),
) {
    init {
        require(learning.petId == petId)
        require(motion.stage == ProductModelStage.MOTION)
        require(bodyGesture.stage == ProductModelStage.BODY_GESTURE)
        require(episode.stage == ProductModelStage.EPISODE)
    }

    val stages: List<ProductModelStageReadiness>
        get() = listOf(motion, bodyGesture, episode)

    val productAuthorityReady: Boolean
        get() = stages.all(ProductModelStageReadiness::ready)

    val nextBlockingStage: ProductModelStageReadiness?
        get() = stages.firstOrNull { !it.ready }
}

data class ProductCheckpointReadinessEvidence(
    val checkpointId: String,
    val modelId: String,
    val baseArtifactSha256: String,
    val weightsSha256: String,
    val version: Long,
    val validationState: String,
    val active: Boolean,
    val lastValidationReason: String?,
    val lastValidatedAtEpochMillis: Long?,
    val upstreamAuthoritySha256: String? = null,
) {
    init {
        require(checkpointId.isNotBlank())
        require(modelId.isNotBlank())
        require(version > 0L)
    }
}

data class ProductModelReadinessEvidence(
    val petId: PetId,
    val learning: PetLearningSummaryRead,
    val ml1Corpus: Ml1CorpusGateResult,
    val ml1HoldoutCorpus: Ml1HoldoutCorpusGateResult,
    val ml2Corpus: Ml2CorpusGateResult,
    val checkpoints: List<ProductCheckpointReadinessEvidence>,
) {
    init {
        require(learning.petId == petId)
        require(checkpoints.map(ProductCheckpointReadinessEvidence::checkpointId).distinct().size == checkpoints.size)
    }
}

object ProductModelReadinessPolicy {
    fun evaluate(
        evidence: ProductModelReadinessEvidence,
        motionAuthorityReady: Boolean,
        productValueAuthorizedCheckpointIds: Set<String>,
        expectedMl1Artifacts: Map<String, String> = EXPECTED_ML1_ARTIFACTS,
        expectedMl2Artifacts: Map<String, String> = EXPECTED_ML2_ARTIFACTS,
    ): ProductModelReadinessState {
        requireExpectedArtifacts(expectedMl1Artifacts)
        requireExpectedArtifacts(expectedMl2Artifacts)
        require(productValueAuthorizedCheckpointIds.all { authorizedId ->
            evidence.checkpoints.any { it.checkpointId == authorizedId }
        })
        val allCheckpointAuthorityEvidence =
            evidence.checkpoints.map(ProductCheckpointReadinessEvidence::toAuthorityEvidence)
        val valueAuthorizedCheckpointEvidence = evidence.checkpoints
            .filter { checkpoint ->
                !checkpoint.active || checkpoint.checkpointId in productValueAuthorizedCheckpointIds
            }
            .map(ProductCheckpointReadinessEvidence::toAuthorityEvidence)
        val rawMl1Authority = ProductCheckpointAuthorityPolicy.select(
            expectedArtifacts = expectedMl1Artifacts,
            checkpoints = allCheckpointAuthorityEvidence,
        )
        val ml1Authority = ProductCheckpointAuthorityPolicy.select(
            expectedArtifacts = expectedMl1Artifacts,
            checkpoints = valueAuthorizedCheckpointEvidence,
        )
        val motion = if (motionAuthorityReady) {
            ready(ProductModelStage.MOTION, 1)
        } else {
            ProductModelStageReadiness(
                stage = ProductModelStage.MOTION,
                level = ProductModelReadinessLevel.BLOCKED,
                reason = ProductModelReadinessReason.MOTION_AUTHORITY_CERTIFICATE_REQUIRED,
                readyUnitCount = 0,
                requiredUnitCount = 1,
                missingModelIds = listOf(CandidateMotionArtifacts.displacement.modelId),
            )
        }
        val ml1 = evaluateCheckpointStage(
            stage = ProductModelStage.BODY_GESTURE,
            expectedArtifacts = expectedMl1Artifacts,
            checkpoints = evidence.checkpoints,
            authority = ml1Authority,
            rawAuthority = rawMl1Authority,
            productValueAuthorizedCheckpointIds = productValueAuthorizedCheckpointIds,
            corpusReady = evidence.ml1Corpus.collectionReady,
            holdoutCorpusReady = evidence.ml1HoldoutCorpus.productPromotionReady,
            corpusReason = ProductModelReadinessReason.ML1_CORPUS_REQUIRED,
            trainingReason = ProductModelReadinessReason.ML1_TRAINING_REQUIRED,
            holdoutReason = ProductModelReadinessReason.ML1_HOLDOUT_REQUIRED,
            fieldReason = ProductModelReadinessReason.ML1_FIELD_EVIDENCE_REQUIRED,
            productValueReason = ProductModelReadinessReason.ML1_PRODUCT_VALUE_EVIDENCE_REQUIRED,
            invalidReason = ProductModelReadinessReason.ML1_CHECKPOINT_INVALID,
            validationBlockedReason = ProductModelReadinessReason.ML1_VALIDATION_BLOCKED,
        )
        val ml1AuthoritySha256 = ProductCheckpointAuthorityPolicy.select(
            expectedArtifacts = PRODUCT_ML1_ARTIFACTS,
            checkpoints = valueAuthorizedCheckpointEvidence,
        ).ml1AuthoritySha256()
        val ml2 = if (ml1AuthoritySha256 == null) {
            ProductModelStageReadiness(
                stage = ProductModelStage.EPISODE,
                level = ProductModelReadinessLevel.BLOCKED,
                reason = ProductModelReadinessReason.ML2_UPSTREAM_ML1_REQUIRED,
                readyUnitCount = 0,
                requiredUnitCount = expectedMl2Artifacts.size,
                missingModelIds = expectedMl2Artifacts.keys.sorted(),
            )
        } else {
            evaluateCheckpointStage(
                stage = ProductModelStage.EPISODE,
                expectedArtifacts = expectedMl2Artifacts,
                checkpoints = evidence.checkpoints,
                authority = ProductCheckpointAuthorityPolicy.select(
                    expectedArtifacts = expectedMl2Artifacts,
                    checkpoints = valueAuthorizedCheckpointEvidence,
                    expectedUpstreamAuthoritySha256 = ml1AuthoritySha256,
                ),
                rawAuthority = ProductCheckpointAuthorityPolicy.select(
                    expectedArtifacts = expectedMl2Artifacts,
                    checkpoints = allCheckpointAuthorityEvidence,
                    expectedUpstreamAuthoritySha256 = ml1AuthoritySha256,
                ),
                productValueAuthorizedCheckpointIds = productValueAuthorizedCheckpointIds,
                corpusReady = evidence.ml2Corpus.candidateTrainingReady,
                holdoutCorpusReady = evidence.ml2Corpus.productPromotionReady,
                corpusReason = ProductModelReadinessReason.ML2_CORPUS_REQUIRED,
                trainingReason = ProductModelReadinessReason.ML2_TRAINING_REQUIRED,
                holdoutReason = ProductModelReadinessReason.ML2_HOLDOUT_REQUIRED,
                fieldReason = ProductModelReadinessReason.ML2_FIELD_EVIDENCE_REQUIRED,
                productValueReason = ProductModelReadinessReason.ML2_PRODUCT_VALUE_EVIDENCE_REQUIRED,
                invalidReason = ProductModelReadinessReason.ML2_CHECKPOINT_INVALID,
                validationBlockedReason = ProductModelReadinessReason.ML2_VALIDATION_BLOCKED,
            )
        }
        return ProductModelReadinessState(
            petId = evidence.petId,
            learning = evidence.learning,
            ml1Corpus = evidence.ml1Corpus,
            ml1HoldoutCorpus = evidence.ml1HoldoutCorpus,
            ml2Corpus = evidence.ml2Corpus,
            motion = motion,
            bodyGesture = ml1,
            episode = ml2,
        )
    }

    private fun evaluateCheckpointStage(
        stage: ProductModelStage,
        expectedArtifacts: Map<String, String>,
        checkpoints: List<ProductCheckpointReadinessEvidence>,
        authority: ProductCheckpointAuthoritySelection,
        rawAuthority: ProductCheckpointAuthoritySelection,
        productValueAuthorizedCheckpointIds: Set<String>,
        corpusReady: Boolean,
        holdoutCorpusReady: Boolean,
        corpusReason: ProductModelReadinessReason,
        trainingReason: ProductModelReadinessReason,
        holdoutReason: ProductModelReadinessReason,
        fieldReason: ProductModelReadinessReason,
        productValueReason: ProductModelReadinessReason,
        invalidReason: ProductModelReadinessReason,
        validationBlockedReason: ProductModelReadinessReason,
    ): ProductModelStageReadiness {
        val rowsByModel = expectedArtifacts.keys.associateWith { modelId ->
            checkpoints.filter { it.modelId == modelId }
        }
        val invalidActive = rawAuthority.invalidModelIds.isNotEmpty()
        val readyModels = authority.selectedByModelId.keys
        val missing = (expectedArtifacts.keys - readyModels).sorted()
        if (invalidActive) {
            return stageState(stage, ProductModelReadinessLevel.BLOCKED, invalidReason, readyModels.size, expectedArtifacts, missing)
        }
        if (missing.isEmpty()) return ready(stage, expectedArtifacts.size)

        val latestRows = missing.mapNotNull { modelId ->
            rowsByModel.getValue(modelId)
                .filter { row -> row.baseArtifactSha256 == expectedArtifacts.getValue(modelId) }
                .maxWithOrNull(
                compareBy<ProductCheckpointReadinessEvidence> { it.version }
                    .thenBy { it.checkpointId },
            )
        }
        if (latestRows.any { row ->
                row.active &&
                    row.validationState == PRODUCT_ACTIVE &&
                    row.checkpointId !in productValueAuthorizedCheckpointIds
            }
        ) {
            return stageState(
                stage,
                ProductModelReadinessLevel.VERIFYING,
                productValueReason,
                readyModels.size,
                expectedArtifacts,
                missing,
            )
        }
        val latestReason = latestRows.mapNotNull(ProductCheckpointReadinessEvidence::lastValidationReason).firstOrNull()
        if (latestRows.any { it.lastValidationReason.isFieldGateReason() }) {
            return stageState(
                stage,
                ProductModelReadinessLevel.VERIFYING,
                fieldReason,
                readyModels.size,
                expectedArtifacts,
                missing,
                latestReason,
            )
        }
        if (!corpusReady) {
            return stageState(
                stage,
                ProductModelReadinessLevel.COLLECTING,
                corpusReason,
                readyModels.size,
                expectedArtifacts,
                missing,
                latestReason,
            )
        }
        if (latestRows.isEmpty()) {
            return stageState(
                stage,
                ProductModelReadinessLevel.COLLECTING,
                trainingReason,
                readyModels.size,
                expectedArtifacts,
                missing,
            )
        }
        if (!holdoutCorpusReady) {
            return stageState(
                stage,
                ProductModelReadinessLevel.VERIFYING,
                holdoutReason,
                readyModels.size,
                expectedArtifacts,
                missing,
                latestReason,
            )
        }
        if (latestRows.any { row -> row.validationState == AWAITING_HOLDOUT && row.lastValidationReason.isHoldoutReason() }) {
            return stageState(
                stage,
                ProductModelReadinessLevel.VERIFYING,
                holdoutReason,
                readyModels.size,
                expectedArtifacts,
                missing,
                latestReason,
            )
        }
        if (latestRows.any { it.validationState == AWAITING_HOLDOUT && it.lastValidationReason == null }) {
            return stageState(
                stage,
                ProductModelReadinessLevel.VERIFYING,
                holdoutReason,
                readyModels.size,
                expectedArtifacts,
                missing,
            )
        }
        if (latestRows.any { row ->
                row.validationState == AWAITING_HOLDOUT &&
                    row.lastValidationReason.isCandidateStaleReason()
            }
        ) {
            return stageState(
                stage,
                ProductModelReadinessLevel.COLLECTING,
                trainingReason,
                readyModels.size,
                expectedArtifacts,
                missing,
                latestReason,
            )
        }
        if (latestReason != null) {
            return stageState(
                stage,
                ProductModelReadinessLevel.BLOCKED,
                validationBlockedReason,
                readyModels.size,
                expectedArtifacts,
                missing,
                latestReason,
            )
        }
        return stageState(
            stage,
            ProductModelReadinessLevel.COLLECTING,
            trainingReason,
            readyModels.size,
            expectedArtifacts,
            missing,
        )
    }

    private fun stageState(
        stage: ProductModelStage,
        level: ProductModelReadinessLevel,
        reason: ProductModelReadinessReason,
        readyCount: Int,
        expected: Map<String, String>,
        missing: List<String>,
        detail: String? = null,
    ) = ProductModelStageReadiness(
        stage = stage,
        level = level,
        reason = reason,
        readyUnitCount = readyCount,
        requiredUnitCount = expected.size,
        missingModelIds = missing,
        validationDetail = detail,
    )

    private fun ready(stage: ProductModelStage, unitCount: Int) = ProductModelStageReadiness(
        stage = stage,
        level = ProductModelReadinessLevel.READY,
        reason = ProductModelReadinessReason.READY,
        readyUnitCount = unitCount,
        requiredUnitCount = unitCount,
    )

    private fun requireExpectedArtifacts(expected: Map<String, String>) {
        require(expected.isNotEmpty())
        require(expected.keys.all(String::isNotBlank))
        require(expected.values.all(SHA_256_REGEX::matches))
    }

    private fun String?.isFieldGateReason(): Boolean = this != null && FIELD_REASON_TOKENS.any(::contains)

    private fun String?.isHoldoutReason(): Boolean = this != null && HOLDOUT_REASON_TOKENS.any(::contains)

    private fun String?.isCandidateStaleReason(): Boolean = this?.contains("_candidate_stale:") == true

    private val FIELD_REASON_TOKENS = listOf("field_gate", "device_two_hour_gate", "thermal_gate")
    private val HOLDOUT_REASON_TOKENS = listOf("holdout_", "holdout:")
    private const val AWAITING_HOLDOUT = "AWAITING_HOLDOUT"
    private const val PRODUCT_ACTIVE = "PRODUCT_ACTIVE"
}

interface ProductModelReadinessStore {
    fun observe(petId: PetId): Flow<ProductModelReadinessEvidence>
}

class RoomModelCorpusReadStore(
    private val database: KullChipDatabase,
) {
    fun observeMl1(petId: String): Flow<Ml1CorpusGateResult> =
        observeMl1RoleCoverage(
            petId = petId,
            role = ModelWalkRole.TRAIN.name,
            intervalLimit = Ml1CorpusGate.MIN_INTERVALS_PER_CLASS,
            walkLimit = Ml1CorpusGate.MIN_WALKS_PER_CLASS,
        ).map { rows -> Ml1CorpusGate.evaluate(rows.toMl1Coverage()) }

    fun observeMl1Holdout(petId: String): Flow<Ml1HoldoutCorpusGateResult> =
        observeMl1RoleCoverage(
            petId = petId,
            role = ModelWalkRole.HOLDOUT.name,
            intervalLimit = Ml1HoldoutCorpusGate.MIN_EXAMPLES_PER_CLASS,
            walkLimit = Ml1HoldoutCorpusGate.MIN_WALKS_PER_MODEL,
        ).map { rows -> Ml1HoldoutCorpusGate.evaluate(rows.toMl1HoldoutCoverage()) }

    private fun observeMl1RoleCoverage(
        petId: String,
        role: String,
        intervalLimit: Long,
        walkLimit: Long,
    ): Flow<List<BehaviorModelLabelCoverageRow>> {
        val flows = BehaviorLearningCatalog.intervalAxes.map { axis ->
            database.learningDao().observeBoundedRoleLabelCoverage(
                petId = petId,
                modelId = BehaviorLearningCatalog.artifact(axis).modelId,
                axis = axis.name,
                role = role,
                intervalLimit = intervalLimit,
                walkLimit = walkLimit,
            )
        }
        return combine(flows) { rows -> rows.flatMap { it } }
    }

    fun observeMl2(petId: String): Flow<Ml2CorpusGateResult> {
        val dao = database.learningDao()
        val flows = Ml2CorpusGate.REQUIRED_LABELS.flatMap { label ->
            listOf(
                dao.observeBoundedMl2CorpusCoverage(
                    petId = petId,
                    modelId = Ml2EpisodeLearningContract.MODEL_ID,
                    label = label,
                    role = ModelWalkRole.TRAIN.name,
                    candidateWalkLimit = Ml2CorpusGate.MIN_PRODUCT_TRAIN_EXAMPLES_PER_CLASS,
                    exampleLimit = Ml2CorpusGate.MIN_PRODUCT_TRAIN_EXAMPLES_PER_CLASS,
                ),
                dao.observeBoundedMl2CorpusCoverage(
                    petId = petId,
                    modelId = Ml2EpisodeLearningContract.MODEL_ID,
                    label = label,
                    role = ModelWalkRole.HOLDOUT.name,
                    candidateWalkLimit = Ml2CorpusGate.MIN_HOLDOUT_EXAMPLES_PER_CLASS,
                    exampleLimit = Ml2CorpusGate.MIN_HOLDOUT_EXAMPLES_PER_CLASS,
                ),
            )
        }
        return combine(flows) { rows -> Ml2CorpusGate.evaluate(rows.toList().toMl2Coverage()) }
    }
}

class RoomProductModelReadinessStore(
    private val database: KullChipDatabase,
    private val corpus: RoomModelCorpusReadStore = RoomModelCorpusReadStore(database),
) : ProductModelReadinessStore {
    override fun observe(petId: PetId): Flow<ProductModelReadinessEvidence> = combine(
        database.learningDao().observePetLearningSummary(petId.value, LEARNING_COUNT_QUERY_LIMIT),
        corpus.observeMl1(petId.value),
        corpus.observeMl1Holdout(petId.value),
        corpus.observeMl2(petId.value),
        database.learningDao().observeProductModelCheckpointReadiness(
            petId = petId.value,
            modelIds = EXPECTED_PRODUCT_MODEL_IDS,
        ),
    ) { summary, ml1, ml1Holdout, ml2, checkpoints ->
        ProductModelReadinessEvidence(
            petId = petId,
            learning = summary.toDomain(petId),
            ml1Corpus = ml1,
            ml1HoldoutCorpus = ml1Holdout,
            ml2Corpus = ml2,
            checkpoints = checkpoints.map(ProductModelCheckpointReadinessRow::toEvidence),
        )
    }.distinctUntilChanged()
}

class ProductModelReadinessOwner(
    private val store: ProductModelReadinessStore,
    private val motionAuthority: MotionArtifactAuthority,
    private val productValueAuthority: ProductValueAuthority,
    private val motionArtifact: CandidateMotionArtifact = CandidateMotionArtifacts.displacement,
) {
    fun observe(petId: PetId): Flow<ProductModelReadinessState> = store.observe(petId)
        .map { evidence ->
            ProductModelReadinessPolicy.evaluate(
                evidence = evidence,
                motionAuthorityReady = motionAuthorityReady(),
                productValueAuthorizedCheckpointIds = productValueAuthorizedCheckpointIds(evidence),
            )
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)

    suspend fun snapshot(petId: PetId): ProductModelReadinessState = observe(petId).first()

    private fun motionAuthorityReady(): Boolean = runCatching {
        val authorized = motionAuthority.authorize(motionArtifact) ?: return false
        authorized.modelId == motionArtifact.modelId &&
            authorized.modelVersion == motionArtifact.modelVersion &&
            authorized.artifactSha256 == motionArtifact.sha256 &&
            authorized.maturity == ModelArtifactMaturity.PRODUCT_AUTHORITY
    }.getOrDefault(false)

    private fun productValueAuthorizedCheckpointIds(
        evidence: ProductModelReadinessEvidence,
    ): Set<String> = evidence.checkpoints
        .asSequence()
        .filter(ProductCheckpointReadinessEvidence::active)
        .mapNotNull { checkpoint ->
            val stage = when (checkpoint.modelId) {
                in EXPECTED_ML1_ARTIFACTS -> ProductValueStage.ML1
                in EXPECTED_ML2_ARTIFACTS -> ProductValueStage.ML2
                else -> return@mapNotNull null
            }
            val key = ProductValueArtifactKey(
                stage = stage,
                modelId = checkpoint.modelId,
                artifactSha256 = checkpoint.baseArtifactSha256,
                weightsSha256 = checkpoint.weightsSha256,
                upstreamAuthoritySha256 = checkpoint.upstreamAuthoritySha256,
            )
            checkpoint.checkpointId.takeIf {
                runCatching { productValueAuthority.authorize(key) }.getOrDefault(false)
            }
        }
        .toSet()
}

private fun List<BehaviorModelLabelCoverageRow>.toMl1Coverage(): List<Ml1ClassCoverage> = mapNotNull { row ->
    val axis = runCatching { BehaviorAxis.valueOf(row.axis) }.getOrNull() ?: return@mapNotNull null
    row.takeIf { BehaviorLearningCatalog.artifact(axis).modelId == it.modelId }?.let {
        Ml1ClassCoverage(
            axis = axis,
            label = it.label,
            intervalCount = it.intervalCount,
            walkCount = it.walkCount,
        )
    }
}

private fun List<BehaviorModelLabelCoverageRow>.toMl1HoldoutCoverage(): List<Ml1HoldoutAxisCoverage> =
    groupBy(BehaviorModelLabelCoverageRow::axis).mapNotNull { (axisName, rows) ->
        val axis = runCatching { BehaviorAxis.valueOf(axisName) }.getOrNull() ?: return@mapNotNull null
        val artifact = BehaviorLearningCatalog.artifact(axis)
        val matching = rows.filter { row -> row.modelId == artifact.modelId }
        Ml1HoldoutAxisCoverage(
            axis = axis,
            exampleCountByLabel = matching.associate { row -> row.label to row.intervalCount },
            walkCount = matching.maxOfOrNull(BehaviorModelLabelCoverageRow::modelWalkCount) ?: 0L,
        )
    }

private fun List<Ml2CorpusCoverageRow>.toMl2Coverage(): List<Ml2ClassCoverage> =
    groupBy(Ml2CorpusCoverageRow::label).map { (label, rows) ->
        val train = rows.singleOrNull { it.role == ModelWalkRole.TRAIN.name }
        val holdout = rows.singleOrNull { it.role == ModelWalkRole.HOLDOUT.name }
        Ml2ClassCoverage(
            label = label,
            trainExampleCount = train?.exampleCount ?: 0,
            trainWalkCount = train?.walkCount ?: 0,
            holdoutExampleCount = holdout?.exampleCount ?: 0,
            holdoutWalkCount = holdout?.walkCount ?: 0,
        )
    }

private fun ProductModelCheckpointReadinessRow.toEvidence() = ProductCheckpointReadinessEvidence(
    checkpointId = checkpointId,
    modelId = modelId,
    baseArtifactSha256 = baseArtifactSha256,
    weightsSha256 = weightsSha256,
    version = version,
    validationState = validationState,
    active = isActive,
    lastValidationReason = lastValidationReason,
    lastValidatedAtEpochMillis = lastValidatedAtEpochMillis,
    upstreamAuthoritySha256 = upstreamAuthoritySha256,
)

private fun ProductCheckpointReadinessEvidence.toAuthorityEvidence() = ProductCheckpointAuthorityEvidence(
    checkpointId = checkpointId,
    modelId = modelId,
    baseArtifactSha256 = baseArtifactSha256,
    weightsSha256 = weightsSha256,
    version = version,
    validationState = validationState,
    active = active,
    upstreamAuthoritySha256 = upstreamAuthoritySha256,
)

internal val EXPECTED_ML1_ARTIFACTS: Map<String, String> = PRODUCT_ML1_READINESS_ARTIFACTS

internal val EXPECTED_ML2_ARTIFACTS: Map<String, String> = PRODUCT_ML2_ARTIFACTS

private val EXPECTED_PRODUCT_MODEL_IDS = (EXPECTED_ML1_ARTIFACTS.keys + EXPECTED_ML2_ARTIFACTS.keys).sorted()
private val SHA_256_REGEX = Regex("[0-9a-f]{64}")
