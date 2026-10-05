# 10분 코드·아키텍처 워크스루

## 0:00–1:00 — 문제와 계보

- ColaZZang에서 Phone/Wear 산책 기록을 단독 구현
- 연결 단절, 발열과 데이터 판단권 분산을 경험
- KullChip을 별도 clean-room 구조로 시작

## 1:00–3:00 — RAW와 전송 무결성

- `SegmentedWearBatchSpool`의 bounded append와 ACK 삭제 조건
- `SensorIngressOwner`의 RAW commit 선행 규칙
- 재전송·중복·복구에서 lineage를 유지하는 방법

## 3:00–5:00 — 단일 owner와 읽기 모델

- runtime 상태를 한 owner가 직렬화하는 이유
- derivation backlog와 live model window 사이의 공정성
- UI가 RAW를 직접 계산하지 않고 Room projection만 읽는 이유

## 5:00–7:00 — 모델 승격 gate

- artifact SHA와 checkpoint 일치
- walk 단위 train/holdout 분리
- 기준선 우위와 시간 안정성
- 후보가 있어도 제품 READY가 되지 않는 조건

## 7:00–9:00 — 실패 지표

- PDR은 제한적 증분 가치가 있었지만 제품 권한을 보류
- ML1은 일부 분리 신호와 별개로 시간 안정성 실패
- ML2는 사건 표본 부족으로 가치 측정 자체가 불가능

## 9:00–10:00 — 엔지니어링 판단

- 관측 사실만 제품에 남김
- 미검증 추론은 fail-closed
- 재개 조건을 문서화하고 프로젝트 봉인
- AI 사용 범위와 사람이 소유한 판단 경계 설명
