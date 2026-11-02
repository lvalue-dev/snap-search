package com.snap_search.lvalue.live;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.snap_search.lvalue.config.LiveProperties;
import com.snap_search.lvalue.model.ExternalIdMap;
import com.snap_search.lvalue.model.Team;
import com.snap_search.lvalue.provider.model.LeagueRef;
import com.snap_search.lvalue.provider.model.TeamSnapshot;
import com.snap_search.lvalue.repository.ExternalIdMapRepository;
import com.snap_search.lvalue.repository.TeamRepository;

/**
 * 외부 공급자의 팀 → 내부 team.id(api-football ID) 해석.
 *
 * 순서: external_id_map 조회 → 별칭 설정 → 정규화된 이름 정확 일치 → 포함 관계 → 실패 시 합성 ID 로 Team 신규 생성.
 * 자동 매칭 결과는 auto=true 로 저장되어 나중에 수정 가능.
 */
@Service
public class TeamMappingService {

	/** 매칭에 실패한 외부 팀에 부여하는 내부 ID 네임스페이스 (api-football ID 와 겹치지 않도록) */
	public static final long SYNTHETIC_TEAM_OFFSET = 9_000_000L;

	private static final Logger log = LoggerFactory.getLogger(TeamMappingService.class);
	private static final Pattern NOISE = Pattern.compile(
		"\\b(fc|cf|afc|sc|ac|as|ssc|us|ogc|rc|rcd|ud|cd|sd|tsg|vfl|vfb|sv|fsv|bsc|1899|1846|1\\.|club|de|futbol|calcio|\\d{1,2})\\b");
	private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9 ]");

	private final ExternalIdMapRepository mapRepository;
	private final TeamRepository teamRepository;
	private final LiveProperties properties;

	public TeamMappingService(ExternalIdMapRepository mapRepository, TeamRepository teamRepository,
		LiveProperties properties) {
		this.mapRepository = mapRepository;
		this.teamRepository = teamRepository;
		this.properties = properties;
	}

	@Transactional
	public long resolveTeamId(String provider, TeamSnapshot external, LeagueRef league) {
		Optional<ExternalIdMap> existing = mapRepository.findByProviderAndEntityTypeAndExternalId(
			provider, ExternalIdMap.EntityType.TEAM, external.externalId());
		if (existing.isPresent()) {
			return existing.get().getInternalId();
		}

		List<Team> teams = teamRepository.findAll();
		Optional<Team> matched = matchByName(external.name(), teams);

		long internalId;
		if (matched.isPresent()) {
			internalId = matched.get().getId();
			log.info("[TeamMap] '{}' ({}:{}) → team {} '{}'", external.name(), provider, external.externalId(),
				internalId, matched.get().getName());
		} else {
			internalId = SYNTHETIC_TEAM_OFFSET + Long.parseLong(external.externalId());
			if (teamRepository.findById(internalId).isEmpty()) {
				Team t = new Team();
				t.setId(internalId);
				t.setName(external.name());
				t.setLogo(external.logo());
				t.setLeagueIds(String.valueOf(league.internalId()));
				teamRepository.save(t);
			}
			log.warn("[TeamMap] '{}' ({}:{}) 매칭 실패 → 합성 팀 {} 생성. live.team-aliases 로 보정하세요.",
				external.name(), provider, external.externalId(), internalId);
		}
		mapRepository.save(new ExternalIdMap(provider, ExternalIdMap.EntityType.TEAM, external.externalId(),
			internalId, true));
		return internalId;
	}

	Optional<Team> matchByName(String externalName, List<Team> teams) {
		if (externalName == null) {
			return Optional.empty();
		}
		String target = normalize(externalName);

		// 1) 별칭: 내부 이름 → 외부 이름
		for (Map.Entry<String, String> alias : properties.getTeamAliases().entrySet()) {
			if (normalize(alias.getValue()).equals(target)) {
				String internalNorm = normalize(alias.getKey());
				Optional<Team> t = teams.stream().filter(x -> normalize(x.getName()).equals(internalNorm)).findFirst();
				if (t.isPresent()) {
					return t;
				}
			}
		}

		// 2) 정규화 후 정확 일치
		Map<String, Team> byNorm = new HashMap<>();
		for (Team t : teams) {
			if (t.getName() != null) {
				byNorm.putIfAbsent(normalize(t.getName()), t);
			}
		}
		if (byNorm.containsKey(target)) {
			return Optional.of(byNorm.get(target));
		}

		// 3) 포함 관계 (Wolves ⊂ Wolverhampton Wanderers, Brighton ⊂ Brighton Hove Albion). 유일할 때만 채택.
		String compactTarget = target.replace(" ", "");
		List<Team> candidates = new ArrayList<>();
		for (Map.Entry<String, Team> e : byNorm.entrySet()) {
			String compact = e.getKey().replace(" ", "");
			if (compact.length() < 4 || compactTarget.length() < 4) {
				continue;
			}
			if (compact.contains(compactTarget) || compactTarget.contains(compact)) {
				candidates.add(e.getValue());
			}
		}
		if (candidates.size() == 1) {
			return Optional.of(candidates.get(0));
		}

		// 4) 첫 토큰 일치가 유일한 경우 (Newcastle ↔ Newcastle United)
		String firstToken = target.split(" ")[0];
		if (firstToken.length() >= 5) {
			List<Team> tokenHits = byNorm.entrySet().stream()
				.filter(e -> e.getKey().split(" ")[0].equals(firstToken))
				.map(Map.Entry::getValue).toList();
			if (tokenHits.size() == 1) {
				return Optional.of(tokenHits.get(0));
			}
		}
		return Optional.empty();
	}

	static String normalize(String name) {
		if (name == null) {
			return "";
		}
		String s = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
		s = s.toLowerCase(Locale.ROOT).replace("&", " and ").replace("-", " ");
		s = NON_ALNUM.matcher(s).replaceAll(" ");
		s = NOISE.matcher(s).replaceAll(" ");
		s = s.replace(" and ", " ");
		return s.trim().replaceAll("\\s+", " ");
	}

	public Set<Long> syntheticTeamIds() {
		Set<Long> ids = new java.util.HashSet<>();
		for (ExternalIdMap m : mapRepository.findByProviderAndEntityType("espn", ExternalIdMap.EntityType.TEAM)) {
			if (m.getInternalId() >= SYNTHETIC_TEAM_OFFSET) {
				ids.add(m.getInternalId());
			}
		}
		return ids;
	}
}
