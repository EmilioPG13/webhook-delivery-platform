package io.github.emiliopg13.webhooks.tenant;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Turns {@code Authorization: Bearer whk_...} into a {@link TenantPrincipal}. A missing header passes through
 * and is rejected later by the authorization rules; a present but unknown or revoked key is rejected here.
 */
final class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

	private final ApiKeyService apiKeys;

	private final AuthenticationEntryPoint entryPoint;

	ApiKeyAuthenticationFilter(ApiKeyService apiKeys, AuthenticationEntryPoint entryPoint) {
		this.apiKeys = apiKeys;
		this.entryPoint = entryPoint;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String token = BearerToken.from(request);
		if (token == null) {
			chain.doFilter(request, response);
			return;
		}
		Optional<TenantPrincipal> principal = apiKeys.authenticate(token);
		if (principal.isEmpty()) {
			entryPoint.commence(request, response, new BadCredentialsException("Invalid API key"));
			return;
		}
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal.get(), null,
				List.of(new SimpleGrantedAuthority("ROLE_TENANT"))));
		SecurityContextHolder.setContext(context);
		chain.doFilter(request, response);
	}

}
