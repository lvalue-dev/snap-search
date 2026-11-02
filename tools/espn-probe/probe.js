#!/usr/bin/env node
/**
 * ESPN 비공개 API 실시간 제공률·지연·정확도 측정기 (의존성 없음, Node 18+)
 *
 *   node tools/espn-probe/probe.js --leagues eng.1,esp.1,ger.1,fra.1 --interval 30 --minutes 120 --out probe-$(date +%F).jsonl
 *   node tools/espn-probe/probe.js --report probe-2026-11-07.jsonl        # 수집 파일로 보고서만 다시 출력
 *   node tools/espn-probe/probe.js --base http://localhost:8099 --interval 2 --minutes 1   # 가짜 서버로 리허설
 *
 * 선택: --api-football-key KEY  (API-Football 무료 플랜의 /fixtures?live=all 과 스코어/상태를 교차 검증.
 *       100 req/일 한도이므로 --af-interval 300 처럼 간격을 길게 둘 것)
 *
 * 측정 항목
 *  - 제공률  : 폴링 성공률, 라이브로 잡힌 경기 수, 스키마 필드 존재율(details/athletesInvolved/displayClock/week)
 *  - 신선도  : 라이브 경기 displayClock 이 바뀌는 간격(초) 분포 → 실제 갱신 주기
 *  - 지연    : 골 이벤트가 처음 관측된 시각의 경기 시계 − 이벤트 분 (분 단위 지연 추정)
 *  - 정확도  : 스코어 == 골 이벤트 수 일치율, 상태 전이 순서 이상(1H→FT 등) 감지,
 *              API-Football 과 비교 시 스코어 일치율과 선행/후행(초)
 */
const fs = require("fs");
const https = require("https");
const http = require("http");

const args = Object.fromEntries(process.argv.slice(2).map((a, i, arr) => a.startsWith("--") ? [a.slice(2), arr[i + 1] && !arr[i + 1].startsWith("--") ? arr[i + 1] : true] : []).filter(Boolean));
const LEAGUES = String(args.leagues || "eng.1,esp.1,ger.1,fra.1").split(",");
const INTERVAL = Number(args.interval || 30) * 1000;
const MINUTES = Number(args.minutes || 120);
const OUT = args.out || `probe-${new Date().toISOString().slice(0, 10)}.jsonl`;
const AF_KEY = args["api-football-key"];
const AF_INTERVAL = Number(args["af-interval"] || 300) * 1000;
const BASE = args.base || "https://site.api.espn.com"; // 테스트용: --base http://localhost:8099 (tools/mock-espn)

function get(url, headers = {}) {
  return new Promise((resolve, reject) => {
    const t0 = Date.now();
    (url.startsWith("http://") ? http : https).get(url, { headers: { "User-Agent": "snap-search-probe/1.0", Accept: "application/json", ...headers } }, (res) => {
      let body = "";
      res.on("data", (c) => (body += c));
      res.on("end", () => {
        const ms = Date.now() - t0;
        if (res.statusCode !== 200) return reject(new Error(`HTTP ${res.statusCode} (${ms}ms) ${body.slice(0, 120)}`));
        try { resolve({ json: JSON.parse(body), ms }); } catch (e) { reject(new Error(`bad json (${ms}ms)`)); }
      });
    }).on("error", reject);
  });
}
const ymd = (d) => d.toISOString().slice(0, 10).replace(/-/g, "");
const parseClock = (s) => { const m = /(\d+)'(?:\s*\+\s*(\d+)')?/.exec(s || ""); return m ? Number(m[1]) + (m[2] ? Number(m[2]) / 100 : 0) : null; };

