# Snap-Search 디벨롭 & 홈 서버 이전 로드맵

작성일: 2026-11-02
대상 저장소: `snap-search`(Spring Boot 백엔드), `snap-search-fe`(Next.js 프론트)

---

## 0. 현재 상태 진단 (코드 기준)

| 영역 | 현재 | 문제 |
|---|---|---|
| 데이터 소스 | API-Football v3, **무료 플랜**(100 req/일) | 무료 플랜은 과거 시즌(현재 기준 2022~2024)만 조회 가능. 현 시즌(2025/26 = `season=2025`) 요청 시 `errors.plan` 응답 → **실시간/현 시즌 데이터가 구조적으로 불가** |
| 경기 데이터 적재 방식 | CSV로 넣어둔 "더미" fixture 행을 매일 00:15 KST 배치가 fixture_id 단위로 1건씩 갱신 (`MatchBatchScheduler` → `MatchServiceImpl.fetchMatcheData`) | ① DB에 없는 새 시즌 일정은 **절대 생성되지 않음** ② 경기당 1 요청이라 쿼터 낭비 (`/fixtures?ids=a-b-c`로 20건씩 묶기 가능) ③ 하루 1회라 라이브 반영 불가 |
| 프론트 시즌 선택 | `page.tsx`에 `2024`부터 13년 하드코딩 | 새 시즌이 열려도 UI에 노출되지 않음 |
| 날짜 처리 | `fixture_date`가 `"yyyy-MM-dd HH:mm:ss UTC"` **문자열** 컬럼. `getUpcomingMatchByTeam`은 KST now 문자열과 비교 | 문자열 비교 + 시간대 혼용으로 "다가오는 경기"가 최대 9시간 어긋남 |
| 비밀정보 | `application.properties`에 **API 키가 평문 커밋**, `application-local.properties`에 Azure MySQL 공인 IP + 비밀번호 | 공개 저장소면 즉시 키 재발급 필요. 환경변수로 이전 |
| 이미지 | Azure Blob에 리사이즈 업로드 (`AzureBlobServiceImpl`) | 홈 서버 이전 시 Azure 종속 제거 필요 |
| 의존성 | Spring Boot 3.4 + `springfox-boot-starter 3.0.0` | springfox는 Boot 3와 호환 안 됨(기동 실패 가능). `springdoc-openapi`로 교체 |
| 프론트 빌드 | `next@13.2` + `output: "export"`인데 Dockerfile은 `npm start`(SSR 모드) | 설정 모순. 동적 라우트(`/team/[teamId]`)는 export 모드와 충돌 |
| 프론트 스택 | MUI + styled-components + emotion + tailwind + MobX + react-query v3 혼용 | 번들 비대, 스타일 시스템 4개 공존. 정리 필요 |
| 프론트 UX | 로딩 시 전체 화면 Spinner, 에러 상태 없음, `["team"]` 쿼리 키에 파라미터 누락(캐시 오염) | 체감 속도/안정성 저하 |

> 참고: 이 세션의 네트워크 정책 때문에 API-Football·football-data.org 등 외부 API를 직접 호출해 확인하진 못했습니다. 플랜 제한은 공식 가격 페이지("free plans are limited in terms of available seasons") 및 2025~2026년 개발자 보고(2022~2024만 허용, 현 시즌은 `errors.plan`)에 근거합니다. 본인 키로 `GET /status`를 호출하면 플랜/잔여 쿼터를 바로 확인할 수 있습니다.

---

## 1. 실시간 데이터 확보 방안

### 선택지 비교

