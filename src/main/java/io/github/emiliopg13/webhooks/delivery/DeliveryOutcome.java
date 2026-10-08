package io.github.emiliopg13.webhooks.delivery;

import java.time.Duration;

/** What one HTTP attempt produced. A closed set, so every {@code switch} over it is checked by the compiler. */
sealed interface DeliveryOutcome {

	/** The status the receiver answered with, or null when it never answered. */
	Integer statusCode();

	/** Null on success. */
	ErrorKind errorKind();

	String errorMessage();

	/** First bytes of the receiver's answer. Foreign content: never render it as HTML. */
	String responseSnippet();

	/**
	 * Why the endpoint must be switched off right away, or null when this outcome is an ordinary failure. Such an
	 * outcome also ends the delivery: retrying cannot help.
	 */
	default String fatalReason() {
		return null;
	}

	/** The receiver answered 2xx. */
	record Success(int status, String responseSnippet) implements DeliveryOutcome {

		@Override
		public Integer statusCode() {
			return status;
		}

		@Override
		public ErrorKind errorKind() {
			return null;
		}

		@Override
		public String errorMessage() {
			return null;
		}

	}

	/** The receiver answered, but not with 2xx. {@code retryAfter} is the parsed header, or null. */
	record HttpFailure(int status, String responseSnippet, Duration retryAfter) implements DeliveryOutcome {

		@Override
		public Integer statusCode() {
			return status;
		}

		@Override
		public ErrorKind errorKind() {
			return ErrorKind.HTTP_STATUS;
		}

		@Override
		public String errorMessage() {
			return "Receiver answered HTTP " + status;
		}

		/** 410 Gone is the receiver saying the endpoint was removed on purpose. */
		@Override
		public String fatalReason() {
			return status == 410 ? "gone" : null;
		}

	}

	/** No usable answer: the request did not complete. */
	record NetworkFailure(ErrorKind kind, String message) implements DeliveryOutcome {

		@Override
		public Integer statusCode() {
			return null;
		}

		@Override
		public ErrorKind errorKind() {
			return kind;
		}

		@Override
		public String errorMessage() {
			return message;
		}

		@Override
		public String responseSnippet() {
			return null;
		}

		/** The SSRF guard refused the destination: sending there again can only be an attack or a mistake. */
		@Override
		public String fatalReason() {
			return kind == ErrorKind.BLOCKED_DESTINATION ? kind.value() : null;
		}

	}

}
