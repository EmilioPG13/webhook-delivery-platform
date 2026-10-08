package io.github.emiliopg13.webhooks.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PagingTest {

	@Test
	void limitDefaultsWhenAbsent() {
		assertThat(Paging.limit(null)).isEqualTo(Paging.DEFAULT_LIMIT);
	}

	@ParameterizedTest
	@ValueSource(ints = { 0, -1, 101 })
	void limitOutsideTheAllowedRangeIsRejected(int limit) {
		assertThatThrownBy(() -> Paging.limit(limit)).isInstanceOf(ApiException.class);
	}

	@Test
	void cursorIsNullWhenAbsentOrBlank() {
		assertThat(Paging.cursor(null)).isNull();
		assertThat(Paging.cursor("  ")).isNull();
	}

	@Test
	void cursorMustBeAUuid() {
		assertThatThrownBy(() -> Paging.cursor("not-a-uuid")).isInstanceOf(ApiException.class);
	}

	@Test
	void lastPageHasNoCursor() {
		List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());

		PageResponse<UUID> page = Paging.page(ids, 2, Function.identity());

		assertThat(page.data()).isEqualTo(ids);
		assertThat(page.nextCursor()).isNull();
	}

	@Test
	void extraRowProvesAnotherPageExistsAndIsNotReturned() {
		List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

		PageResponse<UUID> page = Paging.page(ids, 2, Function.identity());

		assertThat(page.data()).containsExactly(ids.get(0), ids.get(1));
		assertThat(page.nextCursor()).isEqualTo(ids.get(1).toString());
	}

}
