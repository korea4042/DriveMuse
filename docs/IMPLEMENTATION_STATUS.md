# 설계 v2.3 구현 상태

작업일: 2026-09-18. 표의 F01~F10은 v2.1 범위, 아래 v2.3 표는 16~32장 범위입니다.

| 요구사항 | 현재 코드 | 남은 검증/제한 |
|---|---|---|
| F01 | 단계 설문, Room 초안, 출처·상태 보존, 직접 매핑과 선택 AI 검증 | 실제 기기 재시작·큰 글꼴 |
| F02 | 기존 YouTube 읽기 조회, 설문 검색어, 검증된 특성 | 개인 OAuth·프로젝트 키 |
| F03/F04 | 전경 대략 위치, 암호화 영역, KMA 격자 실황·유효시각 | 기기 위치 정확도, 서비스 키·한국 서비스 범위 |
| F05 | Firebase AI Logic, 4 역할, App Check, JSON 검증·예산·대체 | 개인 Firebase/App Check 연결 호출 |
| F06 | 3곡 큐 저장, 버전 검사, 재시작 표시, 순수 상태 머신 | 자동 실행은 G3까지 비활성 |
| F07 | 실제 구간 합산, 탐색/누락 제외, 중복·개정 처리 | L0 진단은 학습에 연결하지 않음 |
| F08 | 명시 평가, 트랙 학습·감쇠·장기 집계, 특성 엔진 | 실제 관측 학습은 G2까지 비활성 |
| F09 | 설문·홈·설정·평가·학습 초기화·운전 모드 | 기기 TalkBack·56dp·큰 글꼴 |
| F10 | 가산 Room v3 마이그레이션, 30일 기록, 좌표 최소화, 삭제 취소 | Room 기기 업그레이드·개인 배포 서명 |

## v2.3 추가 요구
| 장 | 현재 코드 | 남은 검증/제한 |
|---|---|---|
| §16–17 발견·신규성 | `Discovery.kt` NoveltyResolver·DiscoveryPlanner·PoolHealth·CandidatePriority·DiversityAudit, `NoveltyAnnotator` | 실제 경험 데이터는 G2 관측 후 |
| §18 스키마 | Room v4, 15개 신규 테이블, `MIGRATION_3_4` 스테이징 이관 | 기기 업그레이드, `app/schemas` 생성 확인 |
| §19 수집 | `DiscoveryCoordinator`(lease·quota_ledger·RunLimits), `MetadataSyncWorker` 6h | 공급자 실 할당량, Doze 지연 |
| §20 상태 표시 | `CatalogViewModel` CollectionStatus·CatalogSummary, 수집 상세 화면 | 실기기 문구·큰 글꼴 |
| §22–23 연동 설정 | `CredentialStore`(Keystore AES-GCM), `IntegrationConfigRepository` draft→probe→promote, Probes | T16 백업 제외·캡처 보호 실기기 확인 |
| §24 설정 가독성 | `SettingsScreen.kt` 요약행+상세, 진단 펼침 | 360dp·200%·TalkBack |
| §27 Track 정체성 | `Catalog.kt` IdentityResolver·ValidationGate·PlayableRefResolver·AliasResolver | 평가 세트로 임계값 조정 |
| §28 공급자 | `MusicKnowledge.kt` MusicBrainz/Last.fm/ListenBrainz 어댑터, ProviderHttp | 실키 연결, 약관·비상업 조건 |
| §29 비율 | `Exploration.kt` MixTarget/ExplorationMix, RecommendationEngine에 mix 전달 | START_CONFIRMED 노출은 L2 이후 |
| §30 슬롯 큐 | `SlotQueue.kt` SlotQueuePolicy CAS | L2 재생 명령 미구현(L0) |
| §31 프롬프트 | selector v2.3, metadata-interpreter v2.3, discovery-planner v2.2 자산·검증기 | interpreter/planner AI 호출 미연결 |

## 검증 기록
- 2026-09-18 `:core:domain` kotlinc 2.1.20 컴파일 + 리플렉션 러너: 86 passed / 0 failed.
- `python3 scripts/check_migration_sql.py`: PASS.
- `:app` Android 컴파일·APK: **미실행**(환경에 Android SDK 없음). CI(`.github/workflows/android.yml`)에서 확인 필요.

## 출시 게이트
G0: YouTube Music 버전별 곡 ID, 위치, 상태, 종료 원인, 임의 곡 요청을 실기기에서 확인해야 합니다.
G1: 로컬/샘플 경로와 외부 키 설정 경로를 각각 검증해야 합니다.
G2/G3: 현재 차단. 가짜 청취 이벤트, 타이머 자동 전환, 보장되지 않은 재생 명령은 구현하지 않습니다.

## 의도적인 축약
프로세스 단위 세션, 전경 수동 위치 갱신, 원점·방향 미확인 시 GENERAL_DRIVE, 자동 네트워크 재시도 없음. AI 설문은 직접 매핑 근거 ID 검증 계약을 사용하며 자유 입력의 장르를 추정하지 않습니다. 날씨 캐시는 메모리에만 존재합니다.
