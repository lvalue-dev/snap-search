package com.snap_search.lvalue.live;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SseEmitter 레지스트리. 변경된 경기만 push 하고, 25초마다 heartbeat 로 프록시 타임아웃을 막는다.
 */
@Component
public class LiveBroadcaster {

	private static final Logger log = LoggerFactory.getLogger(LiveBroadcaster.class);
	private static final long EMITTER_TIMEOUT_MS = 60 * 60 * 1000L;

	private final Set<SseEmitter> emitters = new CopyOnWriteArraySet<>();

	public SseEmitter subscribe() {
		SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
		emitters.add(emitter);
		emitter.onCompletion(() -> emitters.remove(emitter));
		emitter.onTimeout(() -> emitters.remove(emitter));
		emitter.onError(e -> emitters.remove(emitter));
		try {
			emitter.send(SseEmitter.event().name("connected").data("ok"));
		} catch (IOException e) {
			emitters.remove(emitter);
		}
		return emitter;
	}

	public int subscriberCount() {
		return emitters.size();
	}

	public void publish(List<LiveUpdate> updates) {
		if (updates.isEmpty() || emitters.isEmpty()) {
			return;
		}
		for (LiveUpdate update : updates) {
			SseEmitter.SseEventBuilder event = SseEmitter.event()
				.name("match")
				.id(String.valueOf(update.match().getFixtureId()))
				.data(update);
			for (SseEmitter emitter : emitters) {
				try {
					emitter.send(event);
				} catch (Exception e) {
					emitters.remove(emitter);
				}
			}
		}
		log.debug("[Live] pushed {} updates to {} subscribers", updates.size(), emitters.size());
	}

	@Scheduled(fixedRate = 25_000)
	public void heartbeat() {
		for (SseEmitter emitter : emitters) {
			try {
				emitter.send(SseEmitter.event().comment("ping"));
			} catch (Exception e) {
				emitters.remove(emitter);
			}
		}
	}
}
