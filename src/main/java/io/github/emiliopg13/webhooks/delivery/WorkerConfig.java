package io.github.emiliopg13.webhooks.delivery;

import java.security.SecureRandom;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Switches on what the {@code worker} role needs: scheduled jobs and the Spring 7 resilience annotations.
 *
 * <p>{@code proxyTargetClass} matters: with interface proxies the retry interceptor looks for {@code @Retryable} on
 * the interface method, never finds it on the implementation, and silently retries nothing.
 */
@Configuration(proxyBeanMethods = false)
@Profile("worker")
@EnableScheduling
@EnableResilientMethods(proxyTargetClass = true)
class WorkerConfig {

	@Bean
	RetryPolicy retryPolicy() {
		// Jitter does not need a secure source, but SecureRandom is thread-safe, which a shared generator must be.
		return new RetryPolicy(new SecureRandom());
	}

}
