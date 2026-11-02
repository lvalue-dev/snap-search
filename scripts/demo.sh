#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 로컬 데모: 외부 의존(MySQL, Azure, 실제 ESPN) 없이 전체 흐름을 눌러본다.
#   - 가짜 ESPN 서버 (localhost:8099) : 리그마다 끝난 경기 2, 진행 중 2, 예정 4. 시간이 실제로 흐른다.
#   - 백엔드 demo 프로필 (localhost:8080) : H2 인메모리 + 시드, 5초 간격 라이브 폴링, SSE
#   - 프론트 next dev (localhost:3000) : .env.local 로 로컬 백엔드를 바라봄
#
# 사용:  ./scripts/demo.sh            (실시간 속도)
#        SPEED=10 ./scripts/demo.sh   (10배속: 1분에 경기 10분이 흐름 → 골/HT/FT 를 빨리 본다)
# 요구:  JDK 17+, Node 18+, 형제 디렉터리에 snap-search-fe 체크아웃
# ---------------------------------------------------------------------------
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FE="${FE_DIR:-$ROOT/../snap-search-fe}"
SPEED="${SPEED:-1}"

cleanup() { echo; echo "종료 중..."; kill 0 2>/dev/null || true; }
trap cleanup EXIT INT TERM

echo "▶ 가짜 ESPN (x$SPEED) : http://localhost:8099"
node "$ROOT/tools/mock-espn/server.js" 8099 "$SPEED" &

echo "▶ 백엔드 빌드"
cd "$ROOT" && ./gradlew -q bootJar -x test
echo "▶ 백엔드 demo 프로필 : http://localhost:8080  (H2 콘솔 /h2, JDBC URL jdbc:h2:mem:snap)"
java -jar build/libs/*.jar --spring.profiles.active=demo &

if [ -d "$FE" ]; then
  echo "▶ 프론트 : http://localhost:3000"
  [ -d "$FE/node_modules" ] || (cd "$FE" && npm install)
  echo "NEXT_PUBLIC_API_URL=http://localhost:8080" > "$FE/.env.local"
  (cd "$FE" && npx next dev -p 3000) &
else
  echo "⚠ 프론트 디렉터리($FE)가 없어 백엔드만 띄웁니다. FE_DIR=... 로 지정 가능."
fi

cat <<'TXT'

눌러볼 순서
  1. http://localhost:3000  → 시즌(현재) → England → Premier League → Chelsea
  2. 팀 페이지 '경기 일정' 탭: 초록 LIVE 배지 + 경과 분이 새로고침 없이 1분마다 바뀜 (SSE)
  3. 진행 중 경기 카드 클릭 → 상세: 스코어, 주요 이벤트, 타임라인(골/카드/교체)
  4. 터미널에서 직접 보기:  curl -N localhost:8080/api/live/stream
                            curl localhost:8080/api/live/by-date | jq
  Ctrl+C 로 전부 종료
TXT
wait
