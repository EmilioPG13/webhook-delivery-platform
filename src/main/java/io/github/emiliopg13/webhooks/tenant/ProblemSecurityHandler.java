package io.github.emiliopg13.webhooks.tenant;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Security filters run before Spring MVC, so {@code @RestControllerAdvice} never sees their errors. This writes
 * the same RFC 9457 shape by hand to keep 401 and 403 consistent with the rest of the API.
 */
@Component
class ProblemSecurityHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

	private final JsonMapper jsonMapper;

	ProblemSecurityHandler(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException {
		response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
		write(response, HttpStatus.UNAUTHORIZED, "Valid credentials are required.");
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException accessDeniedException) throws IOException {
		write(response, HttpStatus.FORBIDDEN, "Access denied.");
	}

	private void write(HttpServletResponse response, HttpStatus status, String detail) throws IOException {
		Map<String, Object> problem = new LinkedHashMap<>();
		problem.put("type", "about:blank");
		problem.put("title", status.getReasonPhrase());
		problem.put("status", status.value());
		problem.put("detail", detail);
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.getOutputStream().write(jsonMapper.writeValueAsBytes(problem));
	}

}
