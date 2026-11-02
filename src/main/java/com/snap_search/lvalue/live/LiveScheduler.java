package com.snap_search.lvalue.live;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.snap_search.lvalue.config.LiveProperties;
import com.snap_search.lvalue.model.Match;
import com.snap_search.lvalue.provider.FootballDataProvider;
import com.snap_search.lvalue.provider.model.FixtureSnapshot;
import com.snap_search.lvalue.provider.model.LeagueRef;
import com.snap_search.lvalue.repository.MatchRepository;

/**
 * 두 가지 주기 작업.
 *  1) 일정 동기화: 하루 1회(기본 05:00 KST) 리그별 과거 N일~미래 M일 경기를 upsert → 새 시즌/일정 변경이 자동 반영.
 *  2) 라이브 폴링: 60초마다, "지금 진행 중이거나 곧 시작하는 경기가 있는 리그"만 조회 → 변경분 SSE push.
 *     경기가 없는 시간엔 네트워크 호출 0회.
 */
@Component
@ConditionalOnProperty(prefix = "live", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LiveScheduler {

	private static final Logger log = LoggerFactory.getLogger(LiveScheduler.class);
	private static final DateTimeFormatter DB_DATE =
		DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

	private final LiveProperties props;
	private final FootballDataProvider provider;
	private final FixtureSyncService syncService;
	private final LiveBroadcaster broadcaster;
	private final MatchRepository matchRepository;
	private final AtomicBoolean polling = new AtomicBoolean(false);

	public LiveScheduler(LiveProperties props, FootballDataProvider provider, FixtureSyncService syncService,
		LiveBroadcaster broadcaster, MatchRepository matchRepository) {
		this.props = props;
		this.provider = provider;
		this.syncService = syncService;
		this.broadcaster = broadcaster;
		this.matchRepository = matchRepository;
	}

	@Async
	@EventListener(ApplicationReadyEvent.class)
	public void onStartup() {
		if (props.isSyncOnStartup()) {
			syncSchedule();
		}
	}

	@Scheduled(cron = "${live.sync-cron:0 0 5 * * *}", zone = "Asia/Seoul")
	public void syncSchedule() {
		LocalDate today = LocalDate.now(ZoneOffset.UTC);
		LocalDate from = today.minusDays(props.getSyncPastDays());
		LocalDate to = today.plusDays(props.getSyncFutureDays());
		for (LeagueRef league : props.leagueRefs()) {
			try {
				List<FixtureSnapshot> snapshots = provider.fetchFixtures(league, from, to);
				List<LiveUpdate> changed = syncService.upsertAll(provider.name(), snapshots);
				log.info("[Sync] {} : {} fixtures, {} changed", league.name(), snapshots.size(), changed.size());
			} catch (Exception e) {
				log.error("[Sync] {} 실패: {}", league.name(), e.toString());
			}
		}
	}

	@Scheduled(fixedDelayString = "${live.poll-interval-ms:60000}", initialDelayString = "20000")
	public void pollLive() {
		if (!polling.compareAndSet(false, true)) {
			return; // 이전 폴링이 아직 진행 중
		}
		try {
			Set<Integer> activeLeagueIds = activeLeagueIds();
			if (activeLeagueIds.isEmpty()) {
				return;
			}
			LocalDate today = LocalDate.now(ZoneOffset.UTC);
			for (LeagueRef league : props.leagueRefs()) {
				if (!activeLeagueIds.contains((int)league.internalId())) {
					continue;
				}
				try {
					// ESPN 의 날짜 경계는 미국 동부 기준이라 어제~내일을 함께 조회해 경계 누락을 막는다.
					List<FixtureSnapshot> snapshots = provider.fetchFixtures(league, today.minusDays(1),
						today.plusDays(1));
					List<LiveUpdate> changed = syncService.upsertAll(provider.name(), snapshots);
					if (!changed.isEmpty()) {
						broadcaster.publish(changed);
						log.info("[Live] {} : {} updates → {} subscribers", league.name(), changed.size(),
							broadcaster.subscriberCount());
					}
				} catch (Exception e) {
					log.warn("[Live] {} 폴링 실패: {}", league.name(), e.toString());
				}
			}
		} finally {
			polling.set(false);
		}
	}

	/**
	 * 라이브 윈도우(킥오프 -before ~ +after 분) 안에 있고 아직 끝나지 않은 경기가 속한 리그 ID 집합.
	 */
	Set<Integer> activeLeagueIds() {
		Instant now = Instant.now();
		String from = DB_DATE.format(now.minus(props.getLiveWindowAfterMin(), ChronoUnit.MINUTES));
		String to = DB_DATE.format(now.plus(props.getLiveWindowBeforeMin(), ChronoUnit.MINUTES));
		List<Match> window = matchRepository.findByFixtureDateBetween(from, to);
		Set<Integer> ids = new LinkedHashSet<>();
		for (Match m : window) {
			if (m.getLeagueId() != null && !FixtureSnapshot.FINISHED_STATUSES.contains(m.getFixtureStatusShort())) {
				ids.add(m.getLeagueId());
			}
		}
		return ids;
	}
}