// ------------------------------------------------------------------ 수집
async function collect() {
  const out = fs.createWriteStream(OUT, { flags: "a" });
  const log = (rec) => out.write(JSON.stringify({ t: Date.now(), ...rec }) + "\n");
  const until = Date.now() + MINUTES * 60e3;
  let lastAf = 0;
  console.log(`수집 시작: ${LEAGUES.join(",")} / ${INTERVAL / 1000}s 간격 / ${MINUTES}분 → ${OUT}`);
  while (Date.now() < until) {
    const today = new Date();
    const dates = `${ymd(new Date(today - 864e5))}-${ymd(new Date(+today + 864e5))}`;
    for (const slug of LEAGUES) {
      const url = `${BASE}/apis/site/v2/sports/soccer/${slug}/scoreboard?dates=${dates}&limit=500`;
      try {
        const { json, ms } = await get(url);
        const events = (json.events || []).map((e) => {
          const c = e.competitions?.[0] || {};
          const st = c.status || e.status || {};
          const comp = c.competitors || [];
          const home = comp.find((x) => x.homeAway === "home") || {};
          const away = comp.find((x) => x.homeAway === "away") || {};
          return {
            id: e.id, date: e.date, week: e.week?.number ?? null,
            state: st.type?.state, status: st.type?.name, clock: st.displayClock ?? null, period: st.period ?? null,
            home: home.team?.displayName, away: away.team?.displayName, hs: home.score, as: away.score,
            goals: (c.details || []).filter((d) => d.scoringPlay).map((d) => ({ min: parseClock(d.clock?.displayValue), team: d.team?.id, who: d.athletesInvolved?.[0]?.displayName ?? null, type: d.type?.text })),
            nDetails: (c.details || []).length,
            hasAthletes: (c.details || []).some((d) => (d.athletesInvolved || []).length > 0),
          };
        });
        log({ src: "espn", slug, ok: true, ms, n: events.length, events });
        const live = events.filter((x) => x.state === "in");
        console.log(`${new Date().toLocaleTimeString()} ${slug} ${ms}ms events=${events.length} live=${live.length} ${live.map((x) => `${x.home} ${x.hs}-${x.as} ${x.away} ${x.clock}`).join(" | ")}`);
      } catch (e) {
        log({ src: "espn", slug, ok: false, err: String(e.message) });
        console.log(`${new Date().toLocaleTimeString()} ${slug} 실패: ${e.message}`);
      }
    }
    if (AF_KEY && Date.now() - lastAf > AF_INTERVAL) {
      lastAf = Date.now();
      try {
        const { json, ms } = await get("https://v3.football.api-sports.io/fixtures?live=all", { "x-rapidapi-key": AF_KEY });
        const fixtures = (json.response || []).map((f) => ({ id: f.fixture.id, league: f.league.id, home: f.teams.home.name, away: f.teams.away.name, hs: f.goals.home, as: f.goals.away, status: f.fixture.status.short, elapsed: f.fixture.status.elapsed }));
        log({ src: "af", ok: true, ms, errors: json.errors, remaining: json?.paging, fixtures });
        console.log(`${new Date().toLocaleTimeString()} api-football live=${fixtures.length} ${JSON.stringify(json.errors || {})}`);
      } catch (e) { log({ src: "af", ok: false, err: String(e.message) }); }
    }
    await new Promise((r) => setTimeout(r, INTERVAL));
  }
  out.end();
  report(OUT);
}

