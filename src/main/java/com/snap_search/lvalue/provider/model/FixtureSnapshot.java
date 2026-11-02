package com.snap_search.lvalue.provider.model;

import java.time.Instant;
import java.util.List;

/**
 * 공급자 중립 경기 스냅샷. 상태 코드는 api-football 의 short code(NS, 1H, HT, 2H, FT, PST ...)로 정규화한다.
 */
public record FixtureSnapshot(
	String externalId,
	LeagueRef league,
	int season,
	String round,
	Instant kickoff,
	String statusShort,
	String statusLong,
	Integer elapsed,
	Integer extra,
	TeamSnapshot home,
	TeamSnapshot away,
	Integer goalsHome,
	Integer goalsAway,
	Integer halftimeHome,
	Integer halftimeAway,
	String venueName,
	String venueCity,
	List<EventSnapshot> events
) {
	public static final java.util.Set<String> LIVE_STATUSES =
		java.util.Set.of("1H", "HT", "2H", "ET", "BT", "P", "SUSP", "INT", "LIVE");
	public static final java.util.Set<String> FINISHED_STATUSES =
		java.util.Set.of("FT", "AET", "PEN", "PST", "CANC", "ABD", "AWD", "WO");

	public boolean isLive() {
		return LIVE_STATUSES.contains(statusShort);
	}

	public boolean isFinished() {
		return FINISHED_STATUSES.contains(statusShort);
	}
}
