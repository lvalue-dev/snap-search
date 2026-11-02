#!/usr/bin/env node
/**
 * 가짜 ESPN scoreboard 서버 (데모/테스트용). 외부 네트워크 없이 라이브 흐름을 재현한다.
 *
 *   node tools/mock-espn/server.js [port=8099] [speed=1]
 *
 * - GET /apis/site/v2/sports/soccer/:slug/scoreboard?dates=YYYYMMDD[-YYYYMMDD]
 * - GET /apis/site/v2/sports/soccer/:slug/teams
 *
 * 리그마다: 끝난 경기 2, 지금 진행 중 2, 예정 경기 4. 진행 중 경기는 서버 시작 시각 기준으로
 * 실제 시간이 흐르며(speed 배속) 전반→HT→후반→FT 로 넘어가고 미리 정해진 분에 골/카드/교체가 생긴다.
 */
const http = require("http");
const { URL } = require("url");

const PORT = Number(process.argv[2] || process.env.PORT || 8099);
const SPEED = Number(process.argv[3] || process.env.SPEED || 1); // 1 = 실시간, 10 = 10배속
const START = Date.now();
// 유럽 시즌 표기: 7월 이후면 올해, 그 전이면 작년 (프론트 page.tsx 와 동일 규칙)
const SEASON = new Date().getUTCMonth() >= 6 ? new Date().getUTCFullYear() : new Date().getUTCFullYear() - 1;

// ESPN 표기 이름을 쓴다 (내부 api-football 이름과 다른 것들을 섞어 매핑을 시험)
const LEAGUES = {
  "eng.1": {
    name: "English Premier League", season: SEASON,
    teams: [
      [360, "Manchester United"], [382, "Manchester City"], [364, "Liverpool"], [359, "Arsenal"],
      [363, "Chelsea"], [367, "Tottenham Hotspur"], [361, "Newcastle United"], [362, "Aston Villa"],
      [380, "Wolverhampton Wanderers"], [331, "Brighton & Hove Albion"],
    ],
  },
  "esp.1": {
    name: "Spanish LALIGA", season: SEASON,
    teams: [[86, "Real Madrid"], [83, "Barcelona"], [1068, "Atlético Madrid"], [243, "Sevilla"]],
  },
  "ger.1": {
    name: "German Bundesliga", season: SEASON,
    teams: [[132, "Bayern Munich"], [124, "Borussia Dortmund"], [131, "Bayer Leverkusen"], [11420, "RB Leipzig"]],
  },
  "fra.1": {
    name: "French Ligue 1", season: SEASON,
    teams: [[160, "Paris Saint-Germain"], [176, "Marseille"], [174, "AS Monaco"], [166, "Lille"]],
  },
};

const PLAYERS = ["Bruno Fernandes", "Erling Haaland", "Mohamed Salah", "Bukayo Saka", "Cole Palmer",
  "Son Heung-Min", "Alexander Isak", "Ollie Watkins", "Matheus Cunha", "Kaoru Mitoma", "Vinícius Júnior",
  "Lamine Yamal", "Harry Kane", "Florian Wirtz", "Ousmane Dembélé"];

const pad = (n) => String(n).padStart(2, "0");
const ymd = (d) => `${d.getUTCFullYear()}${pad(d.getUTCMonth() + 1)}${pad(d.getUTCDate())}`;
const iso = (d) => `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}T${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}Z`;
const logo = (id) => `https://a.espncdn.com/i/teamlogos/soccer/500/${id}.png`;
const team = ([id, name]) => ({ id: String(id), displayName: name, logo: logo(id) });

// 결정적 의사난수
function rng(seed) { let s = seed >>> 0; return () => ((s = (s * 1664525 + 1013904223) >>> 0) / 2 ** 32); }

/** 리그별 고정 경기 목록 생성 (서버 시작 시각 기준) */
function buildFixtures(slug, idx) {
  const lg = LEAGUES[slug];
  const r = rng(idx * 7919 + 17);
  const fixtures = [];
  const base = 700000 + idx * 1000;
  const t = lg.teams;
  const pair = (i) => [t[(i * 2) % t.length], t[(i * 2 + 1) % t.length]];
  let n = 0;
  const add = (kind, kickoff, [home, away]) => fixtures.push({ id: base + n++, kind, kickoff, home, away, seed: r() });

  // 끝난 경기: 2일 전, 5일 전
  add("final", new Date(START - 2 * 86400e3), pair(0));
  add("final", new Date(START - 5 * 86400e3), pair(1));
  // 진행 중: 하나는 이미 20분 진행, 하나는 2분 전 킥오프 (speed 배속 적용)
  add("live", new Date(START - (20 * 60e3) / SPEED), pair(2));
  add("live", new Date(START - (2 * 60e3) / SPEED), pair(3));
  // 예정: 오늘 저녁, 내일, 3일 후, 6일 후
  add("pre", new Date(START + 5 * 3600e3), pair(4));
  add("pre", new Date(START + 1 * 86400e3 + 3600e3), pair(0));
  add("pre", new Date(START + 3 * 86400e3), pair(1));
  add("pre", new Date(START + 6 * 86400e3), pair(2));
  return fixtures;
}

