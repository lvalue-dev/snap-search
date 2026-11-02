# 무료 실시간 데이터 파이프라인 (ESPN 기반)

## 왜 ESPN 인가
| 후보 | 현 시즌 | 라이브 분/이벤트 | 비용 | 결론 |
|---|---|---|---|---|
| API-Football 무료 | ✗ (2022~2024만) | `live=all` 은 열리지만 이벤트 없음, 100 req/일 | 0 | 현 시즌 일정 자체를 못 받음 |
| football-data.org 무료 | ✓ | 스코어 지연, 라인업/이벤트 유료 | 0 | 일정·순위 백업 소스로 적합 |
| FotMob / Sofascore 비공식 | ✓ | ✓ | 0 | 서명 토큰(x-mas) 도입, 403 차단, ToS 금지 → 제외 |
| TheSportsDB 무료(키 123) | ✓ | 5~10분 지연 | 0 | 공유 키라 불안정 |
| **ESPN 비공개 JSON** | ✓ | ✓ 분 단위 clock + 득점/카드/교체 (선수명 포함) | 0, 키 불필요 | **채택** |

ESPN 은 문서화되지 않은 API 라 스키마가 바뀔 수 있다. 그래서 `EspnProvider` 는 DTO 바인딩 대신 `JsonNode` 로 관대하게 파싱하고, 공급자는 `FootballDataProvider` 인터페이스 뒤에 숨겨 두었다. 막히면 구현체만 교체하면 된다.

## 구성 요소
```
provider/FootballDataProvider        공급자 인터페이스 (fetchFixtures, fetchTeams)
provider/espn/EspnProvider           scoreboard?dates=YYYYMMDD-YYYYMMDD 파싱 → FixtureSnapshot
provider/espn/EspnStatusMapper       STATUS_SECOND_HALF → "2H", "45'+2'" → elapsed/extra
live/TeamMappingService              ESPN 팀 → 내부 team.id (external_id_map + 이름 정규화 + 별칭)
live/FixtureSyncService              스냅샷 → fixture_stats / fixture_events upsert, 변경분 반환
live/LiveScheduler                   일정 동기화(05:00 KST) + 라이브 폴링(60s, 경기 있을 때만)
live/LiveBroadcaster                 SseEmitter 레지스트리, 25s heartbeat
controller/LiveController            GET /api/live/stream (SSE), /api/live, /api/live/by-date
```

## ID 체계
- 내부 ID 는 기존 api-football 체계를 유지한다. 외부 ID 는 `external_id_map(provider, entity_type, external_id, internal_id, auto)` 로 연결.
- 새 경기: `fixture_id = 2_000_000_000 + espnEventId` (기존 api-football fixture id 와 충돌 방지).
- 팀 매칭 실패 시: `team.id = 9_000_000 + espnTeamId` 로 합성 팀 생성 + WARN 로그. `live.team-aliases[내부명]=ESPN명` 으로 보정 후 `external_id_map` 행을 수정하면 된다.

## 요청량
- 동기화: 리그당 1 요청/일 (4 리그 = 4 요청).
- 라이브: 진행 중/15분 내 킥오프 경기가 있는 리그만 60초마다 1 요청. 경기 없는 시간엔 0.
- 토요일 4 리그 동시 진행 8시간 가정: 4 × 60 × 8 = 1,920 요청/일. ESPN 공식 제한은 없으나 UA 명시, 60초 간격 유지.

## 로컬 검증 순서
1. `./gradlew test --tests 'com.snap_search.lvalue.provider.*' --tests 'com.snap_search.lvalue.live.*'` (네트워크 불필요, 샘플 JSON 기반)
2. 앱 기동 → 로그에 `[ESPN] Premier League 20251030-20251217: N fixtures` 와 `[Sync] ... changed` 확인.
3. `curl localhost:8080/api/live/by-date` 로 오늘 경기 확인. 경기 시간대에 `curl -N localhost:8080/api/live/stream` 으로 `event: match` 수신 확인.
4. `[TeamMap] ... 매칭 실패` WARN 이 있으면 `live.team-aliases` 에 추가.

## 아직 확인이 필요한 가정 (실제 응답으로 검증할 것)
- 교체 이벤트 `athletesInvolved` 순서: [투입, 아웃] 으로 가정. 반대면 `EspnProvider.parseDetail` 의 p1/p2 를 바꾼다.
- `week.number` 가 라운드로 내려오는지. 없으면 round 는 비워진다.
- 하프타임 스코어(`linescores`)는 scoreboard 에 없을 수 있다. 필요하면 `summary?event=` 로 보강.
- 라인업/통계는 `summary?event={id}` 에 있다 (`rosters`, `boxscore.teams[].statistics`). 다음 단계.
