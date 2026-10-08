package io.github.emiliopg13.webhooks.common;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class TimeConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