/** 스크립트된 이벤트: 분, 종류, 홈/원정 */
function scriptEvents(f) {
  const r = rng(Math.floor(f.seed * 1e9));
  const pick = () => PLAYERS[Math.floor(r() * PLAYERS.length)];
  return [
    { min: 7,  type: "Goal",          side: "home", p: [pick()] },
    { min: 23, type: "Yellow Card",   side: "away", p: [pick()] },
    { min: 38, type: "Goal",          side: "away", p: [pick()] },
    { min: 45, extra: 2, type: "Penalty - Scored", side: "home", p: [pick()] },
    { min: 58, type: "Substitution",  side: "away", p: [pick(), pick()] },
    { min: 71, type: "Red Card",      side: "away", p: [pick()] },
    { min: 84, type: "Goal",          side: "away", p: [pick()] },
  ];
}

function statusFor(f, now) {
  if (f.kind === "pre") return { minute: 0, name: "STATUS_SCHEDULED", state: "pre", clock: "0'" };
  if (f.kind === "final") return { minute: 90, name: "STATUS_FULL_TIME", state: "post", clock: "90'+4'" };
  // live: 경과 "경기 분" = 실제 경과(초) * speed / 60. HT 는 45~48분 사이 3분 (speed 반영)
  const elapsedMin = ((now - f.kickoff.getTime()) / 60e3) * SPEED;
  if (elapsedMin < 45) return { minute: Math.floor(elapsedMin), name: "STATUS_FIRST_HALF", state: "in", clock: `${Math.floor(elapsedMin)}'` };
  if (elapsedMin < 47) return { minute: 45, name: "STATUS_FIRST_HALF", state: "in", clock: `45'+${Math.floor(elapsedMin - 45) + 1}'` };
  if (elapsedMin < 62) return { minute: 45, name: "STATUS_HALFTIME", state: "in", clock: "HT" };
  const second = 45 + (elapsedMin - 62);
  if (second < 90) return { minute: Math.floor(second), name: "STATUS_SECOND_HALF", state: "in", clock: `${Math.floor(second)}'` };
  if (second < 94) return { minute: 90, name: "STATUS_SECOND_HALF", state: "in", clock: `90'+${Math.floor(second - 90) + 1}'` };
  return { minute: 90, name: "STATUS_FULL_TIME", state: "post", clock: "90'+4'" };
}

function renderEvent(f, st) {
  const home = team(f.home), away = team(f.away);
  const events = f.kind === "pre" ? [] : scriptEvents(f).filter((e) => e.min <= st.minute || st.state === "post");
  let hs = 0, as = 0;
  const details = events.map((e) => {
    const isGoal = e.type === "Goal" || e.type === "Penalty - Scored";
    if (isGoal) e.side === "home" ? hs++ : as++;
    return {
      type: { id: isGoal ? "137" : e.type === "Substitution" ? "76" : "94", text: e.type },
      clock: { value: e.min * 60, displayValue: e.extra ? `${e.min}'+${e.extra}'` : `${e.min}'` },
      team: { id: e.side === "home" ? home.id : away.id },
      scoringPlay: isGoal, penaltyKick: e.type === "Penalty - Scored", ownGoal: false,
      redCard: e.type === "Red Card", yellowCard: e.type === "Yellow Card",
      athletesInvolved: e.p.map((name, i) => ({ id: String(1000 + Math.abs(hashCode(name))), displayName: name })),
    };
  });
  const scoreOf = (n) => (f.kind === "pre" ? "0" : String(n));
  return {
    id: String(f.id), date: iso(f.kickoff), name: `${f.away[1]} at ${f.home[1]}`,
    season: { year: SEASON, slug: `${SEASON}-${String(SEASON + 1).slice(2)}` }, week: { number: 10 + (f.id % 3) },
    competitions: [{
      id: String(f.id),
      venue: { fullName: `${f.home[1]} Stadium`, address: { city: "Demo City" } },
      competitors: [
        { id: home.id, homeAway: "home", score: scoreOf(hs), team: home },
        { id: away.id, homeAway: "away", score: scoreOf(as), team: away },
      ],
      details,
      status: { clock: st.minute * 60, displayClock: st.clock, period: st.minute > 45 ? 2 : 1,
        type: { id: "x", name: st.name, state: st.state, completed: st.state === "post", shortDetail: st.clock } },
    }],
  };
}

function hashCode(s) { let h = 0; for (const c of s) h = (h * 31 + c.charCodeAt(0)) | 0; return h; }

const FIXTURES = Object.fromEntries(Object.keys(LEAGUES).map((slug, i) => [slug, buildFixtures(slug, i)]));

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);
  const m = url.pathname.match(/^\/apis\/site\/v2\/sports\/soccer\/([^/]+)\/(scoreboard|teams)$/);
  res.setHeader("Content-Type", "application/json");
  if (!m || !LEAGUES[m[1]]) { res.statusCode = 404; return res.end(JSON.stringify({ error: "not found" })); }
  const slug = m[1], lg = LEAGUES[slug];
  if (m[2] === "teams") {
    return res.end(JSON.stringify({ sports: [{ leagues: [{ teams: lg.teams.map((t) => ({ team: team(t) })) }] }] }));
  }
  const dates = url.searchParams.get("dates") || ymd(new Date());
  const [from, to = from] = dates.split("-");
  const now = Date.now();
  const events = FIXTURES[slug]
    .filter((f) => { const d = ymd(f.kickoff); return d >= from && d <= to; })
    .map((f) => renderEvent(f, statusFor(f, now)));
  console.log(`${new Date().toISOString()} GET ${slug} scoreboard ${dates} -> ${events.length} events`);
  res.end(JSON.stringify({
    leagues: [{ id: "700", name: lg.name, slug, season: { year: lg.season } }],
    season: { type: 1, year: lg.season }, day: { date: from }, events,
  }));
});

server.listen(PORT, () => console.log(`mock ESPN listening on http://localhost:${PORT} (speed x${SPEED})`));
