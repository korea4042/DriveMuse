# DriveMuse 0.4.0 — 기술 설계 v2.3 구현

Kotlin / Compose Android 앱. 0.3.0(설계 v2.1: 설문, Firebase AI Logic 역할별 추천, 위치·날씨, 3곡 저장, 명시 평가·학습 엔진) 위에 기술 설계 v2.3의 16~32장을 추가했습니다.

## v2.3에서 추가된 것
- **Track 정체성(§27)**: `track` / `track_identifier` / `playable_ref` / `metadata_assertion` 분리. IdentityResolver(score≥.90, 차이≥.10, 독립 근거 2개, 버전 충돌 시 AMBIGUOUS), 결정적 ValidationGate, PlayableRefResolver(Official Audio → MV → Lyrics, Shorts 제외), identity_alias로 오병합 복구.
- **음악 지식 공급자(§28)**: MusicBrainz(무키, UA, 초당 1회), Last.fm(API key만), ListenBrainz(선택 사용자명·토큰, 204/404 계약). 공급자별 rate limiter · circuit breaker(3회→15분) · Retry-After backoff.
- **발견 대기열과 개인 신규성(§16–§19, §29)**: DISCOVERED→BASIC→ENRICHED→VALIDATED, queueStatus, 5단계 NoveltyState, 수집 예산 70/20/10, 우선순위 .45/.35/.20, PoolHealth 300/120/12/50, DB lease와 quota_ledger로 직렬화된 DiscoveryCoordinator, WorkManager 6시간 주기(비과금 기본).
- **탐색 비율(§29)**: 선곡 50/40/10 기본, Q4 명시 응답 시 .15/.35/.55 우선(Discovery:Experimental 4:1), 세션 누적 정수 보정, 3세션·20 attempt 후 하루 5%p 조정.
- **슬롯 큐(§30)**: PLANNED/LOCKED/START_CONFIRMED/TERMINAL/CANCELLED, baseQueueRevision CAS, 잠긴 슬롯 불변, 재시작 후 미확인 명령 재전송 금지.
- **앱 내 연동 설정(§22–§24)**: AndroidKeyStore AES-GCM 자격증명 저장, draft → VALIDATING → READY/ERROR 원자 교체, configVersion·generation 증가, 요약 행 + 상세 화면 설정 UI(L0·false 같은 용어는 진단 펼침에만), `effectiveEnabled = requested && configReady`.
- **프롬프트**: selector v2.3, metadata-interpreter v2.3, discovery-planner v2.2 추가. selector v2.1 폐기.
- Room v4 가산 마이그레이션(기존 candidates → playable_ref UNMATCHED + discovery_item PENDING 스테이징, played는 노출 기록만).

## 빌드
JDK 17, Android SDK 36, build-tools 35.0.0, Gradle wrapper 8.11.1.

```sh
./gradlew :core:domain:test :app:testDebugUnitTest :app:assembleDebug
python3 scripts/check_migration_sql.py
```

외부 연결 설정이 없어도 빌드와 샘플 모드는 동작합니다. 실제 후보 조회에는 YouTube Data API 키가 필요합니다.

키는 이제 앱의 설정 → 음악 서비스 / AI 화면에서 입력·검증·저장하는 것이 기본입니다(암호화 저장, 재빌드 불필요). 아래 Gradle 속성은 **선택 기본값**이며 사용자가 앱에서 삭제하면 자동으로 되살리지 않습니다.

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

## 이번 작업에서 검증한 것 / 못 한 것
- `:core:domain` 순수 Kotlin: kotlinc 2.1.20으로 컴파일·실행, 86개 테스트 통과(신규 `CatalogV23Test` 포함: T01·T02·T05·T12·T15~T19·T24·T26·T27·T29~T32).
- `scripts/check_migration_sql.py` PASS (v4 테이블·스테이징·ISRC 비유일).
- **`:app` 모듈은 이 환경에 Android SDK가 없어 컴파일하지 못했습니다.** Room KSP·Compose import 오류가 있을 수 있으니 `./gradlew :app:assembleDebug`로 먼저 확인하세요. Room `exportSchema=true`이므로 `app/schemas/`에 스키마 JSON이 생성됩니다.
- 실기기 G0/G2/G3, 공급자 실연동(키·할당량·약관)은 미검증입니다.

## 과도기 상태(다음 단계)
- 추천 후보는 아직 videoId 기반 소스를 사용하며 NoveltyAnnotator가 playable_ref → track_experience 링크로 신규성만 주석합니다. VALIDATED Track 소스로 완전 전환은 다음 단계입니다.
- metadata-interpreter 역할과 discovery-planner AI 경로는 계약·검증기만 있고 호출은 연결하지 않았습니다(로컬 결정적 계획 사용).
- `user_track_context`는 스키마만 있습니다. L1/L2 재생 수준 전환은 G0 통과 전까지 없습니다.

자세한 검증 범위와 남은 기기 검증은 `docs/IMPLEMENTATION_STATUS.md`, `docs/DEVICE_QA.md`를 확인하세요.

SDK 연결 방식: https://firebase.google.com/docs/ai-logic/get-started?platform=android
JSON 응답: https://firebase.google.com/docs/ai-logic/generate-structured-output?platform=android

날씨 데이터 출처: 기상청 초단기실황 (공공누리 제1유형 출처표시) — https://www.data.go.kr/data/15084084/openapi.do
