package com.snap_search.lvalue;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.snap_search.lvalue.config.LiveProperties;

@SpringBootApplication
@EnableScheduling
@EnableAsync
@EnableConfigurationProperties(LiveProperties.class)
public class LvalueApplication {

	public static void main(String[] args) {
		SpringApplication.run(LvalueApplication.class, args);
	}

}
