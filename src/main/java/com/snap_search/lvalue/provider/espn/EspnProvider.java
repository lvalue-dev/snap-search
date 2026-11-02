package com.snap_search.lvalue.provider.espn;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.snap_search.lvalue.provider.FootballDataProvider;
import com.snap_search.lvalue.provider.model.EventSnapshot;
import com.snap_search.lvalue.provider.model.FixtureSnapshot;
import com.snap_search.lvalue.provider.model.LeagueRef;
import com.snap_search.lvalue.provider.model.TeamSnapshot;

/**
 * ESPN 비공개 JSON 엔드포인트 기반 공급자. 키 불필요, 무료.
 *
 * <pre>
 *  GET /apis/site/v2/sports/soccer/{slug}/scoreboard?dates=YYYYMMDD-YYYYMMDD&limit=500
 *  GET /apis/site/v2/sports/soccer/{slug}/teams
 * </pre>
 *
 * 비공식 API 이므로 응답 스키마가 바뀔 수 있다. 그래서 DTO 바인딩 대신 JsonNode 로 관대하게 파싱하고,
 * 없는 필드는 null 로 둔다. 파싱 로직은 {@link #parseScoreboard(JsonNode, LeagueRef)} 에 모여 있어
 * 저장된 샘플 JSON 으로 단위 테스트할 수 있다.
 */
@Component
public class EspnProvider implements FootballDataProvider {

	public static final String NAME = "espn";
	private static final Logger log = LoggerFactory.getLogger(EspnProvider.class);
	private static final DateTimeFormatter DATES = DateTimeFormatter.ofPattern("yyyyMMdd");

	private final WebClient webClient;

