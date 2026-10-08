package io.github.emiliopg13.webhooks.common;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Cursor pagination over time-ordered UUID ids. The cursor is the id of the last row of the previous page;
 * repositories fetch {@code limit + 1} rows so {@link #page} can tell whether another page exists.
 */
public final class Paging {

	public static final int DEFAULT_LIMIT = 50;

	public static final int MAX_LIMIT = 100;

	private Paging() {
	}

	public static int limit(Integer requested) {
		if (requested == null) {
			return DEFAULT_LIMIT;
		}
		if (requested < 1 || requested > MAX_LIMIT) {
			throw ApiException.invalidField("limit", "must be between 1 and " + MAX_LIMIT);
		}
		return requested;
	}

	/** Returns null when no cursor was given. */
	public static UUID cursor(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return UUID.fromString(raw);
		}
		catch (IllegalArgumentException ex) {
			throw ApiException.invalidField("cursor", "is not a valid cursor");
		}
	}

	public static <T> PageResponse<T> page(List<T> fetched, int limit, Function<T, UUID> idOf) {
		if (fetched.size() <= limit) {
			return new PageResponse<>(fetched, null);
		}
		List<T> data = fetched.subList(0, limit);
		return new PageResponse<>(data, idOf.apply(data.get(limit - 1)).toString());
	}

}