| 방안 | 비용 | 현 시즌 | 라이브(분 단위·이벤트) | 기존 코드 재사용 | 비고 |
|---|---|---|---|---|---|
| **A. API-Football Pro 플랜** | 약 $19/월 (7,500 req/일) | ✅ | ✅ `/fixtures?live=39-61-78-140`, events/lineups/statistics | **거의 100%** (ID·DTO 그대로) | 가장 빠르고 확실. 비용만 감수 |
| **B. football-data.org 무료** | 무료 (10 req/분, 12대회) | ✅ | ⚠️ 스코어는 약간 지연, 라인업·이벤트 상세는 유료 | ID 체계가 달라 매핑 테이블 필요 | 공식 무료 API. PL/BL1/PD/FL1 모두 포함 |
| **C. ESPN 비공개 엔드포인트** (`site.api.espn.com/.../soccer/eng.1/scoreboard`) | 무료, 키 불필요 | ✅ | ✅ 분 단위·득점 이벤트 포함 | ID 매핑 필요 | 비공식·무문서. 예고 없이 바뀔 수 있음. 취미 프로젝트엔 실용적 |
| D. TheSportsDB 무료(key `123`) | 무료 (30 req/분) | ✅ | ⚠️ 5~10분 지연, 라이브 상세는 Patreon | ID 매핑 필요 | 공유 테스트 키라 불안정 |

### 권장안

1. **데이터 공급자 추상화 먼저** (어느 안을 택하든 필요)
   - `FootballDataProvider` 인터페이스: `fetchFixtures(leagueId, season)`, `fetchLiveFixtures(leagueIds)`, `fetchEvents(fixtureId)`, `fetchStandings(...)`.
   - 구현체: `ApiFootballProvider`(기존 로직 이관), `FootballDataOrgProvider`, `EspnProvider`.
   - 외부 ID → 내부 ID 매핑 테이블 `external_id_map(provider, entity_type, external_id, internal_id)`.
2. **비용 0원으로 가려면 B + C 조합**: football-data.org로 일정/결과/순위(공식·안정), ESPN으로 라이브 분·득점 이벤트 보강. ESPN이 막히면 라이브만 꺼지고 나머지는 정상.
3. **월 $19가 부담 없다면 A**: 기존 코드·DB 스키마를 그대로 쓰고 아래 스케줄러 개편만 하면 2~3일 내 라이브 완성.

### 스케줄러 개편 (어느 안이든 공통)

```
[시즌 동기화]  매일 05:00 KST  → 리그별 전체 일정 1회 요청(4 req) → fixture upsert (새 시즌 자동 생성)
[경기일 감지]  매일 05:05 KST  → 오늘 KST 기준 예정 경기 있으면 "라이브 윈도우" 등록
[라이브 폴링]  윈도우 내 60초 간격 → live 엔드포인트 1 req → 변경분만 DB 반영 + SSE push
[종료 후 정리] 상태 FT 전환 시 1회 → events/lineups/statistics 수집
```

- 하루 예산(무료 100 req 기준으로도): 동기화 4 + 라이브 6시간×60 = 364 → **무료 쿼터로는 부족**하므로 A 또는 B/C가 필요하다는 결론이 여기서도 나옵니다.
- 프론트 전달: Spring `SseEmitter`로 `/api/live/stream` 제공 → 프론트는 `EventSource`로 구독. 폴링보다 단순하고 홈 서버 리소스 부담 적음.

### 데이터 모델 정리

- `fixture_stats.fixture_date` → `DATETIME`(UTC) 컬럼으로 전환, JPA `Instant` 매핑. 문자열 비교 제거.
- `fixture_status_short`별 상태 enum 도입(`NS/1H/HT/2H/ET/P/FT/AET/PEN/PST/CANC/LIVE`).
- Flyway 도입(`V1__baseline.sql`)으로 스키마를 코드로 관리. `ddl-auto=update` 제거.

---

## 2. 홈 서버 배포 설계

### 구성 (deploy/ 디렉터리에 스켈레톤 포함)

```
[인터넷] → Cloudflare Tunnel(cloudflared) → nginx(:80)
                                             ├─ /            → frontend (Next.js standalone :3000)
                                             ├─ /api/        → backend  (Spring Boot :8080)
                                             └─ /images/     → 로컬 볼륨 정적 서빙 (Azure Blob 대체)
           backend ↔ mysql:8 (볼륨)   /   optional: redis (라이브 캐시·SSE 팬아웃)
```

