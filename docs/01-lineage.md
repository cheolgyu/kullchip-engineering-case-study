# ColaZZang → KullChip 기술 계보

## ColaZZang: 선행 프로토타입

ColaZZang은 2025년 2월부터 2026년 1월까지 개발한 Android Phone/Wear 산책 기록 앱입니다. 기본 브랜치에는 단일 작성자 303개 커밋과 132일의 개발 활동이 남아 있으며, 저장소 기록상 AI 공동작성 표기는 없습니다.

주요 구현 범위:

- Kotlin·Compose 기반 Phone/Wear UI
- Room 기반 산책·위치·센서·lap·chip 기록
- Google Maps 경로와 산책 통계
- Wear Data Layer, Tile, Complication
- Foreground service와 산책 타이머

Git 기록만으로 도구 사용을 완전히 증명할 수는 없으므로 “AI를 단 한 번도 사용하지 않았다”고 주장하지 않습니다. 정확한 표현은 **기본 브랜치가 장기간 단일 작성자 이력으로 구축됐고 AI 공동작성 흔적이 없다**입니다.

## KullChip: clean-room 재설계

KullChip은 ColaZZang의 코드를 병합한 동일 저장소가 아닙니다. 현장에서 확인한 발열, 연결 단절, 중복 소비자, 데이터 소유권과 검증 부족을 바탕으로 2026년 3월 별도 저장소에서 다시 시작했습니다.

핵심 변화:

| ColaZZang에서 드러난 문제 | KullChip의 대응 |
|---|---|
| Phone/Wear 연결 단절 시 데이터 경계가 불명확 | segmented spool, ACK, 재전송, session fence |
| 수집·가공·UI 판단이 여러 곳에 분산 | RAW-first ingress와 single derivation/runtime owner |
| 화면이 원시 데이터와 계산을 직접 소비 | Room projection 기반 읽기 모델 |
| 실행 성공을 기능 가치로 오인 | walk holdout·기준선·시간 안정성·제품 가치 gate |
| 실험 기능과 제품 기능의 경계 부족 | product, validation, Model Lab 권한 분리 |

따라서 관계는 “코드 승계”보다 **문제 발견 → 재설계**로 설명하는 것이 정확합니다.
