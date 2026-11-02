package com.snap_search.lvalue.controller;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.snap_search.lvalue.live.LiveBroadcaster;
import com.snap_search.lvalue.model.Match;
import com.snap_search.lvalue.provider.model.FixtureSnapshot;
import com.snap_search.lvalue.repository.MatchRepository;

@RestController
@RequestMapping("/api/live")
public class LiveController {

	private static final DateTimeFormatter DB_DATE =
		DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

	private final LiveBroadcaster broadcaster;
	private final MatchRepository matchRepository;

	public LiveController(LiveBroadcaster broadcaster, MatchRepository matchRepository) {
		this.broadcaster = broadcaster;
		this.matchRepository = matchRepository;
	}

	/** SSE 스트림. 이벤트 이름 "match", 데이터는 LiveUpdate JSON. */
	@GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter stream() {
		return broadcaster.subscribe();
	}

	/** 현재 진행 중인 경기 목록 (초기 렌더용). */
	@GetMapping
	public List<Match> live() {
		return matchRepository.findByFixtureStatusShortIn(FixtureSnapshot.LIVE_STATUSES);
	}

	/**
	 * 특정 날짜(기본 KST 오늘)의 경기 목록. "오늘의 경기" 화면용.
	 * @param date yyyy-MM-dd
	 * @param tz 날짜를 해석할 시간대 (기본 Asia/Seoul)
	 */
	@GetMapping("/by-date")
	public List<Match> byDate(@RequestParam(value = "date", required = false) String date,
		@RequestParam(value = "tz", defaultValue = "Asia/Seoul") String tz) {
		ZoneId zone = ZoneId.of(tz);
		LocalDate day = date != null ? LocalDate.parse(date) : LocalDate.now(zone);
		ZonedDateTime start = day.atStartOfDay(zone);
		ZonedDateTime end = start.plusDays(1).minusSeconds(1);
		return matchRepository.findByFixtureDateBetween(DB_DATE.format(start.toInstant()),
			DB_DATE.format(end.toInstant()));
	}
}
