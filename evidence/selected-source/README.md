# Selected source evidence

이 디렉터리는 비공개 원본 KullChip의 봉인 revision에서 선별한 소스와 테스트 snapshot을 담습니다.

- 전체 애플리케이션 배포본이 아닙니다.
- 각 파일은 원래 package 선언과 핵심 테스트를 보존합니다.
- 실제 데이터, 좌표, 인증정보와 내부 운영 설정은 포함하지 않습니다.
- 원본 경로, source blob SHA와 공개 snapshot blob SHA는 `manifest.csv`에 기록합니다. 선별 파일은 원본 blob과 byte-identical입니다.
- 파일 자체의 설계 검토가 목적이며 이 디렉터리만으로 전체 앱을 빌드할 수 있다고 주장하지 않습니다.

각 파일의 공개 범위와 설계 불변식은 [선별 소스 설명](../../docs/07-selected-source-notes.md)을 참고하십시오.
