package com.snap_search.lvalue.live;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.snap_search.lvalue.dto.MatchEventDTO;
import com.snap_search.lvalue.model.ExternalIdMap;
import com.snap_search.lvalue.model.Match;
import com.snap_search.lvalue.model.MatchEvents;
import com.snap_search.lvalue.provider.model.EventSnapshot;
import com.snap_search.lvalue.provider.model.FixtureSnapshot;
import com.snap_search.lvalue.provider.model.TeamSnapshot;
import com.snap_search.lvalue.repository.ExternalIdMapRepository;
import com.snap_search.lvalue.repository.MatchEventRepository;
import com.snap_search.lvalue.repository.MatchRepository;

/**
 * 공급자 스냅샷 → fixture_stats / fixture_events upsert.
 * 기존 컬럼 포맷(fixture_date 문자열 "yyyy-MM-dd HH:mm:ss UTC")을 유지해 프론트/기존 쿼리와 호환한다.
 */
@Service
public class FixtureSyncService {

	/** 외부 경기 ID 에 더하는 내부 fixture_id 네임스페이스 (api-football fixture id 와 충돌 방지) */
	public static final long FIXTURE_ID_OFFSET = 2_000_000_000L;

	private static final Logger log = LoggerFactory.getLogger(FixtureSyncService.class);
	private static final DateTimeFormatter DB_DATE =
		DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

	private final MatchRepository matchRepository;
	private final MatchEventRepository eventRepository;
	private final ExternalIdMapRepository mapRepository;
	private final TeamMappingService teamMapping;

	public FixtureSyncService(MatchRepository matchRepository, MatchEventRepository eventRepository,
		ExternalIdMapRepository mapRepository, TeamMappingService teamMapping) {
		this.matchRepository = matchRepository;
		this.eventRepository = eventRepository;
		this.mapRepository = mapRepository;
		this.teamMapping = teamMapping;
	}

	/**
	 * 스냅샷을 DB 에 반영하고, 스코어/상태/경과시간이 바뀐 경기만 돌려준다 (SSE push 대상).
	 */
	@Transactional
	public List<LiveUpdate> upsertAll(String provider, List<FixtureSnapshot> snapshots) {
		List<LiveUpdate> changed = new ArrayList<>();
		for (FixtureSnapshot s : snapshots) {
			try {
				upsertOne(provider, s).ifPresent(changed::add);
			} catch (RuntimeException e) {
				log.error("[Sync] fixture {} 저장 실패: {}", s.externalId(), e.toString());
			}
		}
		return changed;
	}

	Optional<LiveUpdate> upsertOne(String provider, FixtureSnapshot s) {
		long fixtureId = resolveFixtureId(provider, s.externalId());
		Match match = matchRepository.findById(fixtureId).orElseGet(Match::new);
		boolean isNew = match.getFixtureId() == null;

		String before = signature(match);

		long homeId = teamMapping.resolveTeamId(provider, s.home(), s.league());
		long awayId = teamMapping.resolveTeamId(provider, s.away(), s.league());

		match.setFixtureId(fixtureId);
		match.setFixtureDate(DB_DATE.format(s.kickoff()));
		match.setFixtureStatusShort(s.statusShort());
		match.setFixtureStatusLong(s.statusLong());
		match.setFixtureStatusElapsed(s.elapsed());
		match.setFixtureStatusExtra(s.extra());
		match.setLeagueId((int)s.league().internalId());
		match.setLeagueName(s.league().name());
		match.setLeagueSeason(s.season());
		if (s.round() != null) {
			match.setLeagueRound(s.round());
		}
		applyTeam(match, s.home(), homeId, true);
		applyTeam(match, s.away(), awayId, false);
		match.setGoalsHome(s.goalsHome());
		match.setGoalsAway(s.goalsAway());
		match.setScoreHalftimeHome(s.halftimeHome());
		match.setScoreHalftimeAway(s.halftimeAway());
		if (s.isFinished() && s.goalsHome() != null && s.goalsAway() != null) {
			match.setScoreFulltimeHome(s.goalsHome());
			match.setScoreFulltimeAway(s.goalsAway());
			match.setTeamsHomeWinner(winner(s.goalsHome(), s.goalsAway()));
			match.setTeamsAwayWinner(winner(s.goalsAway(), s.goalsHome()));
		} else {
			match.setTeamsHomeWinner(null);
			match.setTeamsAwayWinner(null);
		}
		if (s.venueName() != null) {
			match.setFixtureVenueName(s.venueName());
			match.setFixtureVenueCity(s.venueCity());
		}
		matchRepository.save(match);

		List<MatchEventDTO> eventDtos = List.of();
		boolean eventsChanged = false;
		if ((s.isLive() || s.isFinished()) && s.events() != null) {
			List<MatchEvents> existing = eventRepository.findByFixtureIdOrderByTimeElapsed(fixtureId);
			if (existing.size() != s.events().size()) {
				eventRepository.deleteAll(existing);
				List<MatchEvents> fresh = s.events().stream()
					.map(e -> toEntity(fixtureId, e, s, homeId, awayId)).toList();
				eventRepository.saveAll(fresh);
				eventDtos = fresh.stream().map(MatchEventDTO::fromEntity).toList();
				eventsChanged = true;
			} else {
				eventDtos = existing.stream().map(MatchEventDTO::fromEntity).toList();
			}
		}

		boolean changed = isNew || eventsChanged || !before.equals(signature(match));
		if (changed && (s.isLive() || s.isFinished())) {
			return Optional.of(new LiveUpdate(match, eventDtos, Instant.now()));
		}
		return Optional.empty();
	}

