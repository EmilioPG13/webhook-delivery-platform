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

	}

}