- **Cloudflare Tunnel**: 공유기 포트포워딩·DDNS·인증서 발급 전부 불필요. 무료. 기존 도메인(`toomuchoffside.site`)을 Cloudflare로 네임서버만 옮기면 됨. 대안: Tailscale Funnel, 또는 직접 포트포워딩 + Caddy.
- **이미지 저장소**: `ImageStorage` 인터페이스 → `LocalFileStorage`(기본), `AzureBlobStorage`(옵션). DB의 기존 `*.blob.core.windows.net` URL은 일괄 치환 스크립트 1회.
- **DB 이전**: Azure VM에서 `mysqldump --single-transaction SNAP_SEARCH > dump.sql` → 홈 MySQL 컨테이너 `docker exec -i mysql mysql SNAP_SEARCH < dump.sql`.
- **CI/CD**: GitHub Actions는 그대로 두되 Docker Hub 대신 **GHCR**(`ghcr.io/lvalue-dev/...`)에 push. 홈 서버에는 **Watchtower**가 5분 간격으로 새 이미지를 pull & 재시작. (self-hosted runner는 홈 서버에 GitHub 권한을 주는 셈이라 비추천)
- **비밀정보**: `.env`(git 제외)로 주입. `application.properties`에서 키 삭제 후 `${API_FOOTBALL_KEY}` 플레이스홀더로.
- **운영**: Spring Actuator `/actuator/health` → compose healthcheck. 로그는 docker json-file 로테이션. 백업은 `mysqldump` cron → 외장/클라우드.

### 하드웨어 참고

- 백엔드 JVM 512MB~1GB, MySQL 512MB, Next 256MB → **라즈베리파이 4(4GB)나 N100 미니PC**면 충분. ARM이면 백엔드 베이스 이미지를 `eclipse-temurin:17-jre`로(멀티아치 지원), `openjdk:17-jdk-slim`은 deprecated.

---

## 3. 기능 확장 아이디어 (우선순위순)

### P0 — 라이브가 들어오면 바로 가치가 생기는 것
1. **홈 화면 = 오늘의 경기 대시보드**: 날짜 스트립(어제/오늘/내일), 리그별 그룹, LIVE 배지 + 경과 분, 득점 시 스코어 하이라이트 애니메이션(이미 framer-motion 있음).
2. **라이브 타임라인**: 현재 `MatchOverview`/`EventCard`를 진행 중 경기에도 열어주고 SSE로 이벤트 append. (현재는 "경기 종료 후 확인 가능" 모달로 막혀 있음)
3. **리그 순위표**: `/standings` 1일 1회. 팀 페이지에서 현재 순위·최근 5경기 폼(W/D/L 칩) 표시.

### P1 — 재방문 동기
4. **즐겨찾기 팀**(localStorage → 이후 로그인) + 홈 상단 고정.
5. **Web Push 알림**: 즐겨찾기 팀 킥오프/득점/종료. 홈 서버에서 VAPID만 있으면 무료.
6. **검색**: ElasticSearch 대신 **Meilisearch**(단일 바이너리, 메모리 수십 MB) 또는 MySQL FULLTEXT. 팀/선수/감독 통합 검색 + 한글 별칭 테이블(토트넘, 맨시티…).
7. **경기 상세 확장**: 라인업 포메이션 뷰, 통계 바(점유율·슈팅·xG), 상대전적(H2H).

### P2 — 깊이
8. 선수 상세 페이지(현재 `EventCard`가 `/player/:id`로 라우팅하지만 페이지 없음) + 시즌 스탯.
9. 득점왕/도움왕 랭킹, 이적 뉴스(API 제공 시).
10. 다국어(ko/en), 팀명 한글화 매핑.

---

## 4. UI 개편 방향

### 구조
- **모바일 퍼스트 + 하단 탭바**(경기 / 리그 / 즐겨찾기 / 검색). 축구 결과 서비스는 모바일 트래픽이 지배적.
- 홈을 "시즌→국가→리그→팀" 드릴다운에서 **"오늘의 경기"**로 교체. 드릴다운은 `/leagues` 탐색 탭으로 이동.
- 팀 페이지: 현재 로고 대표색 그라데이션 헤더는 유지(차별점). 그 아래 **순위·폼·다음 경기 카운트다운**을 한 줄 요약으로.