	private long resolveFixtureId(String provider, String externalId) {
		return mapRepository.findByProviderAndEntityTypeAndExternalId(provider, ExternalIdMap.EntityType.FIXTURE,
				externalId)
			.map(ExternalIdMap::getInternalId)
			.orElseGet(() -> {
				long internal = FIXTURE_ID_OFFSET + Long.parseLong(externalId);
				mapRepository.save(new ExternalIdMap(provider, ExternalIdMap.EntityType.FIXTURE, externalId, internal,
					true));
				return internal;
			});
	}

	private static void applyTeam(Match m, TeamSnapshot t, long internalId, boolean home) {
		if (home) {
			m.setTeamsHomeId((int)internalId);
			m.setTeamsHomeName(t.name());
			if (m.getTeamsHomeLogo() == null) {
				m.setTeamsHomeLogo(t.logo());
			}
		} else {
			m.setTeamsAwayId((int)internalId);
			m.setTeamsAwayName(t.name());
			if (m.getTeamsAwayLogo() == null) {
				m.setTeamsAwayLogo(t.logo());
			}
		}
	}

	private MatchEvents toEntity(long fixtureId, EventSnapshot e, FixtureSnapshot s, long homeId, long awayId) {
		MatchEvents ev = new MatchEvents();
		ev.setFixtureId(fixtureId);
		ev.setTimeElapsed(e.elapsed());
		ev.setTimeExtra(e.extra());
		boolean isHome = e.externalTeamId() != null && e.externalTeamId().equals(s.home().externalId());
		ev.setTeamId((int)(isHome ? homeId : awayId));
		ev.setTeamName(isHome ? s.home().name() : s.away().name());
		ev.setTeamLogo(isHome ? s.home().logo() : s.away().logo());
		ev.setPlayerId(parseIntOrNull(e.externalPlayerId()));
		ev.setPlayerName(e.playerName());
		ev.setAssistId(parseIntOrNull(e.externalAssistId()));
		ev.setAssistName(e.assistName());
		ev.setEventType(e.type());
		ev.setDetail(e.detail());
		return ev;
	}

	private static Integer parseIntOrNull(String s) {
		if (s == null) {
			return null;
		}
		try {
			long v = Long.parseLong(s);
			return v > Integer.MAX_VALUE ? null : (int)v;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static String winner(Integer mine, Integer theirs) {
		if (mine == null || theirs == null) {
			return null;
		}
		return mine > theirs ? "true" : mine < theirs ? "false" : null;
	}

	private static String signature(Match m) {
		return String.join("|",
			Objects.toString(m.getFixtureStatusShort()), Objects.toString(m.getFixtureStatusElapsed()),
			Objects.toString(m.getFixtureStatusExtra()), Objects.toString(m.getGoalsHome()),
			Objects.toString(m.getGoalsAway()), Objects.toString(m.getFixtureDate()));
	}
}
