# 소스 리뷰 가이드

전체 파일을 순서대로 읽을 필요는 없습니다. 아래 순서로 구현과 반증 테스트를 함께 보면 약 10분 안에 핵심 판단을 검토할 수 있습니다.

## 1. 끊겨도 bounded하게 보존하는 Wear spool

- 구현: [`SegmentedWearBatchSpool.kt`](../evidence/selected-source/android/SegmentedWearBatchSpool.kt)
- 테스트: [`SegmentedWearBatchSpoolTest.kt`](../evidence/selected-source/android/SegmentedWearBatchSpoolTest.kt)
- 확인할 불변식: 배치 수와 wire byte 상한, SHA-256 payload 검증, FIFO ACK, 재시작 복구, 잘린 tail 처리, 손상 segment 격리
- 과장하지 않는 범위: 저장장치 고장까지 포함한 절대 무손실을 보장한다는 주장이 아니라, 프로세스 재시작과 연결 단절에 대한 bounded spool 정책입니다.

## 2. durable commit 이후에만 ACK하는 ingress

- 구현: [`SensorIngressOwner.kt`](../evidence/selected-source/android/SensorIngressOwner.kt)
- 테스트: [`SensorIngressOwnerTest.kt`](../evidence/selected-source/android/SensorIngressOwnerTest.kt)
- 확인할 불변식: bounded channel, batch 크기·시간 상한, commit timeout, journal commit 결과가 돌아오기 전 성공 응답 금지, drain 직렬화
- 핵심 실패 경로: queue 포화, oversized batch, commit 예외와 timeout이 성공으로 변환되지 않는지 확인합니다.

## 3. 후보 모델의 제품 실행 권한을 분리하는 gate

- 실행 gate: [`AuthorityGatedMotionEngine.kt`](../evidence/selected-source/android/AuthorityGatedMotionEngine.kt)
- readiness 정책: [`ProductModelReadiness.kt`](../evidence/selected-source/android/ProductModelReadiness.kt)
- 반증 테스트: [`AuthorityGatedMotionEngineTest.kt`](../evidence/selected-source/android/AuthorityGatedMotionEngineTest.kt), [`ProductModelReadinessPolicyTest.kt`](../evidence/selected-source/android/ProductModelReadinessPolicyTest.kt)
- 확인할 불변식: artifact identity와 authority certificate 불일치 시 delegate 미실행, corpus·training·holdout·field·product-value 단계 중 하나라도 빠지면 READY 금지, ML1 미승격 시 ML2도 차단
- 설계 의도: 모델이 생성되었다는 사실을 제품 권한으로 해석하지 않습니다.

## 4. anchor 없는 PDR 출력을 제품 좌표로 만들지 않기

- 구현: [`AnchoredPdrEngine.kt`](../evidence/selected-source/android/AnchoredPdrEngine.kt)
- 테스트: [`AnchoredPdrEngineTest.kt`](../evidence/selected-source/android/AnchoredPdrEngineTest.kt)
- 확인할 불변식: 유효한 GNSS anchor 없이는 좌표를 내지 않음, 비정상 frame 거부, drift budget을 넘는 결과 차단
- 과장하지 않는 범위: 이 코드는 PDR 후보와 안전 경계를 보여 주며, 다중 사용자·다중 기기 정확도 검증 완료를 의미하지 않습니다.

## 5. ACK가 요청한 레코드와 같은지 검증하기

- 구현: [`WearSensorAckValidation.kt`](../evidence/selected-source/android/WearSensorAckValidation.kt)
- 테스트: [`WearSensorAckValidationTest.kt`](../evidence/selected-source/android/WearSensorAckValidationTest.kt)
- 확인할 불변식: batch, walk, session과 payload identity가 모두 맞아야 spool 제거를 허용합니다.

## 출처 검증

[`manifest.csv`](../evidence/selected-source/manifest.csv)는 봉인 revision, 원본 경로, 원본 blob SHA, 공개 snapshot blob SHA를 함께 기록합니다. 선별 snapshot은 원본 blob과 byte-identical입니다. 실제 좌표, 사용자 식별자, DB, 키와 내부 프롬프트는 포함하지 않습니다.
