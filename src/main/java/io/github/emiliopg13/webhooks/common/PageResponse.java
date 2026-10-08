package io.github.emiliopg13.webhooks.common;

import java.util.List;

/** One page of a cursor-paginated listing. {@code nextCursor} is null on the last page. */
public record PageResponse<T>(List<T> data, String nextCursor) {
}
