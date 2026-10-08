package io.github.emiliopg13.webhooks.tenant;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.List;

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

/** Authenticates the operator token on {@code /admin/**}. This is an operator console, not user login. */
final class AdminTokenAuthenticationFilter extends OncePerRequestFilter {

	private final AdminProperties properties;

	private final AuthenticationEntryPoint entryPoint;

	AdminTokenAuthenticationFilter(AdminProperties properties, AuthenticationEntryPoint entryPoint) {
		this.properties = properties;
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
		if (!matches(token)) {
			entryPoint.commence(request, response, new BadCredentialsException("Invalid admin token"));
			return;
		}
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("admin", null,
				List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
		SecurityContextHolder.setContext(context);
		chain.doFilter(request, response);
	}

	private boolean matches(String presented) {
		String expected = properties.token();
		if (expected == null || expected.isBlank()) {
			return false;
		}
		// Hash both sides first so the comparison takes the same time whatever the length of the input.
		return MessageDigest.isEqual(ApiKeyService.sha256(presented), ApiKeyService.sha256(expected));
	}

}
