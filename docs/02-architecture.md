# 시스템 아키텍처

## 데이터 권한

KullChip 8.1.0의 핵심 규칙은 파생 결과보다 관측 RAW를 먼저 보존하는 것입니다.

```text
Phone/Wear input
  → bounded transport
  → SensorIngressOwner
  → Room RAW batch commit
  → CommittedSensorBatch
  → SensorDerivationOwner
  → route / feature / model projections
  → UI read model
```

파생 단계가 실패해도 RAW 사실은 남아 재처리할 수 있습니다. 반대로 RAW commit에 실패하면 후속 처리 성공처럼 보이는 결과를 만들지 않습니다.

## Phone/Wear 전송

Wear는 연결을 신뢰하지 않습니다.

- 메모리 무제한 큐 대신 bounded segmented spool 사용
- batch identity와 source/walk/time lineage 보존
- 모바일 durable commit 이후에만 ACK 처리
- 동일 batch 재전송은 멱등 처리
- STOP 이후 오래된 START 재생을 terminal fence로 차단
- ACK timeout 시 데이터를 삭제하지 않고 연결 복구 뒤 재전송

## 단일 판단 owner

`WalkRuntimeOwner`는 산책 시작·종료·복구를 직렬화합니다. `SensorDerivationOwner`는 RAW와 model window의 처리를 bounded하게 교차시키며, 어느 한쪽 backlog가 다른 쪽을 영구 기아 상태로 만들지 않도록 합니다.

## 제품과 검증의 분리

제품 앱과 validation 앱은 같은 핵심 파이프라인을 사용하지만 외부 데이터 주입과 runtime probe는 validation 변형에만 존재합니다. 후보 모델은 Model Lab에서 평가할 수 있지만 제품 UI로 승격하려면 별도 제품 가치 인증을 통과해야 합니다.

## Backend 경계

원본 프로젝트에는 Rust 다중 crate 서버, tonic gRPC-Web 서버, ConnectRPC TypeScript 클라이언트, Axum, SQLx/PostgreSQL, 인증·동기화·소셜·알림·관리자·배치 영역이 있었습니다. 이 공개본은 native Connect 서버나 운영 서비스라고 주장하지 않습니다. 공개본의 Rust 예제는 합성 식별자만 사용하는 bounded delta sync 정책을 독립 실행형으로 재구성합니다.
