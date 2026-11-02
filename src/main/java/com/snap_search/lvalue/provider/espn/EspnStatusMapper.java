package com.snap_search.lvalue.provider.espn;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ESPN status.type.name → api-football short code 변환.
 */
public final class EspnStatusMapper {

	private static final Map<String, String> BY_NAME = Map.ofEntries(
		Map.entry("STATUS_SCHEDULED", "NS"),
		Map.entry("STATUS_FIRST_HALF", "1H"),
		Map.entry("STATUS_HALFTIME", "HT"),
		Map.entry("STATUS_SECOND_HALF", "2H"),
		Map.entry("STATUS_IN_PROGRESS", "LIVE"),
		Map.entry("STATUS_EXTRA_TIME", "ET"),
		Map.entry("STATUS_OVERTIME", "ET"),
		Map.entry("STATUS_HALFTIME_ET", "BT"),
		Map.entry("STATUS_END_OF_EXTRATIME", "BT"),
		Map.entry("STATUS_SHOOTOUT", "P"),
		Map.entry("STATUS_PENALTIES", "P"),
		Map.entry("STATUS_FULL_TIME", "FT"),
		Map.entry("STATUS_FINAL", "FT"),
		Map.entry("STATUS_FINAL_AET", "AET"),
		Map.entry("STATUS_FINAL_PEN", "PEN"),
		Map.entry("STATUS_POSTPONED", "PST"),
		Map.entry("STATUS_DELAYED", "PST"),
		Map.entry("STATUS_CANCELED", "CANC"),
		Map.entry("STATUS_ABANDONED", "ABD"),
		Map.entry("STATUS_SUSPENDED", "SUSP"),
		Map.entry("STATUS_FORFEIT", "AWD"),
		Map.entry("STATUS_RAIN_DELAY", "INT")
	);

	private static final Map<String, String> LONG_NAMES = Map.ofEntries(
		Map.entry("NS", "Not Started"),
		Map.entry("1H", "First Half"),
		Map.entry("HT", "Halftime"),
		Map.entry("2H", "Second Half"),
		Map.entry("LIVE", "In Progress"),
		Map.entry("ET", "Extra Time"),
		Map.entry("BT", "Break Time"),
		Map.entry("P", "Penalty In Progress"),
		Map.entry("FT", "Match Finished"),
		Map.entry("AET", "Match Finished After Extra Time"),
		Map.entry("PEN", "Match Finished After Penalty"),
		Map.entry("PST", "Match Postponed"),
		Map.entry("CANC", "Match Cancelled"),
		Map.entry("ABD", "Match Abandoned"),
		Map.entry("SUSP", "Match Suspended"),
		Map.entry("INT", "Match Interrupted"),
		Map.entry("AWD", "Technical Loss")
	);

	// "45'+2'", "67'", "90'+4'" 등
	private static final Pattern CLOCK = Pattern.compile("(\\d+)'(?:\\s*\\+\\s*(\\d+)')?");

	private EspnStatusMapper() {
	}

	/**
	 * @param typeName ESPN status.type.name
	 * @param state ESPN status.type.state (pre / in / post) - typeName 을 모를 때의 폴백
	 */
	public static String toShort(String typeName, String state) {
		if (typeName != null) {
			String mapped = BY_NAME.get(typeName);
			if (mapped != null) {
				return mapped;
			}
		}
		if (state == null) {
			return "NS";
		}
		return switch (state) {
			case "in" -> "LIVE";
			case "post" -> "FT";
			default -> "NS";
		};
	}

	public static String toLong(String shortCode) {
		return LONG_NAMES.getOrDefault(shortCode, shortCode);
	}

	/** displayClock("45'+2'") → [elapsed, extra]. 파싱 불가 시 null. */
	public static int[] parseClock(String displayClock) {
		if (displayClock == null) {
			return null;
		}
		Matcher m = CLOCK.matcher(displayClock);
		if (!m.find()) {
			return null;
		}
		int elapsed = Integer.parseInt(m.group(1));
		Integer extra = m.group(2) != null ? Integer.parseInt(m.group(2)) : null;
		return new int[] {elapsed, extra == null ? 0 : extra};
	}
}
