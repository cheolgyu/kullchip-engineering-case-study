# KullChip Engineering Case Study

[![public-case-study-safety](https://github.com/cheolgyu/kullchip-engineering-case-study/actions/workflows/safety.yml/badge.svg)](https://github.com/cheolgyu/kullchip-engineering-case-study/actions/workflows/safety.yml)

> ColaZZang은 휴대폰·Wear OS로 반려견 산책의 경로·시간·걸음·이력을 기록하는 local-first 앱입니다. KullChip은 사람·반려견 시계의 GNSS·IMU 수집과 온디바이스 ML0/ML1/ML2까지 확장해 clean-room으로 다시 설계한 후속 프로젝트이며, 제품 기준을 통과하지 못한 자동 추론은 최종 앱에서 차단했습니다.

이 저장소는 출시 제품의 전체 소스가 아닙니다. 비공개 원본 저장소의 **kullchip-8.1.0-sealed** 기준에서 개인정보, 위치 원본, 인증정보, 내부 프롬프트와 생성 산출물을 제외하고 설계 판단·대표 구현·집계 검증 결과만 선별한 공개 포트폴리오입니다.

[English summary](README.en.md) · [기술 계보](docs/01-lineage.md) · [시스템 아키텍처](docs/02-architecture.md) · [검증 해설](docs/03-validation.md) · [주장 범위](docs/06-claims-boundary.md)

## 한눈에 보는 결론

| 항목 | ColaZZang | KullChip 8.1 |
|---|---|---|
| 개발 기간 | 2025.02–2026.01 | 2026.03–2026.08 |
| 사용자가 하는 일 | 휴대폰·손목 시계로 산책을 제어하고 경로·시간·걸음·이력을 확인 | 휴대폰으로 산책·기억·일기·리포트를 보고, 선택적으로 사람/반려견 시계 센서를 연결 |
| 실행 환경 | Android Phone + Wear OS | Android Product App + Model Lab + Wear + Mobile Runtime |
| 원시 데이터 | 위치·걸음·랩을 Room에 직접 기록 | RAW를 Room 트랜잭션에 먼저 저장한 뒤 파생 처리 |
| Wear 동기화 | Data Layer 요청과 상태 목록 재전송 | bounded spool, session/sequence lineage, durable commit 이후 ACK |
| UI 데이터 | Room Flow를 조합해 지도·차트 렌더링 | bounded read model과 별도 ProductFeatureDatabase projection |
| Backend | 제품 경로에 연결된 실사용 backend 없음 | 역사적 서버 코드는 보존됐지만 8.1 최종 런타임에서는 비활성 |
| 최종 판단 | 동작하는 local-first 프로토타입 | 수집 구조는 개선했으나 핵심 ML 제품 가설 미달로 봉인 |

이 사례의 핵심은 코드량이나 모델 실행 성공이 아닙니다. **관측 사실, 파생 후보, 제품 기능의 권한을 분리하고 실패한 후보를 제품에서 차단한 과정**입니다.

## 두 프로젝트의 관계

ColaZZang과 KullChip은 이름만 바꾼 동일 코드베이스가 아닙니다.

~~~text
ColaZZang
  실제 Phone/Wear 산책 기록 구현
  → 위치 저장·지도 갱신·Wear 동기화·서비스 수명주기 병목 확인

2026.02 재구성 실험
  기존 문제를 다른 경계로 풀 수 있는지 검토

KullChip
  별도 저장소에서 clean-room 재설계
  → RAW-first / single owner / spool + ACK / product-value gate
  → 상대 위치·ML 가치 검증 실패
  → 자동 추론 비활성화 후 8.1 봉인
~~~

ColaZZang 기본 브랜치에는 303개 커밋과 132개 KST 활동일이 남아 있습니다. 저장소 기록상 AI 공동작성 표기는 없지만, Git 기록만으로 모든 도구 사용 여부를 증명할 수는 없습니다. 따라서 정확한 표현은 **장기간의 단일 작성자 이력으로 구축됐고 AI 공동작성 흔적이 없다**입니다.

KullChip에서는 AI coding agent를 구현·리팩터링·테스트·리뷰 가속 도구로 사용했습니다. 제품 문제, 데이터 수집, 아키텍처 경계, 합격 기준, 결과 채택·거절과 프로젝트 중단 판단은 직접 소유했습니다.

자세한 계보와 책임 구분은 [기술 계보](docs/01-lineage.md)와 [AI 사용과 책임 경계](docs/05-ai-assisted-development.md)에 정리했습니다.

## 1. ColaZZang

### 실행 환경과 모듈

ColaZZang은 보호자가 휴대폰이나 자신의 Wear OS 시계에서 산책을 시작·일시정지·저장하고, 산책 중에는 경로·시간·걸음·lap을 확인하며, 끝난 뒤에는 달력·지도·차트로 기록을 다시 보는 local-first 산책 앱이었습니다. 반려견 행동을 자동 판정하는 AI 앱이 아니라, 산책 기록과 Phone/Wear 제어를 먼저 끝까지 연결한 선행 제품이었습니다.

| 영역 | 구성 |
|---|---|
| Phone UI | Kotlin, Jetpack Compose, Hilt, Coroutines/Flow |
| Wear UI | Compose for Wear OS, Wear Data Layer |
| 수집 | Fused Location, 활동 인식, 걸음 센서, Foreground Service |
| 저장 | 단일 Room DB, 10개 entity와 2개 view |
| 표시 | Google Maps, Vico chart |
| 외부 SDK | Google Maps, Fused Location, AdMob |
| Android 기준 | compile/target SDK 36, min SDK 34, Java 21 |
| 모듈 | app, wear, core:data, core:domain, core:stopwatch, core:sync, core:style |

### 구현 기능

| 기능 | 상태 |
|---|---|
| 산책 시작·일시정지·재개·저장·초기화 | 구현 |
| Foreground GPS와 걸음 수집 | 구현 |
| 실시간 경로와 lap·chip marker | 구현 |
| 달력형 산책 이력과 산책 상세 | 구현 |
| 날짜 필터 지도, 속도·걸음 차트 | 구현 |
| 휴대폰↔Wear 제어와 상태 동기화 | 구현 |
| Wear Tile·Complication | 클래스 수준 프로토타입. 최종 manifest 등록은 비활성 |
| Wear에서 시작한 일부 시간 계산 | 미완성 경로 존재 |
| 원격 backend 동기화 | 제품 경로에는 없음 |

### 실제 데이터 흐름

~~~mermaid
flowchart TD
    A[Phone UI: 산책 시작] --> B[(Room: walk + START history)]
    B --> C[WalkManagerService]
    C --> D[LocationService]
    C --> E[TimerService]
    C --> F[SensorService]
    C --> G[LocationController]
    D --> H[(Room: location)]
    E --> I[(Room: stopwatch)]
    F --> J[(Room: step)]
    G --> K[(Room: speed and accuracy projection)]
    H --> L[Room Flow + ViewModel]
    I --> L
    J --> L
    K --> L
    L --> M[Live map / history / charts]

    W[Wear UI] --> X[Wear Data Layer]
    X --> Y[Phone DataLayerListenerService]
    Y --> Z[현재 위치에 lap 연결 + stopwatch write]
    Z --> B
    B --> Q[DBFlowSender]
    Q --> W
~~~

휴대폰에서 산책을 시작하면 walk와 START history를 Room에 저장하고, **WalkManagerService**가 위치·타이머·센서·제어 서비스를 시작합니다. 위치 callback마다 좌표와 초기 집계를 기록하고, Room Flow를 ViewModel이 조합해 지도·이력·차트를 갱신합니다.

Wear 명령은 **DataLayerListenerService**가 받아 현재 위치와 lap을 연결하고 stopwatch 상태를 Room에 기록합니다. 반대 방향에서는 **DBFlowSender**가 Room 변화를 관찰해 stopwatch·lap·chip 상태를 Wear로 보냅니다.

### 저장과 책임 경계

- 산책·위치·걸음·lap·chip의 기준 저장소는 휴대폰의 Room이었습니다.
- Wear는 명령과 표시를 담당했지만 독립적인 durable 저장 경계는 없었습니다.
- Google Maps와 AdMob은 외부 SDK였으며, 산책 데이터를 보관하는 클라우드 backend는 연결되지 않았습니다.
- 별도로 만든 Spring/Rust 서버 실험은 존재하지만 ColaZZang 앱과 일관된 운영 시스템으로 통합됐다고 주장하지 않습니다.
- 저장소에는 자동 CI/CD workflow가 없었습니다.
- AWS를 사용한 근거는 없습니다.

### 현장에서 드러난 병목

1. 고정밀 위치 요청 간격이 0이고 callback마다 Room write가 발생했습니다.
2. 경로 전체 목록을 Flow로 다시 읽고 이미 그린 선까지 재렌더링했습니다.
3. Wear 상태가 바뀔 때 lap·chip 전체 목록을 반복 직렬화했습니다.
4. Wear callback 안의 blocking 처리와 여러 Service·ViewModel의 상태 소유가 겹쳤습니다.
5. paging 구현은 주석 상태였고, 저장량이 커졌을 때의 경계가 완성되지 않았습니다.
6. Wear에서 발생한 pause/resume/save 일부 경로가 미구현 시간 계산을 호출했습니다.
7. 성능과 Wear 동기화 문제를 TODO로 기록했지만 장시간 필드 기준과 회귀 테스트는 충분하지 않았습니다.

ColaZZang의 가치는 이 문제들을 숨기지 않고 다음 아키텍처의 입력으로 만든 데 있습니다.

## 2. KullChip 8.1

### 목표와 실행 환경

KullChip은 보호자가 휴대폰에서 반려견 산책을 기록하고, 필요하면 자신의 시계와 반려견에게 부착한 Wear OS 시계의 GNSS·IMU를 함께 받아 관측 경로, 확인한 기억, 일기·사진 일기와 리포트를 보는 앱으로 재설계했습니다. 자동 몸짓·배변 전조·상대 위치는 사용자가 직접 확인하지 않아도 의미 있는 정보를 주기 위한 실험이었지만, 최종 8.1에서는 제품 기준을 통과하지 못해 일반 사용자 화면에서 차단했습니다.

기술적 목표는 산책을 다음 폐쇄 루프로 만드는 것이었습니다.

~~~text
산책
  → Phone / human watch / pet watch의 GNSS + 6축 IMU
  → ML0 몸축 정규화와 PDR 후보
  → ML1 행동 축 후보
  → ML2 사건 후보
  → 사용자 확인과 학습
  → 경로·리포트·사진 일기
~~~

최종 Android 구조는 다음 책임으로 분리했습니다.

| 모듈 | 책임 |
|---|---|
| Product App | 산책 기록, 지도, 이력·일기·사진 일기, 리포트 |
| Model Lab | 센서·PDR·ML 후보의 검증과 디버깅 |
| Wear | 센서 수집, bounded spool, 전송과 ACK |
| Mobile Runtime | 단일 산책 런타임, Room, ingress, 파생 처리 |
| Core / Wire | 도메인 정책, model contract, packet contract |

사람 시계와 반려견 시계는 역할이 다른 패킷을 만들지만 같은 ingress와 Room 경계를 통과합니다. 반려견 시계 수집 목표는 GNSS 약 1초, accelerometer/gyroscope 약 50 Hz, optional rotation 약 25 Hz였습니다. 이는 제품 전체가 해당 주기로 장시간 인증됐다는 뜻이 아니라 최종 수집 계약의 목표값입니다.

### 제품 기능과 실험 기능의 분리

| 구분 | 기능 | 최종 상태 |
|---|---|---|
| 관측 사실 | GNSS 산책 이력, 시간·속도 구성, 사용자 확인 marker | 제품 사실로 보존 |
| 로컬 제품 화면 | 지도, 이력, 일기·사진 일기, report, life-grid projection | 구현 경계 존재 |
| 결정론적 변환 | ML0 몸축 변환 | 공통 phone pipeline에서 생성하되 행동 의미를 부여하지 않음 |
| 경로 후보 | Motion model과 PDR | Model Lab 진단만 허용, 제품 경로 권한 없음 |
| 학습 후보 | ML1 atomic axes, ML2 episode 후보 | Model Lab에서만 후보 실행, 제품 권한 없음 |
| 상대 위치 | 두 GNSS와 IMU 기반 pet relative navigation | 제품 모드 비활성 |
| 소셜 | walk-room과 공유 기능 | 최종 durable contract 미구현 |
| 원격 갱신 | public marker refresh, hosted weather writer | 최종 런타임 비활성 |

### 전체 데이터 흐름

~~~mermaid
flowchart TD
    A[Product command] --> B[WalkRuntimeOwner]
    B --> C[Phone GNSS]
    B --> D[Wear PREPARE → READY → START]
    D --> E[Bounded segmented spool]
    C --> F[SensorIngressOwner]
    E --> F
    F --> G[(Room RAW batch transaction)]
    G -->|commit success| H[Wear ACK]
    G --> I[SensorDerivationOwner]
    I --> J[Observed GNSS route]
    I --> K[ML0 deterministic body frame]
    K --> L[Motion distance model]
    L --> S[PDR candidate]
    K --> M[ML1 six body axes]
    K --> T[ML1 leash head]
    M --> N[ML2 32-frame episode candidate]
    J --> O[(Room product projection)]
    S --> P{Product-value gate}
    M --> P
    T --> P
    N --> P
    P -->|certified| O
    P -->|not certified| Q[Model Lab only]
    O --> R[Product UI]
~~~

핵심 규칙은 **RAW durable commit 이전에는 파생 처리를 성공으로 인정하지 않는 것**입니다. 파생 단계가 실패해도 RAW는 남아 재처리할 수 있고, RAW 저장에 실패하면 후속 결과가 제품 사실처럼 보이지 않습니다.

Wear 연결은 항상 끊길 수 있다고 가정했습니다.

- 메모리 무제한 queue 대신 bounded segmented spool 사용
- source, walk, session, sequence, sample time lineage 보존
- 휴대폰 Room transaction 성공 이후에만 ACK
- 동일 batch 재전송은 멱등 처리
- 연결 재시도는 2초에서 최대 60초까지 증가
- ACK 누락 재시도는 5초에서 최대 60초까지 증가
- 오래된 START가 종료된 산책을 되살리지 못하도록 terminal fence 적용

산책 종료도 단순 service stop이 아니라 명시적인 상태 전이로 다뤘습니다.

~~~text
STOP_REQUESTED
  → DRAINING
  → COMMITTING
  → COMPLETED 또는 FAILED
~~~

배터리 온도 40°C 이상 또는 Android thermal 상태가 MODERATE 이상일 때 작업을 제한하는 로컬 thermal circuit breaker도 두었습니다. 다만 마지막 봉인 시점까지 비충전 2시간 실기기 필드 기준과 모든 단절·복구 조합을 통과하지는 못했습니다.

### 온디바이스 ML0 → PDR / ML1 → ML2

여기서 **온디바이스**는 모델이 반려견 시계에서 돈다는 뜻이 아닙니다. 반려견 시계는 GNSS·가속도·자이로와 선택적 회전 벡터를 수집해 spool로 보낼 뿐이며 Room, TFLite/LiteRT, 학습 정책과 제품 판정은 갖지 않습니다. 휴대폰의 **SensorIngressOwner**가 RAW를 Room에 commit한 뒤 **SensorDerivationOwner**와 **ModelComputeOwner**가 ML0, Motion/PDR, ML1, ML2를 실행합니다. 학습 가능한 head의 미세조정과 checkpoint 저장도 휴대폰에서 수행합니다.

아래의 `50×6`, 몸축 정렬, `27×32` 계약과 세부 평가 수치는 비공개 봉인 태그 **kullchip-8.1.0-sealed**의 코드·검증 보고서를 다시 대조한 요약입니다. 공개 `selected-source`에는 개인정보·RAW와 함께 전체 feature assembler, normalizer, ML2 구현을 포함하지 않았으므로, 공개본만으로 모델 pipeline 전체를 재현할 수 있다고 주장하지 않습니다.

#### 입력 창 gate

모델 앞단은 단순히 IMU 50개를 묶지 않습니다. 같은 walk·pet·source session의 decode된 **PET_WATCH_IMU**만 사용하고, 연속 sequence, 단조 증가 timestamp, 45~55 Hz 유효 cadence와 최대 60 ms 표본 간격을 만족한 50개를 1초 창으로 승인합니다. malformed packet, sequence gap, 시간 역전, cadence 이탈, 큰 간격과 종료 시 불완전 창은 사유와 lineage를 남기고 거부합니다. 거부는 RAW 삭제나 50 Hz 보간을 뜻하지 않습니다.

| 단계 | 휴대폰 입력 | 처리와 출력 | 제품 권한 경계 |
|---|---|---|---|
| ML0 | 승인된 1초 50×6 가속도·자이로 창, 중력 기준, 사용 가능하면 회전 벡터와 GNSS heading | 50×6을 forward/right/up 가속도와 각속도로 회전. alignment mode·coverage·quality·RAW/feature hash를 함께 저장 | 학습 분류가 아닌 결정론적 변환. 구조적으로 유효한 움직임을 noise로 버리지 않으며 행동 이름이나 제품 사실을 만들지 않음 |
| Motion | ML0 50×6 몸축 tensor와 실제 pet GNSS/PDR anchor | 별도 TFLite가 1초 이동 거리만 추론. gyro yaw와 causal GNSS speed를 결합해 50개 motion frame 생성 | 정확한 artifact certificate와 제품 가치 certificate가 없으면 **MOTION_MODEL_UNAVAILABLE**로 차단 |
| PDR | 권위가 확인된 Motion frame과 실제 pet GNSS 위치·bearing anchor | anchor에서 forward/right 이동량을 적분하고 위치·누적거리·accuracy·drift state 생성 | ML1과 독립. Motion 권한이 없거나 heading/GNSS anchor가 없으면 좌표를 발명하지 않음. 후보는 Model Lab 진단만 가능 |
| ML1 몸짓 | ML0 50×6 tensor | 공통 backbone/TFLite를 한 번 실행해 여섯 atomic axis의 26개 확률, 선택 class와 confidence 생성 | 축마다 label·checkpoint·holdout·승격 권한이 독립. 제품 준비 상태는 여섯 몸짓 축과 leash까지 일곱 축 모두의 현재 artifact/checkpoint/value certificate를 요구 |
| ML1 leash | 같은 ML0 50×6 tensor | 독립 head가 loose/neutral/taut/pulling/sudden tug 관계 확률 생성 | 장력 센서 측정값이 아니라 사용자 확정 구간을 학습한 관계 후보. PDR이나 ML2 입력으로 사용하지 않음 |
| ML2 | 같은 시점 ML1 몸짓 26개 확률+불확실도 1개로 만든 27차원 frame 32개 | 약 32초의 순서를 TFLite가 읽어 poop preparation, pee preparation, none 점수와 confidence 생성 | 결과 marker·미래 정답·leash는 입력에서 제외. 상류 ML1 지문과 pet별 ML2 checkpoint/value certificate가 모두 일치할 때만 제품 의미 허용 |

ML0의 두 정렬 경로는 다음과 같습니다.

- 50개 표본 모두에 유효한 quaternion이 있고 GNSS heading이 있으면 world 좌표를 heading 기준의 몸축으로 회전합니다.
- 그렇지 않으면 중력 방향을 up으로, 장착 기준을 forward/right로 만든 gravity/mount fallback을 사용합니다.
- 어느 경로를 썼는지, quaternion coverage와 alignment quality를 메타데이터에 남깁니다. fallback을 사용했다는 사실을 숨기지 않습니다.
- ML0 출력은 Motion/PDR과 ML1에 독립적으로 전달됩니다. ML1 실패가 PDR을 막거나 PDR 결과가 ML1 class를 바꾸지 않습니다.

여기서 **몸축**은 반려견의 해부학적 forward/right/up을 독립 측정 장비로 입증한 절대 정답이 아닙니다. 8.1이 보장한 것은 quaternion·GNSS heading 또는 중력·장착 가정으로 같은 RAW를 같은 50×6 좌표계에 재현하고, 사용한 정렬 방식과 품질을 추적하는 것입니다. 실제 몸 방향과의 오차는 별도 ground truth가 부족했으므로 ML0 결정성이나 회전 불변식 통과를 물리적 정렬 정확도 또는 행동 정확도로 확대하지 않습니다.

ML1의 **atomic axis**는 한 행동 이름으로 모든 것을 덮어쓰지 않기 위한 분해입니다.

| Axis | Class |
|---|---|
| movement | stop, slow walk, walk, run |
| posture | neutral, sit, down, roll over, rear up |
| head | forward, back, left, right, up, down |
| event | shake, scratch, circle, body rub, none |
| nose | sniffing, none |
| oral | chewing, drinking, panting, none |
| leash | loose, neutral, taut, pulling, sudden tug |

앞의 여섯 몸짓 축은 한 physical runtime과 공통 backbone을 사용하지만 label, checkpoint와 합격 여부는 축별로 분리합니다. leash는 같은 ML0 tensor를 읽는 독립 head입니다. 각 inference에는 model ID, artifact SHA, checkpoint ID, weights SHA와 feature hash를 연결해 “어떤 입력을 어떤 weights가 해석했는지”를 남깁니다.

ML2는 **사건이 이미 일어났다는 판정**이 아니라 사건 직전의 시간 패턴 후보입니다. 한 frame은 여섯 몸짓 축의 26개 실제 확률과 평균 불확실도 1개이며, 서로 다른 32개 시간창을 순서대로 사용합니다. 사용자가 산책 marker에서 POOP 또는 PEE를 확정하면 그 결과는 앞선 32개 frame에 학습 target을 붙이는 데만 쓰고 예측 입력에는 넣지 않습니다. 사건 주변을 제외한 none 구간도 별도 음성 예제로 필요합니다.

제품 승격은 단계별로 fail-closed합니다.

1. RAW와 feature window의 source/session/sequence/count/hash가 맞아야 합니다.
2. 모델 artifact ID·version·SHA와 feature schema가 실행 계약과 같아야 합니다.
3. ML1 각 축은 pet별로 정확히 하나의 **PRODUCT_ACTIVE** checkpoint와 weights hash를 가져야 합니다.
4. ML2의 32개 frame은 같은 ML1 artifact 세대여야 하며, 여섯 몸짓 checkpoint로 만든 동일한 upstream authority fingerprint를 가져야 합니다.
5. ML2 checkpoint도 같은 pet·artifact·upstream fingerprint에 묶인 **PRODUCT_ACTIVE** 상태여야 합니다.
6. 마지막으로 walk 단위 train/holdout 분리, 누출 0, 단순 baseline 우위, 시간 안정성, field·thermal gate를 포함한 8.1 제품 가치 certificate가 필요합니다.

Model Lab은 제품 지문이 없는 한 후보 세대와 **AWAITING_HOLDOUT** checkpoint를 명시적으로 선택해 진단할 수 있습니다. Product App은 선택된 제품 checkpoint와 certificate가 없으면 Motion/PDR/ML1/ML2를 실행하거나 projection에 올리지 않습니다. 후보 context를 Room에 보존하는 것은 학습 lineage일 뿐 제품 의미 승격이 아닙니다.

### 저장·권한 경계

| 저장 위치 | 소유 데이터와 권한 |
|---|---|
| Wear segmented spool | 연결 단절 중 batch 임시 보존. Room·모델·지도 없음 |
| Phone runtime Room | RAW packet, source/session/sequence/time lineage, 운영 상태와 파생 projection |
| ProductFeatureDatabase | life grid·일기·리포트용 저빈도 materialized projection |
| External dataset repository | manifest/hash로 참조하는 검증 데이터. 공개본에 RAW 없음 |
| Local Lake / Lance / OLAP | 5.x/6.x 역사적 실험. 8.1 최종 runtime 아님 |

제품 UI는 RAW 전체를 직접 scan하거나 학습·재추론하지 않습니다. 갱신 queue, 고정 크기 page와 materialized projection을 통해 원시 행 수에 비례하는 화면 작업을 피하도록 분리했습니다.

더 자세한 현재 경계는 [시스템 아키텍처](docs/02-architecture.md), 공개 가능한 대표 구현은 [선별 소스 설명](docs/07-selected-source-notes.md)에 있습니다.

## 병목에서 재설계까지

| 확인한 문제 | KullChip의 대응 | 남은 한계 |
|---|---|---|
| callback마다 위치 저장 | RAW batch transaction과 bounded 처리 | 비충전 장시간 필드 인증 미완료 |
| 전체 경로 Flow 재조회와 지도 전체 재렌더링 | 고정 page, incremental materialized projection | 최종 head 장시간 성능 기준 미통과 |
| Wear 상태 전체 재전송 | segmented spool, session/sequence fence, commit 후 ACK | 물리적 단절·프로세스 재시작 전 조합 미인증 |
| 여러 Service/ViewModel이 수명주기 소유 | WalkRuntimeOwner와 SensorDerivationOwner로 직렬화 | stop 이후 완전 quiescence 필드 증거 부족 |
| 수집·학습·추론이 같은 자원을 경쟁 | RAW-first, bounded reconciliation, 단일 training admission, pacing | 장시간 latency·thermal 기준 미통과 |
| 두 개의 noisy GNSS와 IMU로 상대 위치 추정 | uncertainty와 UNKNOWN, 제품 모드 비활성 | 독립 ground truth와 하드웨어 관측성 부족 |
| window 분류가 시간축에서 반복 반전 | walk-disjoint holdout, baseline 비교, flip budget, fail-closed | ML1/ML2 제품 가치 기준 실패 |
| 저사양 VM 안에서 서버 image build | GitHub Actions matrix build 후 GHCR image pull | 이후 원격 cloud 자체를 종료 |

설계 선택과 trade-off는 [엔지니어링 결정](docs/04-engineering-decisions.md)에 별도로 기록했습니다.

## Backend, CI/CD와 클라우드의 변화

### 역사적 개발 단계

5.x/6.x 개발 단계에는 다음 원격 구성을 실험했습니다.

~~~text
Android / Admin Web
  → Cloudflare Pages · DNS · Zero Trust
  → Caddy
  → gRPC/Connect · Admin API
  → PostgreSQL/PostGIS
  → FCM · object-storage client · batch
~~~

보존된 backend source에는 Rust multi-crate, Tokio, tonic, Axum, SQLx/PostgreSQL, Prometheus, JWT, H3와 cron scheduler가 있습니다. 계정·산책·돌봄·안전·소셜·알림·기억·실종견·관리자 등의 domain, unary API, live/share/SOS relay, FCM outbox notifier와 batch job을 나눴습니다. Admin은 React/Vite, ConnectRPC, Leaflet, Recharts를 사용했습니다.

이 구성은 **역사적 개발·배포 경로**이며 최종 8.1의 현재 운영 backend가 아닙니다.

초기에는 저사양 GCP VM에서 image를 직접 빌드해 15~20분 지연, 디스크 부족과 OOM을 겪었습니다. 이후 GitHub Actions에서 다중 서비스 image를 만들고 GHCR에 올린 뒤 VM은 pull/up만 수행하도록 바꿨습니다. 당시 내부 보고에서는 build가 3분 미만으로 단축됐습니다. 이 수치는 동일 조건의 현재 재현 benchmark가 아니라 역사적 운영 기록입니다.

### 봉인 시점의 최종 상태

| 항목 | 최종 상태 |
|---|---|
| Android CI | 수동 실행. contract, build/test/lint, emulator와 Wear instrumentation |
| Server CI | 수동 실행. Cargo workspace build/test |
| Android release | self-hosted Windows에서 build/audit와 artifact upload |
| Play 배포 | 자동 배포 없음 |
| 원격 서비스 | GCP, Firebase, Cloudflare, GHCR 기반 runtime 종료 |
| 데이터 저장 | 로컬 Room과 명시된 projection이 기준 |
| 외부 지도 | Google Maps SDK 사용 |
| AWS | 사용 근거 없음 |

따라서 이 저장소는 자동 배포 운영, 현재 cloud service, 사용자 traffic을 주장하지 않습니다.

## 검증 결과

가장 큰 검증 가능 데이터 snapshot은 다음과 같습니다.

- 산책 10회
- IMU 1,642,362행
- 반려견 GNSS 31,081행
- 사용자 축 label 749행
- episode 16행

원본 좌표, 시각, 사용자·반려동물·기기 식별값과 raw sample은 공개하지 않습니다.

### PDR: 후보로 남겼지만 제품 권한은 부여하지 않음

| GNSS hidden 구간 | PDR median / P95 | 고정 위치 baseline median / P95 | 마지막 속도 baseline median / P95 |
|---|---:|---:|---:|
| 5초, 1,661 segment | 1.34m / 5.91m | 2.73m / 9.92m | 1.53m / 6.87m |
| 10초, 825 segment | 2.33m / 10.99m | 5.30m / 16.78m | 3.29m / 13.91m |

단순 baseline보다 나았지만 단일 반려견의 과거 데이터였습니다. 다중 반려견, 독립 위치 ground truth와 현재 기기 장시간 검증이 없으므로 **KEEP_CANDIDATE_NOT_PRODUCT_AUTHORITY**로 판정했습니다.

### ML1/ML2: 제품 가치 기준 실패

먼저 기존 label window에서 신호 분리 가능성을 본 historical probe는 다음과 같았습니다. 이 수치는 아래의 untouched authority 산책 결과와 다른 평가 단계입니다.

| Axis | Probe macro-F1 / 다수 class baseline | 같은 정답 구간 상태 반전/시간 | 판단 |
|---|---:|---:|---|
| movement | 0.47 / 0.16 | 794.06 | 신호는 있으나 시간 불안정 |
| posture | 0.45 / 0.30 | 587.66 | 신호는 있으나 시간 불안정 |
| oral | 0.33 / 0.18 | 806.05 | 신호는 있으나 시간 불안정 |
| head | 0.18 / 0.08 | 875.73 | 산책별 일관성 부족 |
| event | 0.22 / 0.29 | 1,200.23 | baseline보다 낮음 |
| nose | 0.97 / 1.00 | 94.91 | 높은 수치지만 baseline 우위 없음 |

봉인 8.1 APK와 byte-identical한 artifact를 train 2산책, validation 1산책, untouched authority 1산책으로 다시 평가한 결과는 더 낮았습니다.

| 항목 | 측정 | 판단 |
|---|---:|---|
| movement untouched walk macro-F1 | 0.14 | 제품 가치 주장 불가 |
| oral untouched walk macro-F1 | 0.07 | 제품 가치 주장 불가 |
| movement untouched 상태 반전 | 시간당 93.12회 | 60회 기준 초과 |
| posture untouched 정답 | 0개 | 평가 불가 |
| ML2 확정 pee | 5건 / 4회 산책 | 독립 split 불가 |
| ML2 확정 poop | 1건 / 1회 산책 | 가치 평가 불가 |

따라서 794.06회와 93.12회는 모순되는 동일 지표가 아니라 **historical probe와 untouched authority 평가의 서로 다른 집계**입니다. 높은 단일 지표도 단순 baseline을 이기지 못하면 제품 가치로 인정하지 않았습니다. ML2는 32-frame 문맥은 만들 수 있었지만 독립 train/holdout과 전체 산책 candidate score timeline이 없어 탐지율·선행시간·시간당 오경보를 계산할 수 없었습니다.

[기계 판독용 공개 집계](evidence/validation/metrics.json)에는 식별정보 없는 dataset과 PDR·ML1 핵심 수치만 담았습니다. 원본 봉인 문서의 전체 판단은 [검증 해설](docs/03-validation.md)에 요약했습니다.

## 왜 중단했는가

최종 실패 원인은 한 모델의 정확도만이 아니었습니다.

1. 두 개의 noisy GNSS와 IMU만으로 요구한 상대 위치가 충분히 관측 가능하지 않았습니다.
2. ML0의 자세·하네스 조건과 실제 행동 의미를 혼동했습니다.
3. 라벨 시각과 sensor window의 의미 시각이 맞지 않았습니다.
4. 반려견·산책 수가 적어 독립 train/holdout과 일반화를 주장할 수 없었습니다.
5. ML1의 시간 불안정성이 ML2 입력을 흔들었습니다.
6. pipeline 실행, test 통과, 모델 artifact 생성을 사용자 가치와 혼동할 위험이 있었습니다.
7. bounded 처리로 일부 발열·backlog를 줄였지만 장시간 현장 기준을 일관되게 통과하지 못했습니다.

그래서 관측 GNSS와 사용자 확인 기록만 제품 사실로 남기고 상대 위치·PDR·ML 출력을 fail-closed했습니다.

재개하려면 최소한 UWB 등 독립 위치 장치/API, 독립 ground truth, 다중 반려견 corpus, 시간 안정성을 포함한 ML1 기준, ML2 train/holdout, 비충전 장시간 thermal·battery·quiescence 검증이 필요합니다.

## AI coding agent 사용과 책임

KullChip은 AI 보조 개발 사실을 숨기지 않습니다.

| AI agent가 보조한 일 | 직접 소유한 판단 |
|---|---|
| 구현 초안, 반복 refactor, test 추가, review 가속 | 어떤 사용자 문제를 풀 것인지 |
| 문서·contract 간 불일치 탐색 | 데이터와 runtime 권한 경계 |
| 실패 재현용 코드와 평가 도구 작성 보조 | 현장 데이터 수집과 개인정보 경계 |
| 대안 설계 비교 보조 | holdout·baseline·제품 합격 기준 |
| 대규모 변경의 기계적 작업 | 결과 채택·거절, 제품 노출 차단, 프로젝트 종료 |

agent가 코드를 생성했거나 test가 통과했다는 사실만으로 완료 처리하지 않았습니다. 실제 기기 데이터, 산책 단위 holdout, 단순 baseline 대비 증분 가치, 시간 안정성·오경보·P95, RAW lineage와 artifact SHA 일치를 요구했습니다.

ColaZZang은 저장소 이력상 장기간 단일 작성자 프로젝트이고, KullChip은 명시적인 AI-assisted 프로젝트입니다. 어느 쪽도 근거 없는 “100% 수기 작성” 또는 “AI가 전부 작성” 비율을 주장하지 않습니다.

## 공개한 코드 증거

**evidence/selected-source**에는 원본 8.1.0에서 선별한 다음 구현과 test가 있습니다.

1. 연결 단절을 견디는 Wear segmented spool
2. RAW commit 이전 파생 처리를 금지하는 sensor ingress
3. artifact·holdout·baseline·품질 gate가 일치해야 READY가 되는 model readiness
4. GNSS anchor와 IMU motion을 결합하는 PDR 후보와 test
5. 최대 500건 page, retention gap, version lane, 단조 cursor ACK를 보여 주는 [독립 Rust bounded-sync 예제](examples/bounded-sync/README.md)

Kotlin 파일은 전체 앱 배포본이 아니라 설계와 test 방식을 검토하기 위한 source snapshot입니다. 원본 경로와 blob SHA는 [manifest](evidence/selected-source/manifest.csv)에 기록했습니다. Rust 예제는 원본 운영 코드를 복사하지 않고 합성 식별자만으로 재구성했으며 독립적으로 build/test할 수 있습니다.

[선별 소스 설명](docs/07-selected-source-notes.md) · [주장 범위](docs/06-claims-boundary.md)

## 이 저장소가 주장하지 않는 것

- 반려견 행동 인식 또는 상대 위치 추정의 제품화 성공
- PDR·ML1·ML2의 다중 반려견 일반화
- 대규모 사용자·traffic·매출
- 현재 운영 중인 backend나 cloud service
- AWS 기반 시스템
- 자동 Play 배포
- KullChip 전체 코드의 AI 없는 단독 수기 작성
- Tile·Complication이 ColaZZang release에서 활성화됐다는 주장
- LanceDB·Local Lake·OLAP·Gemma가 8.1 최종 runtime에서 운영됐다는 주장

## 공개 저장소 안전 원칙

- 원본 Git 이력은 가져오지 않습니다.
- 실제 GPS, DB, token, key, 사용자 prompt와 기기 log를 포함하지 않습니다.
- 집계 지표만 공개하며 개인·반려동물·기기 식별값을 제거합니다.
- 공개 전 **scripts/privacy_guard.py**와 GitHub Actions로 파일명·비밀 pattern·고정밀 좌표 형태를 검사합니다.

보안 문제 신고와 공개 경계는 [SECURITY.md](SECURITY.md), [PRIVACY.md](PRIVACY.md), [THIRD_PARTY.md](THIRD_PARTY.md)를 참고하십시오.