	public EspnProvider(@Qualifier("webClientForEspn") WebClient webClient) {
		this.webClient = webClient;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public List<FixtureSnapshot> fetchFixtures(LeagueRef league, LocalDate from, LocalDate to) {
		String dates = from.equals(to) ? from.format(DATES) : from.format(DATES) + "-" + to.format(DATES);
		JsonNode root = webClient.get()
			.uri(b -> b.path("/apis/site/v2/sports/soccer/{slug}/scoreboard")
				.queryParam("dates", dates)
				.queryParam("limit", 500)
				.build(league.espnSlug()))
			.retrieve()
			.bodyToMono(JsonNode.class)
			.timeout(Duration.ofSeconds(20))
			.block();
		List<FixtureSnapshot> result = parseScoreboard(root, league);
		log.info("[ESPN] {} {}: {} fixtures", league.name(), dates, result.size());
		return result;
	}

	@Override
	public List<TeamSnapshot> fetchTeams(LeagueRef league) {
		JsonNode root = webClient.get()
			.uri(b -> b.path("/apis/site/v2/sports/soccer/{slug}/teams").build(league.espnSlug()))
			.retrieve()
			.bodyToMono(JsonNode.class)
			.timeout(Duration.ofSeconds(20))
			.block();
		List<TeamSnapshot> teams = new ArrayList<>();
		if (root == null) {
			return teams;
		}
		for (JsonNode sport : root.path("sports")) {
			for (JsonNode lg : sport.path("leagues")) {
				for (JsonNode wrapper : lg.path("teams")) {
					JsonNode t = wrapper.path("team");
					teams.add(new TeamSnapshot(t.path("id").asText(), t.path("displayName").asText(null),
						firstLogo(t)));
				}
			}
		}
		return teams;
	}

	// ---------------------------------------------------------------- parsing

	public List<FixtureSnapshot> parseScoreboard(JsonNode root, LeagueRef league) {
		List<FixtureSnapshot> out = new ArrayList<>();
		if (root == null) {
			return out;
		}
		int season = root.path("leagues").path(0).path("season").path("year").asInt(0);
		for (JsonNode event : root.path("events")) {
			try {
				parseEvent(event, league, season).ifPresent(out::add);
			} catch (RuntimeException e) {
				log.warn("[ESPN] skip event {}: {}", event.path("id").asText(), e.toString());
			}
		}
		return out;
	}

	private Optional<FixtureSnapshot> parseEvent(JsonNode event, LeagueRef league, int seasonFromRoot) {
		JsonNode comp = event.path("competitions").path(0);
		if (comp.isMissingNode()) {
			return Optional.empty();
		}
		int season = event.path("season").path("year").asInt(seasonFromRoot);

		TeamSnapshot home = null;
		TeamSnapshot away = null;
		Integer goalsHome = null;
		Integer goalsAway = null;
		Integer htHome = null;
		Integer htAway = null;
		for (JsonNode c : comp.path("competitors")) {
			JsonNode t = c.path("team");
			TeamSnapshot snap = new TeamSnapshot(t.path("id").asText(), t.path("displayName").asText(null),
				firstLogo(t));
			Integer score = parseIntOrNull(c.path("score"));
			Integer ht = parseIntOrNull(c.path("linescores").path(0).path("value"));
			if ("home".equals(c.path("homeAway").asText())) {
				home = snap;
				goalsHome = score;
				htHome = ht;
			} else {
				away = snap;
				goalsAway = score;
				htAway = ht;
			}
		}
		if (home == null || away == null) {
			return Optional.empty();
		}

		JsonNode status = comp.has("status") ? comp.path("status") : event.path("status");
		String shortCode = EspnStatusMapper.toShort(status.path("type").path("name").asText(null),
			status.path("type").path("state").asText(null));
		int[] clock = EspnStatusMapper.parseClock(status.path("displayClock").asText(null));
		Integer elapsed = null;
		Integer extra = null;
		if (clock != null && FixtureSnapshot.LIVE_STATUSES.contains(shortCode)) {
			elapsed = clock[0];
			extra = clock[1] > 0 ? clock[1] : null;
		} else if (FixtureSnapshot.FINISHED_STATUSES.contains(shortCode) && !"PST".equals(shortCode)
			&& !"CANC".equals(shortCode)) {
			elapsed = 90;
		}

		// 예정 경기 중 스코어가 0으로 내려오는 경우가 있어 NS 는 null 로 정리
		if ("NS".equals(shortCode) || "PST".equals(shortCode) || "CANC".equals(shortCode)) {
			goalsHome = null;
			goalsAway = null;
			htHome = null;
			htAway = null;
		}

		Instant kickoff = Instant.parse(normalizeIso(event.path("date").asText()));
		String round = event.path("week").has("number")
			? "Regular Season - " + event.path("week").path("number").asInt() : null;

		JsonNode venue = comp.path("venue");
		String venueName = venue.path("fullName").asText(null);
		String venueCity = venue.path("address").path("city").asText(null);

		List<EventSnapshot> events = new ArrayList<>();
		for (JsonNode d : comp.path("details")) {
			EventSnapshot ev = parseDetail(d);
			if (ev != null) {
				events.add(ev);
			}
		}

		return Optional.of(new FixtureSnapshot(
			event.path("id").asText(), league, season, round, kickoff, shortCode,
			EspnStatusMapper.toLong(shortCode), elapsed, extra, home, away,
			goalsHome, goalsAway, htHome, htAway, venueName, venueCity, events));
	}

	/**
	 * ESPN details 항목 → api-football 어휘.
	 * type.text 예: "Goal", "Penalty - Scored", "Own Goal", "Yellow Card", "Red Card", "Substitution",
	 *               "Penalty - Missed", "Goal - Header", "Goal - Volley", "Goal - Free-kick" ...
	 */
	EventSnapshot parseDetail(JsonNode d) {
		String text = d.path("type").path("text").asText("");
		String lower = text.toLowerCase();
		int[] clock = EspnStatusMapper.parseClock(d.path("clock").path("displayValue").asText(null));
		int elapsed = clock != null ? clock[0] : (int)d.path("clock").path("value").asDouble(0) / 60;
		Integer extra = clock != null && clock[1] > 0 ? clock[1] : null;
		String teamId = d.path("team").path("id").asText(null);

		JsonNode athletes = d.path("athletesInvolved");
		String p1Id = athletes.path(0).path("id").asText(null);
		String p1Name = athletes.path(0).path("displayName").asText(null);
		String p2Id = athletes.path(1).path("id").asText(null);
		String p2Name = athletes.path(1).path("displayName").asText(null);

		String type;
		String detail;
		if (d.path("scoringPlay").asBoolean(false) || lower.startsWith("goal") || lower.contains("scored")) {
			type = "Goal";
			if (d.path("ownGoal").asBoolean(false) || lower.contains("own goal")) {
				detail = "Own Goal";
			} else if (d.path("penaltyKick").asBoolean(false) || lower.contains("penalty")) {
				detail = "Penalty";
			} else {
				detail = "Normal Goal";
			}
		} else if (lower.contains("missed") && lower.contains("penalty")) {
			type = "Goal";
			detail = "Missed Penalty";
		} else if (d.path("redCard").asBoolean(false) || lower.contains("red card")) {
			type = "Card";
			detail = "Red Card";
		} else if (d.path("yellowCard").asBoolean(false) || lower.contains("yellow card")) {
			type = "Card";
			detail = "Yellow Card";
		} else if (lower.contains("substitution")) {
			type = "subst";
			detail = "Substitution";
		} else {
			return null; // 알 수 없는 유형은 버린다 (킥오프, 종료 안내 등)
		}
		// Substitution: athletesInvolved[0]=투입, [1]=교체 아웃 으로 가정 (api-football 과 동일한 player/assist 자리 배치)
		return new EventSnapshot(elapsed, extra, teamId, p1Id, p1Name, p2Id, p2Name, type, detail);
	}

	private static String firstLogo(JsonNode team) {
		if (team.has("logo")) {
			return team.path("logo").asText(null);
		}
		JsonNode logos = team.path("logos");
		if (logos.isArray() && logos.size() > 0) {
			return logos.path(0).path("href").asText(null);
		}
		String id = team.path("id").asText(null);
		return id != null ? "https://a.espncdn.com/i/teamlogos/soccer/500/" + id + ".png" : null;
	}

	private static Integer parseIntOrNull(JsonNode n) {
		if (n == null || n.isMissingNode() || n.isNull()) {
			return null;
		}
		if (n.isNumber()) {
			return n.asInt();
		}
		String s = n.asText("").trim();
		if (s.isEmpty()) {
			return null;
		}
		try {
			return (int)Double.parseDouble(s);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** ESPN 은 "2025-11-01T15:00Z" 처럼 초가 없는 ISO 문자열을 준다. */
	static String normalizeIso(String iso) {
		if (iso.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}Z")) {
			return iso.substring(0, 16) + ":00Z";
		}
		return iso;
	}
}
