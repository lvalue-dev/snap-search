package com.snap_search.lvalue.provider.model;

/**
 * 내부(api-football 기준) 리그 ID와 외부 공급자 식별자를 함께 담는 참조.
 */
public record LeagueRef(long internalId, String name, String espnSlug) {
}
