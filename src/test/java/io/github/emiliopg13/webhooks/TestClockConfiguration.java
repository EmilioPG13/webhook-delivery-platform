package io.github.emiliopg13.webhooks;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the application's clock with one the tests can move. */
@TestConfiguration(proxyBeanMethods = false)
class TestClockConfiguration {

	@Bean
	@Primary
	OffsetClock testClock() {
		return new OffsetClock();
	}

}
