# 주장 범위

## 구현·검증 근거가 있는 주장

- Android Phone/Wear 센서 수집과 Room RAW 저장
- segmented spool, ACK, 재전송과 복구 경계
- runtime/derivation owner 기반 상태 직렬화
- 제품·validation·Model Lab 권한 분리
- walk 단위 PDR/ML 평가 도구와 집계 결과
- Rust tonic gRPC-Web/PostgreSQL backend와 관리자·batch 구성, ConnectRPC TypeScript client 연동
- 미인증 모델을 제품에서 차단한 최종 정책

## 제한적으로만 주장하는 내용

- PDR: 단일 펫 과거 데이터에서 기준선보다 나은 후보
- ML1: 일부 통계 신호가 있으나 현재 artifact의 제품 가치는 검증 실패
- ML2: 표본 부족으로 가치 미판정
- CI: 개발 중 성공 기록은 있으나 봉인 태그 전체를 이 공개본에서 다시 빌드한 것은 아님

## 주장하지 않는 내용

- 상용 출시 또는 현재 운영
- 행동·감정 인식 제품화 성공
- 상대 위치 정확도 목표 달성
- 대규모 사용자·매출·트래픽
- 최종 코드 전체의 단독 수기 작성
- 과거 연구 설계가 최종 제품 런타임에 모두 포함됐다는 주장
