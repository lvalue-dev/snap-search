package com.snap_search.lvalue.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.snap_search.lvalue.provider.model.LeagueRef;

/**
 * 실시간 데이터 수집 설정. application.properties 의 live.* 항목.
 */
@ConfigurationProperties(prefix = "live")
public class LiveProperties {

	/** 전체 기능 on/off */
	private boolean enabled = true;
	/** 진행 중 경기 폴링 간격(ms). ESPN 은 공식 제한이 없으니 60초가 예의상 적정선. */
	private long pollIntervalMs = 60_000;
	/** 전체 일정 동기화 cron (KST) */
	private String syncCron = "0 0 5 * * *";
	private boolean syncOnStartup = true;
	/** 일정 동기화 범위: 오늘 기준 과거/미래 일수 */
	private int syncPastDays = 3;
	private int syncFutureDays = 45;
	/** 킥오프 전 몇 분부터 라이브 폴링을 시작할지 */
	private int liveWindowBeforeMin = 15;
	/** 킥오프 후 몇 분까지 라이브로 간주할지 (FT 가 안 찍히는 경우 안전장치) */
	private int liveWindowAfterMin = 180;
	private List<LeagueConfig> leagues = new ArrayList<>();
	/** 내부 팀명(api-football) → 외부 팀명(ESPN displayName) 별칭. 자동 매칭 실패 시 보정용. */
	private Map<String, String> teamAliases = new LinkedHashMap<>();
	private Espn espn = new Espn();

	public static class LeagueConfig {
		private long id;
		private String name;
		private String espnSlug;

		public long getId() {
			return id;
		}

		public void setId(long id) {
			this.id = id;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public String getEspnSlug() {
			return espnSlug;
		}

		public void setEspnSlug(String espnSlug) {
			this.espnSlug = espnSlug;
		}

		public LeagueRef toRef() {
			return new LeagueRef(id, name, espnSlug);
		}
	}

	public static class Espn {
		private String baseUrl = "https://site.api.espn.com";
		private String userAgent = "snap-search/1.0 (+https://www.toomuchoffside.site)";

		public String getBaseUrl() {
			return baseUrl;
		}

		public void setBaseUrl(String baseUrl) {
			this.baseUrl = baseUrl;
		}

		public String getUserAgent() {
			return userAgent;
		}

		public void setUserAgent(String userAgent) {
			this.userAgent = userAgent;
		}
	}

	public List<LeagueRef> leagueRefs() {
		return leagues.stream().map(LeagueConfig::toRef).toList();
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public long getPollIntervalMs() {
		return pollIntervalMs;
	}

	public void setPollIntervalMs(long pollIntervalMs) {
		this.pollIntervalMs = pollIntervalMs;
	}

	public String getSyncCron() {
		return syncCron;
	}

	public void setSyncCron(String syncCron) {
		this.syncCron = syncCron;
	}

	public boolean isSyncOnStartup() {
		return syncOnStartup;
	}

	public void setSyncOnStartup(boolean syncOnStartup) {
		this.syncOnStartup = syncOnStartup;
	}

	public int getSyncPastDays() {
		return syncPastDays;
	}

	public void setSyncPastDays(int syncPastDays) {
		this.syncPastDays = syncPastDays;
	}

	public int getSyncFutureDays() {
		return syncFutureDays;
	}

	public void setSyncFutureDays(int syncFutureDays) {
		this.syncFutureDays = syncFutureDays;
	}

	public int getLiveWindowBeforeMin() {
		return liveWindowBeforeMin;
	}

	public void setLiveWindowBeforeMin(int liveWindowBeforeMin) {
		this.liveWindowBeforeMin = liveWindowBeforeMin;
	}

	public int getLiveWindowAfterMin() {
		return liveWindowAfterMin;
	}

	public void setLiveWindowAfterMin(int liveWindowAfterMin) {
		this.liveWindowAfterMin = liveWindowAfterMin;
	}

	public List<LeagueConfig> getLeagues() {
		return leagues;
	}

	public void setLeagues(List<LeagueConfig> leagues) {
		this.leagues = leagues;
	}

	public Map<String, String> getTeamAliases() {
		return teamAliases;
	}

	public void setTeamAliases(Map<String, String> teamAliases) {
		this.teamAliases = teamAliases;
	}

	public Espn getEspn() {
		return espn;
	}

	public void setEspn(Espn espn) {
		this.espn = espn;
	}
}
