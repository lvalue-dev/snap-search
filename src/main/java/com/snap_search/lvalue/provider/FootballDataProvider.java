package com.snap_search.lvalue.provider;

import java.time.LocalDate;
import java.util.List;

import com.snap_search.lvalue.provider.model.FixtureSnapshot;
import com.snap_search.lvalue.provider.model.LeagueRef;
import com.snap_search.lvalue.provider.model.TeamSnapshot;

/**
 * 경기 데이터 공급자 추상화.
 * 구현체를 바꿔 끼우면(ESPN, football-data.org, API-Football 유료 등) 나머지 서비스 계층은 그대로 쓴다.
 */
public interface FootballDataProvider {

	/** 공급자 식별 문자열. external_id_map.provider 컬럼에 저장된다. */
	String name();

	/**
	 * 날짜 범위(UTC 기준, 양끝 포함)의 경기를 조회한다. 진행 중 경기는 현재 스코어/경과 시간/이벤트를 포함한다.
	 */
	List<FixtureSnapshot> fetchFixtures(LeagueRef league, LocalDate from, LocalDate to);

	/** 리그 소속 팀 목록(ID 매핑 시드용). */
	List<TeamSnapshot> fetchTeams(LeagueRef league);
}
