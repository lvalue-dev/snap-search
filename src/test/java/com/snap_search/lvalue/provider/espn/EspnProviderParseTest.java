package com.snap_search.lvalue.provider.espn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.snap_search.lvalue.provider.model.EventSnapshot;
import com.snap_search.lvalue.provider.model.FixtureSnapshot;
import com.snap_search.lvalue.provider.model.LeagueRef;

/**
 * 저장된 ESPN scoreboard 샘플로 파싱 규칙을 검증한다. 네트워크 불필요.
 */
class EspnProviderParseTest {

	private final EspnProvider provider = new EspnProvider(null);
	private final LeagueRef epl = new LeagueRef(39, "Premier League", "eng.1");

	private JsonNode sample() throws Exception {
		try (InputStream in = getClass().getResourceAsStream("/espn/scoreboard-sample.json")) {
			return new ObjectMapper().readTree(in);
		}
	}

	@Test
	void parsesLiveScheduledAndFinishedFixtures() throws Exception {
		List<FixtureSnapshot> fixtures = provider.parseScoreboard(sample(), epl);
		assertEquals(3, fixtures.size());

		FixtureSnapshot live = fixtures.get(0);
		assertEquals("740562", live.externalId());
		assertEquals(2025, live.season());
		assertEquals("Regular Season - 10", live.round());
		assertEquals(Instant.parse("2025-11-01T15:00:00Z"), live.kickoff());
		assertEquals("2H", live.statusShort());
		assertEquals(72, live.elapsed());
		assertNull(live.extra());
		assertEquals("380", live.home().externalId());
		assertEquals("Wolverhampton Wanderers", live.home().name());
		assertEquals("https://a.espncdn.com/i/teamlogos/soccer/500/331.png", live.away().logo());
		assertEquals(1, live.goalsHome());
		assertEquals(2, live.goalsAway());
		assertEquals("Molineux Stadium", live.venueName());
		assertEquals("Wolverhampton", live.venueCity());
		assertTrue(live.isLive());

		FixtureSnapshot scheduled = fixtures.get(1);
		assertEquals("NS", scheduled.statusShort());
		assertNull(scheduled.goalsHome(), "예정 경기의 0 스코어는 null 로 정리");
		assertNull(scheduled.elapsed());
		assertNull(scheduled.round());

		FixtureSnapshot finished = fixtures.get(2);
		assertEquals("FT", finished.statusShort());
		assertEquals(90, finished.elapsed());
		assertEquals(2, finished.goalsHome());
		assertTrue(finished.isFinished());
	}

	@Test
	void mapsDetailsToApiFootballVocabulary() throws Exception {
		FixtureSnapshot live = provider.parseScoreboard(sample(), epl).get(0);
		List<EventSnapshot> events = live.events();
		assertEquals(5, events.size(), "Kickoff 같은 비이벤트는 제외");

		EventSnapshot goal = events.get(0);
		assertEquals("Goal", goal.type());
		assertEquals("Normal Goal", goal.detail());
		assertEquals(23, goal.elapsed());
		assertEquals("331", goal.externalTeamId());
		assertEquals("Danny Welbeck", goal.playerName());
		assertEquals("245400", goal.externalPlayerId());

		EventSnapshot yellow = events.get(1);
		assertEquals("Card", yellow.type());
		assertEquals("Yellow Card", yellow.detail());

		EventSnapshot pen = events.get(2);
		assertEquals("Goal", pen.type());
		assertEquals("Penalty", pen.detail());
		assertEquals(45, pen.elapsed());
		assertEquals(2, pen.extra());

		EventSnapshot sub = events.get(3);
		assertEquals("subst", sub.type());
		assertEquals("Player In", sub.playerName());
		assertEquals("Player Out", sub.assistName());

		EventSnapshot og = events.get(4);
		assertEquals("Own Goal", og.detail());
	}

	@Test
	void statusMapperFallsBackToState() {
		assertEquals("LIVE", EspnStatusMapper.toShort("STATUS_SOMETHING_NEW", "in"));
		assertEquals("FT", EspnStatusMapper.toShort(null, "post"));
		assertEquals("NS", EspnStatusMapper.toShort(null, null));
		assertEquals("PST", EspnStatusMapper.toShort("STATUS_POSTPONED", "pre"));
	}

	@Test
	void parsesClockVariants() {
		assertEquals(45, EspnStatusMapper.parseClock("45'+2'")[0]);
		assertEquals(2, EspnStatusMapper.parseClock("45'+2'")[1]);
		assertEquals(0, EspnStatusMapper.parseClock("67'")[1]);
		assertNull(EspnStatusMapper.parseClock("FT"));
		assertNull(EspnStatusMapper.parseClock(null));
	}

	@Test
	void normalizesIsoWithoutSeconds() {
		assertEquals("2025-11-01T15:00:00Z", EspnProvider.normalizeIso("2025-11-01T15:00Z"));
		assertEquals("2025-11-01T15:00:00Z", EspnProvider.normalizeIso("2025-11-01T15:00:00Z"));
	}
}
