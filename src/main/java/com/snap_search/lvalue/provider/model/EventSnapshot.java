package com.snap_search.lvalue.provider.model;

/**
 * 공급자 중립 경기 이벤트. type/detail 은 api-football 어휘(Goal, Card, subst, Var)를 따른다.
 * 프론트엔드(constants/eventTypes.ts)가 그 어휘에 의존하기 때문.
 */
public record EventSnapshot(
	int elapsed,
	Integer extra,
	String externalTeamId,
	String externalPlayerId,
	String playerName,
	String externalAssistId,
	String assistName,
	String type,
	String detail
) {
}
