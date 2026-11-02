package com.snap_search.lvalue.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.snap_search.lvalue.config.LiveProperties;
import com.snap_search.lvalue.model.Team;

class TeamMappingServiceTest {

	private static Team team(long id, String name) {
		Team t = new Team();
		t.setId(id);
		t.setName(name);
		return t;
	}

	private final List<Team> internal = List.of(
		team(33, "Manchester United"), team(50, "Manchester City"), team(39, "Wolves"),
		team(51, "Brighton"), team(34, "Newcastle"), team(47, "Tottenham"), team(48, "West Ham"),
		team(85, "Paris Saint Germain"), team(157, "Bayern Munich"), team(163, "Borussia Monchengladbach"),
		team(192, "FC Koln"), team(530, "Atletico Madrid"), team(531, "Athletic Club"), team(727, "Osasuna"),
		team(106, "Stade Brestois 29"), team(84, "Nice"), team(40, "Liverpool"), team(41, "Southampton")
	);

	private TeamMappingService service(LiveProperties props) {
		return new TeamMappingService(null, null, props);
	}

	@Test
	void normalizeStripsNoiseAndDiacritics() {
		assertEquals("borussia monchengladbach", TeamMappingService.normalize("Borussia Mönchengladbach"));
		assertEquals("koln", TeamMappingService.normalize("1. FC Köln"));
		assertEquals("paris saint germain", TeamMappingService.normalize("Paris Saint-Germain"));
		assertEquals("brighton hove albion", TeamMappingService.normalize("Brighton & Hove Albion"));
		assertEquals("nice", TeamMappingService.normalize("OGC Nice"));
	}

	@Test
	void matchesExactContainmentAndFirstToken() {
		TeamMappingService s = service(new LiveProperties());
		assertEquals(33L, s.matchByName("Manchester United", internal).get().getId());
		assertEquals(51L, s.matchByName("Brighton & Hove Albion", internal).get().getId());
		assertEquals(34L, s.matchByName("Newcastle United", internal).get().getId());
		assertEquals(47L, s.matchByName("Tottenham Hotspur", internal).get().getId());
		assertEquals(163L, s.matchByName("Borussia Mönchengladbach", internal).get().getId());
		assertEquals(192L, s.matchByName("1. FC Köln", internal).get().getId());
		assertEquals(530L, s.matchByName("Atlético Madrid", internal).get().getId());
		assertEquals(84L, s.matchByName("OGC Nice", internal).get().getId());
		assertEquals(85L, s.matchByName("Paris Saint-Germain", internal).get().getId());
	}

	@Test
	void ambiguousContainmentIsRejected() {
		TeamMappingService s = service(new LiveProperties());
		// "Manchester" 만으로는 United/City 둘 다 걸리므로 매칭하지 않아야 한다
		assertTrue(s.matchByName("Manchester", internal).isEmpty());
	}

	@Test
	void aliasOverridesHeuristics() {
		LiveProperties props = new LiveProperties();
		props.getTeamAliases().put("Stade Brestois 29", "Brest");
		props.getTeamAliases().put("Wolves", "Wolverhampton Wanderers");
		TeamMappingService s = service(props);
		Optional<Team> brest = s.matchByName("Brest", internal);
		assertEquals(106L, brest.get().getId());
		assertEquals(39L, s.matchByName("Wolverhampton Wanderers", internal).get().getId());
	}
}
