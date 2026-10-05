# 주요 엔지니어링 결정

## 1. RAW before derived

가공 결과보다 원본 관측을 먼저 durable commit합니다. 재처리 가능성과 데이터 계보를 보존하고, 파생 파이프라인 실패가 관측 사실을 지우지 않게 합니다.

## 2. Bounded by default

센서·지도·UI·학습 큐에 무제한 축적을 허용하지 않습니다. paging, batch, 최신 station과 명시적 backpressure를 사용합니다.

## 3. One decision owner

동일한 상태를 서비스·ViewModel·worker가 각각 판단하지 않습니다. runtime과 derivation의 변경 권한을 owner에 모으고 UI는 projection을 읽습니다.

## 4. Fail closed

artifact SHA, train/holdout, 기준선 우위, 시간 안정성 또는 field evidence가 맞지 않으면 후보를 제품 사실로 노출하지 않습니다.

## 5. Runtime success is not product value

처리 건수, checkpoint version과 학습 job 성공은 정확도나 사용자 개선의 증거가 아닙니다. 실제 산책의 독립 평가가 없으면 제품 준비 완료로 표시하지 않습니다.

## 6. Stop conditions are part of design

기술적으로 흥미로운 후보를 계속 확장하는 대신 관측 가능성, 데이터 규모, field 안정성에 재개 조건을 걸고 충족하지 못하면 종료합니다.
