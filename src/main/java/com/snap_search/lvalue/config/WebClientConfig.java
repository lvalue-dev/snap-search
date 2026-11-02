package com.snap_search.lvalue.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

@Configuration
public class WebClientConfig {

	private static final Logger logger = LoggerFactory.getLogger(WebClientConfig.class);

	@Value("${api.rapidapi.host}")
	private String baseUrl;

	@Value("${api.rapidapi.key}")
	private String apiKey;

	@Bean(name = "webClientForDefault")
	public WebClient webClient(WebClient.Builder builder) {
		return builder.baseUrl(baseUrl).defaultHeader("x-rapidapi-key", apiKey).filter(logRequest())
			.filter(logResponse()).build();
	}

	/**
	 * API 응답 데이터가 많을 경우, 사용하는 webClient
	 * @param builder
	 * @return
	 */
	@Bean(name = "webClientForBigData")
	public WebClient webClientForBigData(WebClient.Builder builder) {
		return builder
			.baseUrl(baseUrl)
			.defaultHeader("x-rapidapi-key", apiKey)
			.codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(5 * 1024 * 1024)) // 버퍼 크기 설정
			.build();
	}

	/**
	 * ESPN 비공개 JSON API 용 WebClient. 키 없음, UA 만 명시. 날짜 범위 조회 응답이 커서 버퍼를 넉넉히 잡는다.
	 */
	@Bean(name = "webClientForEspn")
	public WebClient webClientForEspn(WebClient.Builder builder, LiveProperties liveProperties) {
		HttpClient httpClient = HttpClient.create().responseTimeout(java.time.Duration.ofSeconds(20));
		return builder.clone()
			.baseUrl(liveProperties.getEspn().getBaseUrl())
			.defaultHeader(HttpHeaders.USER_AGENT, liveProperties.getEspn().getUserAgent())
			.defaultHeader(HttpHeaders.ACCEPT, "application/json")
			.clientConnector(new ReactorClientHttpConnector(httpClient))
			.codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
			.build();
	}

	// 요청 로깅 필터
	private ExchangeFilterFunction logRequest() {
		return ExchangeFilterFunction.ofRequestProcessor(request -> {
			logger.info("Request: {} {}", request.method(), request.url());
			request.headers().forEach((name, values) ->
				values.forEach(value -> logger.info("Request Header: {}={}", name, value))
			);
			return Mono.just(request);
		});
	}

	// 응답 로깅 필터
	private ExchangeFilterFunction logResponse() {
		return ExchangeFilterFunction.ofResponseProcessor(response -> {
			logger.info("Response status: {}", response.statusCode());
			response.headers().asHttpHeaders().forEach((name, values) ->
				values.forEach(value -> logger.info("Response Header: {}={}", name, value))
			);
			return Mono.just(response);
		});
	}
}
