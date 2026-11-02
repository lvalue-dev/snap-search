package com.snap_search.lvalue.scheduler;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.snap_search.lvalue.repository.TeamRepository;
import com.snap_search.lvalue.service.MatchService;

/**
 * API-Football 기반 레거시 배치. 무료 플랜은 현 시즌 조회가 막혀 있어 기본 비활성.
 * 유료 플랜으로 올리면 batch.api-football.enabled=true 로 다시 켤 수 있다.
 */
@Component
@ConditionalOnProperty(prefix = "batch.api-football", name = "enabled", havingValue = "true")
public class MatchBatchScheduler {

	private static final Logger logger = LoggerFactory.getLogger(MatchBatchScheduler.class);
	@Autowired
	private MatchService matchService;

	@Autowired
	private TeamRepository teamRepository;

	// 적용 대상 리그 ID 리스트
	private static final List<Integer> TARGET_LEAGUES = List.of(39, 61, 78, 140);

	/**
	 * 매일 00:00 기준으로 업데이트가 필요한 경기 정보를 가져와 업데이트하는 배치 작업
	 */
	@Scheduled(cron = "0 15 0 * * *", zone = "Asia/Seoul")
	public void fetchMatchDataBatch() {
		logger.info("[Match Batch] 경기 데이터 업데이트 시작");

		List<Integer> teamIds = teamRepository.findTeamIdsByLeagueId(TARGET_LEAGUES);

		for (int teamId : teamIds) {
			try {
				matchService.fetchMatcheData(teamId);
				System.out.println("Match batch completed for teamId: " + teamId);
			} catch (Exception e) {
				System.err.println("Error processing match batch for teamId: " + teamId + " - " + e.getMessage());
			}
		}
	}
}

