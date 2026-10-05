# 선별 소스 설명

## Wear spool

- 구현: [`SegmentedWearBatchSpool.kt`](../evidence/selected-source/android/SegmentedWearBatchSpool.kt)
- 테스트: [`SegmentedWearBatchSpoolTest.kt`](../evidence/selected-source/android/SegmentedWearBatchSpoolTest.kt)
- 설계 불변식: 배치 수와 wire byte 상한, SHA-256 payload 검증, FIFO ACK, 재시작 복구, 잘린 tail 처리, 손상 segment 격리
- 주장 범위: 저장장치 고장까지 포함한 절대 무손실 보장이 아니라 프로세스 재시작과 연결 단절에 대한 bounded spool 정책

## Sensor ingress

- 구현: [`SensorIngressOwner.kt`](../evidence/selected-source/android/SensorIngressOwner.kt)
- 테스트: [`SensorIngressOwnerTest.kt`](../evidence/selected-source/android/SensorIngressOwnerTest.kt)
- 설계 불변식: bounded channel, batch 크기·시간 상한, commit timeout, journal commit 이전 성공 응답 금지, drain 직렬화
- 실패 경로: queue 포화, oversized batch, commit 예외와 timeout을 성공으로 변환하지 않음

## Model authority

- 실행 gate: [`AuthorityGatedMotionEngine.kt`](../evidence/selected-source/android/AuthorityGatedMotionEngine.kt)
- readiness 정책: [`ProductModelReadiness.kt`](../evidence/selected-source/android/ProductModelReadiness.kt)
- 테스트: [`AuthorityGatedMotionEngineTest.kt`](../evidence/selected-source/android/AuthorityGatedMotionEngineTest.kt), [`ProductModelReadinessPolicyTest.kt`](../evidence/selected-source/android/ProductModelReadinessPolicyTest.kt)
- 설계 불변식: artifact identity와 authority certificate가 불일치하면 delegate 미실행, corpus·training·holdout·field·product-value 단계 중 하나라도 빠지면 READY 금지, ML1 미승격 시 ML2 차단

## Anchored PDR

- 구현: [`AnchoredPdrEngine.kt`](../evidence/selected-source/android/AnchoredPdrEngine.kt)
- 테스트: [`AnchoredPdrEngineTest.kt`](../evidence/selected-source/android/AnchoredPdrEngineTest.kt)
- 설계 불변식: 유효한 GNSS anchor 없이는 좌표를 내지 않음, 비정상 frame 거부, drift budget 초과 결과 차단
- 주장 범위: PDR 후보와 안전 경계를 보여 주며 다중 사용자·다중 기기 정확도 검증 완료를 의미하지 않음

## Wear ACK validation

- 구현: [`WearSensorAckValidation.kt`](../evidence/selected-source/android/WearSensorAckValidation.kt)
- 테스트: [`WearSensorAckValidationTest.kt`](../evidence/selected-source/android/WearSensorAckValidationTest.kt)
- 설계 불변식: batch, walk, session과 payload identity가 모두 일치해야 spool 제거 허용

## 출처 검증

[`manifest.csv`](../evidence/selected-source/manifest.csv)는 봉인 revision, 원본 경로, 원본 blob SHA, 공개 snapshot blob SHA를 기록합니다. 선별 snapshot은 원본 blob과 byte-identical입니다. 실제 좌표, 사용자 식별자, DB, 키와 내부 프롬프트는 포함하지 않습니다.
