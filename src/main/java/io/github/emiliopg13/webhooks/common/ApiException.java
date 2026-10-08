package io.github.emiliopg13.webhooks.common;

import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * An error the caller can fix, rendered as an RFC 9457 problem document by {@link GlobalExceptionHandler}.
 * The message is shown to API clients, so it must never contain internal details.
 */
public final class ApiException extends RuntimeException {

	public record FieldViolation(String field, String message) {
	}

	private final HttpStatus status;

	private final List<FieldViolation> errors;

	private ApiException(HttpStatus status, String message, List<FieldViolation> errors) {
		super(message);
		this.status = status;
		this.errors = errors;
	}

	public static ApiException notFound(String what) {
		return new ApiException(HttpStatus.NOT_FOUND, what + " not found.", List.of());
	}

	public static ApiException badRequest(String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, message, List.of());
	}

	public static ApiException invalid(List<FieldViolation> errors) {
		return new ApiException(HttpStatus.BAD_REQUEST, "Validation failed.", List.copyOf(errors));
	}

	public static ApiException invalidField(String field, String message) {
		return invalid(List.of(new FieldViolation(field, message)));
	}

	public static ApiException unprocessable(String message) {
		return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, message, List.of());
	}

	public static ApiException payloadTooLarge(String message) {
		return new ApiException(HttpStatus.CONTENT_TOO_LARGE, message, List.of());
	}

	public HttpStatus status() {
		return status;
	}

	public List<FieldViolation> errors() {
		return errors;
	}

}