// ------------------------------------------------------------------ 보고서
function report(file) {
  const rows = fs.readFileSync(file, "utf8").split("\n").filter(Boolean).map((l) => JSON.parse(l));
  const espn = rows.filter((r) => r.src === "espn");
  const ok = espn.filter((r) => r.ok);
  const pct = (a, b) => (b ? ((100 * a) / b).toFixed(1) + "%" : "n/a");
  const med = (xs) => { if (!xs.length) return null; const s = [...xs].sort((a, b) => a - b); return s[Math.floor(s.length / 2)]; };
  console.log("\n==================== ESPN 측정 보고서 ====================");
  console.log(`기간: ${new Date(rows[0].t).toLocaleString()} ~ ${new Date(rows.at(-1).t).toLocaleString()}`);
  console.log(`폴링 성공률: ${pct(ok.length, espn.length)} (${ok.length}/${espn.length}), 응답시간 중앙값 ${med(ok.map((r) => r.ms))}ms, 최대 ${Math.max(0, ...ok.map((r) => r.ms))}ms`);
  const fails = espn.filter((r) => !r.ok).reduce((m, r) => ((m[r.err] = (m[r.err] || 0) + 1), m), {});
  if (Object.keys(fails).length) console.log("실패 사유:", fails);

  // 경기별 타임라인 재구성
  const byFixture = new Map();
  for (const r of ok) for (const e of r.events) {
    const f = byFixture.get(e.id) || { ...e, samples: [] };
    f.samples.push({ t: r.t, state: e.state, status: e.status, clock: e.clock, hs: e.hs, as: e.as, goals: e.goals, nDetails: e.nDetails, hasAthletes: e.hasAthletes, week: e.week });
    byFixture.set(e.id, f);
  }
  const all = [...byFixture.values()];
  const liveSeen = all.filter((f) => f.samples.some((s) => s.state === "in"));
  console.log(`\n관측 경기: ${all.length} (라이브로 잡힌 경기 ${liveSeen.length})`);
  console.log(`필드 제공율 (라이브 샘플 기준): displayClock ${pct(liveSeen.flatMap((f) => f.samples).filter((s) => s.state === "in" && s.clock).length, liveSeen.flatMap((f) => f.samples).filter((s) => s.state === "in").length)}, ` +
    `details ${pct(liveSeen.filter((f) => f.samples.some((s) => s.nDetails > 0)).length, liveSeen.length)}, ` +
    `선수명(athletesInvolved) ${pct(liveSeen.filter((f) => f.samples.some((s) => s.hasAthletes)).length, liveSeen.length)}, ` +
    `week(라운드) ${pct(all.filter((f) => f.week != null).length, all.length)}`);

  // 신선도: 라이브 중 clock 값이 바뀐 간격
  const gaps = [];
  for (const f of liveSeen) {
    let prev = null;
    for (const s of f.samples) {
      if (s.state !== "in" || !s.clock) continue;
      if (prev && s.clock !== prev.clock) gaps.push((s.t - prev.t) / 1000);
      if (!prev || s.clock !== prev.clock) prev = s;
    }
  }
  console.log(`시계 갱신 간격: 중앙값 ${med(gaps) ?? "n/a"}s, 최대 ${gaps.length ? Math.max(...gaps) : "n/a"}s (폴링 ${INTERVAL / 1000}s 해상도)`);

  // 지연: 골이 처음 보인 샘플의 경기 시계 − 골 분
  const delays = [];
  let scoreMismatch = 0, scoreChecks = 0, badTransitions = 0;
  const order = { pre: 0, in: 1, post: 2 };
  for (const f of liveSeen) {
    // 첫 샘플에 이미 있던 골은 "처음 관측" 시각을 알 수 없으므로 지연 표본에서 제외
    const seen = new Set(f.samples[0].goals.map((g) => `${g.min}-${g.team}-${g.who}`));
    let prevState = null;
    for (const s of f.samples) {
      if (prevState && order[s.state] < order[prevState]) badTransitions++;
      prevState = s.state;
      for (const g of s.goals) {
        const k = `${g.min}-${g.team}-${g.who}`;
        if (!seen.has(k)) { seen.add(k); const c = parseClock(s.clock); if (c != null && g.min != null && s.state === "in") delays.push(Math.max(0, Math.floor(c) - Math.floor(g.min))); }
      }
      if (s.state !== "pre") {
        scoreChecks++;
        const total = s.goals.length, score = Number(s.hs) + Number(s.as);
        if (Number.isFinite(score) && total !== score) scoreMismatch++;
      }
    }
  }
  console.log(`골 반영 지연(경기 분): 표본 ${delays.length}, 중앙값 ${med(delays) ?? "n/a"}분, 최대 ${delays.length ? Math.max(...delays) : "n/a"}분`);
  console.log(`스코어 vs 골 이벤트 수 불일치: ${scoreMismatch}/${scoreChecks} 샘플 (${pct(scoreMismatch, scoreChecks)})  상태 역행: ${badTransitions}`);

  // API-Football 교차 검증
  const af = rows.filter((r) => r.src === "af" && r.ok);
  if (af.length) {
    let agree = 0, compared = 0;
    const norm = (s) => String(s || "").toLowerCase().replace(/[^a-z]/g, "");
    for (const a of af) {
      const near = ok.filter((r) => Math.abs(r.t - a.t) < INTERVAL * 1.5);
      const espnLive = near.flatMap((r) => r.events).filter((e) => e.state === "in");
      for (const fx of a.fixtures) {
        const m = espnLive.find((e) => norm(e.home).includes(norm(fx.home).slice(0, 6)) || norm(fx.home).includes(norm(e.home).slice(0, 6)));
        if (!m) continue;
        compared++;
        if (Number(m.hs) === fx.hs && Number(m.as) === fx.as) agree++;
      }
    }
    console.log(`API-Football 교차 검증: 비교 ${compared}건, 스코어 일치 ${pct(agree, compared)}  (API-Football 오류: ${JSON.stringify(af.at(-1).errors || {})})`);
  }
  console.log("==========================================================\n");
}

if (args.report) report(args.report); else collect();
