package io.github.emiliopg13.webhooks.delivery;

import java.security.SecureRandom;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.scheduling.annotation.EnableScheduling;

import io.github.emiliopg13.webhooks.endpoint.EndpointProperties;
import io.github.emiliopg13.webhooks.security.GuardedDnsResolver;

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

	private static final Logger log = LoggerFactory.getLogger(WorkerConfig.class);

	/**
	 * The SSRF guard: every address the HTTP client connects to goes through it. The only way to bypass it is the
	 * explicit development property, and that logs a warning so it cannot be left on unnoticed.
	 */
	@Bean
	DnsResolver destinationDnsResolver(EndpointProperties endpoints) {
		if (endpoints.allowPrivateDestinations()) {
			log.warn("webhooks.endpoints.allow-private-destinations is on: the SSRF guard is OFF. "
					+ "Never do this in production.");
			return SystemDefaultDnsResolver.INSTANCE;
		}
		return new GuardedDnsResolver(SystemDefaultDnsResolver.INSTANCE);
	}

	@Bean
	RetryPolicy retryPolicy() {
		// Jitter does not need a secure source, but SecureRandom is thread-safe, which a shared generator must be.
		return new RetryPolicy(new SecureRandom());
	}

}
