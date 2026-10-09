package io.github.emiliopg13.webhooks.tenant;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Two stateless chains. {@code /admin/**} and the metrics scrape path accept only the operator token (Prometheus
 * sends it as a bearer token); everything else needs a tenant API key except the few public paths. The filters are created here rather than declared as beans, because a
 * {@code Filter} bean would also be registered with the servlet container and run twice.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

	/**
	 * Nobody logs in with a username: tenants use API keys and the operator uses a token. An empty user store
	 * stops Spring Boot from creating a default user with a random password that it prints to the log.
	 */
	@Bean
	UserDetailsService noUsers() {
		return new InMemoryUserDetailsManager();
	}

	@Bean
	@Order(1)
	SecurityFilterChain adminSecurity(HttpSecurity http, AdminProperties admin, ProblemSecurityHandler problems)
			throws Exception {
		http.securityMatcher("/admin/**", "/actuator/prometheus")
			.csrf(csrf -> csrf.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(requests -> requests.anyRequest().hasRole("ADMIN"))
			.exceptionHandling(handling -> handling.authenticationEntryPoint(problems).accessDeniedHandler(problems))
			.addFilterBefore(new AdminTokenAuthenticationFilter(admin, problems), AuthorizationFilter.class);
		return http.build();
	}

	@Bean
	@Order(2)
	SecurityFilterChain apiSecurity(HttpSecurity http, ApiKeyService apiKeys, ProblemSecurityHandler problems)
			throws Exception {
		http.csrf(csrf -> csrf.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(requests -> requests
				.requestMatchers("/v1/ping", "/actuator/health/**", "/actuator/info", "/v3/api-docs/**",
						"/swagger-ui/**", "/swagger-ui.html", "/error")
				.permitAll()
				.requestMatchers("/v1/**")
				.hasRole("TENANT")
				.anyRequest()
				.denyAll())
			.exceptionHandling(handling -> handling.authenticationEntryPoint(problems).accessDeniedHandler(problems))
			.addFilterBefore(new ApiKeyAuthenticationFilter(apiKeys, problems), AuthorizationFilter.class);
		return http.build();
	}

}
