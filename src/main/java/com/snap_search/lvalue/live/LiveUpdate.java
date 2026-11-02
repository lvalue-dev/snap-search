package com.snap_search.lvalue.live;

import java.time.Instant;
import java.util.List;

import com.snap_search.lvalue.dto.MatchEventDTO;
import com.snap_search.lvalue.model.Match;

/**
 * SSE 로 내려가는 라이브 갱신 페이로드. 프론트의 Match 타입과 동일한 필드를 그대로 쓴다.
 */
public record LiveUpdate(Match match, List<MatchEventDTO> events, Instant updatedAt) {
}