### 디자인 시스템
- 스타일 시스템을 **하나로 통일**. 추천: Tailwind + shadcn/ui(Radix) — 이미 tailwind 설정이 있고, MUI/styled-components/emotion 제거 시 번들이 크게 줄어듦. MUI를 유지할 거면 tailwind·styled-components를 제거.
- 디자인 토큰: `--team-primary`, `--team-on-primary`를 CSS 변수로 올려 `colorExtractor` 결과를 페이지 전체에 전파.
- **다크 모드**(`prefers-color-scheme` + 토글). 라이브 스코어 앱 사용 시간대 특성상 수요 높음.
- 로딩: 전체 화면 Spinner → 영역별 **Skeleton**. 에러·빈 상태 컴포넌트 추가.
- 경기 카드: 승/무/패 배경색 대신 좌측 컬러바 + 스코어 굵기로 정보 밀도 상승. LIVE는 펄스 도트 + `72'` 같은 경과 시간.
- 접근성: 색만으로 승패 구분하지 않기(아이콘/텍스트 병기), 포커스 링, 명도 대비 4.5:1.

### 기술 정리
- `next@13.2` → **Next 15**(App Router 안정화, `experimental.appDir` 제거), `react-query@3` → **TanStack Query v5**, MobX 제거(헤더 로고 상태는 Context 또는 URL 파생으로 충분).
- `output: "export"` 제거 → `output: "standalone"`(Dockerfile과 일치, SSR/ISR 사용 가능). 오늘의 경기 페이지는 ISR 60초 + 클라이언트 SSE.
- ESLint `ignoreDuringBuilds` 해제, 타입 `goalsHome: string` → `number | null` 등 백엔드 타입과 정합.

---

## 5. 실행 순서 제안 (약 4주)

| 주차 | 백엔드 | 프론트 | 인프라 |
|---|---|---|---|
| 1 | API 키 재발급·환경변수화, springdoc 교체, Flyway 베이스라인, `fixture_date` DATETIME 전환 | Next 15 / TanStack Query v5 업그레이드, 스타일 시스템 통일 결정 | `deploy/` compose로 홈 서버에 현 버전 그대로 기동, DB 이전, Cloudflare Tunnel |
| 2 | `FootballDataProvider` 추상화 + 선택한 공급자 구현, 시즌 동기화 배치 | 오늘의 경기 홈, Skeleton/에러 상태 | GHCR + Watchtower 자동 배포 |
| 3 | 라이브 폴링 + SSE `/api/live/stream`, 순위표 | 라이브 카드·타임라인 SSE 구독, 순위표·폼 | 백업 cron, Actuator 모니터링 |
| 4 | 즐겨찾기/Web Push, 검색 인덱싱 | 하단 탭바, 다크모드, 즐겨찾기, 검색 | 부하 점검, 로그 로테이션 |

### 지금 결정이 필요한 것
1. **데이터 공급자**: A(유료 $19/월, 가장 빠름) vs B+C(무료, 매핑 작업 추가).
2. **프론트 스타일 시스템**: MUI 유지 vs Tailwind+shadcn/ui로 전환.
3. 홈 서버 OS/아키텍처(x86 vs ARM)와 공개 방식(Cloudflare Tunnel 권장).

---

## 출처
- API-Football 가격 페이지: https://www.api-football.com/pricing
- API-Football 시작 가이드(100 req/일): https://www.api-football.com/news/post/how-to-get-started-with-api-football-the-complete-beginners-guide
- 무료 플랜 시즌 제한 개발자 보고: https://github.com/MinhHai12345/MMO/pull/38 , https://github.com/paulmaina/football-value-pipeline/pull/78 , https://github.com/Mahiruni/Ethiolivesoccer/pull/4
- football-data.org 무료 티어 정리: https://www.thestatsapi.com/blog/football-data-org-free-tier-limits-2026 , https://publicapis.io/football-data-org-api
- TheSportsDB 문서(무료 키 123, 30 req/분): https://www.thesportsdb.com/documentation
- 무료 스포츠 API 비교: https://dev.to/edgelab/the-best-free-sports-data-apis-in-2025-a-developers-practical-review-14j9
