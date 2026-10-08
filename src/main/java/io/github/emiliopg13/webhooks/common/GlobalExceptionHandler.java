package io.github.emiliopg13.webhooks.common;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import io.github.emiliopg13.webhooks.common.ApiException.FieldViolation;

/**
 * Every error leaves the API as {@code application/problem+json}. Framework exceptions (bad JSON, wrong method,
 * missing parameters) are already mapped by the parent class; this adds our own exceptions, field-level
 * validation errors, and a catch-all that never leaks a stack trace.
 */
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	ResponseEntity<Object> handleApiException(ApiException ex, WebRequest request) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
		if (!ex.errors().isEmpty()) {
			problem.setProperty("errors", ex.errors());
		}
		return handleExceptionInternal(ex, problem, new HttpHeaders(), ex.status(), request);
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<FieldViolation> errors = ex.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(error -> new FieldViolation(error.getField(), String.valueOf(error.getDefaultMessage())))
			.toList();
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed.");
		problem.setProperty("errors", errors);
		return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
		log.error("Unhandled exception", ex);
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
				"Unexpected error.");
		return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
	}

}
