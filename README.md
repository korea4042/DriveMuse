# DriveMuse 0.3.0 — v2.1 구현

Kotlin / Compose Android 앱. 첨부 제품·기술 설계 v2.1을 기준으로 기존 0.2.0에 초기 설문, Firebase AI Logic 역할별 추천, 선택 위치·날씨, 3곡 저장, 명시 평가와 순수 Kotlin 관측·학습 엔진을 추가했습니다.

## 빌드
JDK 17, Android SDK 36, build-tools 35.0.0, Gradle wrapper 8.11.1.

```sh
./gradlew :core:domain:test :app:testDebugUnitTest :app:assembleDebug
python3 scripts/check_migration_sql.py
```

외부 연결 설정이 없어도 빌드와 샘플 모드는 동작합니다. 실제 후보 조회에는 YouTube Data API 키가 필요합니다.

- `-PytApiKey=...`: YouTube Data API 키. Android 패키지·서명과 API 제한을 설정하세요.
- `app/google-services.json`: 자신의 Firebase Android 앱 설정. 파일이 있을 때 Google Services 플러그인이 적용됩니다. 서비스 계정 비밀 키가 아닙니다.
- `-PgeminiModel=...`: Firebase AI Logic에서 현재 지원하는 JSON 응답 모델 ID. 모델을 코드에 고정하지 않습니다.
- `-PweatherApiKey=...`: 기상청 초단기실황 API의 디코딩된 서비스 키. 한국 격자 조회를 사용합니다.

Firebase 프로젝트에서 AI Logic과 App Check를 구성해야 합니다. 디버그는 개인 디버그 토큰 등록, 릴리스는 Play Integrity 검증이 필요합니다. 인증·할당량·과금은 자신의 프로젝트 설정을 따릅니다. Gemini 비밀 API 키를 APK에 넣지 않습니다.

## 현재 제공 범위
- 초기 Q&A 초안 저장·재개·수정, 미응답 중립, 출처 있는 직접 선호, 명시 제외, 첫 세션 분위기
- 네 개의 독립적인 AI 역할, 요청 ID·입력 해시·엄격한 JSON 검증, 최대 40개 후보, 20초 제한, 일 60회 로컬 한도, 동일 제약 로컬 대체
- 기존 OAuth 읽기 전용 후보 조회와 설문 검색어, 제공자 메타데이터 기반 특징
- 최대 3곡 추천 저장, 버전·중복·제외 검증, 프로세스 재시작 시 표시만 복원
- 현재 위치의 의미 영역/격자 변환, 집·회사 영역의 Keystore 암호화 저장, 기상청 관측 시각·지역·만료 검사
- 직접 좋아요·싫어요, 30일 상세 기록, 감쇠된 장기 집계, 설문과 학습 별도 초기화
- 재생 관측 진단, 관측 집계·학습·큐 상태 전이 도메인과 테스트

**재생 수준은 L0 OPEN_ONLY입니다.** YouTube Music 열기는 실제 청취가 아닙니다. 자동 전환과 관측 기반 학습은 실기기 G0/G2/G3 검증 전까지 활성화하지 않습니다. 알림 접근 권한도 곡 제어를 보장하지 않습니다.

## 확인할 제한
- 세션은 현재 앱 프로세스 단위입니다. 차량 연결 수명주기에 따른 세션·백그라운드 실행은 추가 실기기 검증 항목입니다.
- 출발 영역과 이동 방향의 연속 관측은 연결하지 않았습니다. 자동 상황 판정은 일반 드라이브로 보수적으로 처리합니다. 사용자가 상황을 직접 선택할 수 있습니다.
- 위치는 전경의 수동 갱신입니다. 백그라운드 위치 수집은 없습니다.
- 네트워크 자동 재시도는 없습니다. 실패 시 검증된 로컬 후보를 사용합니다.
- 행동 특징의 상황/장기 학습 엔진은 준비되어 있지만, L0에서 실제 재생 증거를 생성하지 않습니다.
- AI 설문 출력은 근거 ID 직접 매핑을 검증하는 축약 계약입니다. 원본 질문·답변·의도·범위는 그대로 저장합니다.

자세한 검증 범위와 남은 기기 검증은 `docs/IMPLEMENTATION_STATUS.md`, `docs/DEVICE_QA.md`를 확인하세요.

SDK 연결 방식: https://firebase.google.com/docs/ai-logic/get-started?platform=android
JSON 응답: https://firebase.google.com/docs/ai-logic/generate-structured-output?platform=android

날씨 데이터 출처: 기상청 초단기실황 (공공누리 제1유형 출처표시) — https://www.data.go.kr/data/15084084/openapi.do
