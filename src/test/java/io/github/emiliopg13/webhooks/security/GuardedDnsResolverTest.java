package io.github.emiliopg13.webhooks.security;

import static io.github.emiliopg13.webhooks.security.FakeDns.ip;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class GuardedDnsResolverTest {

	private static final InetAddress PUBLIC = ip(93, 184, 216, 34);

	private static final InetAddress LOOPBACK = ip(127, 0, 0, 1);

	private static final InetAddress METADATA = ip(169, 254, 169, 254);

	@Test
	void passesPublicAddressesThrough() throws Exception {
		GuardedDnsResolver guard = new GuardedDnsResolver(FakeDns.of(host -> new InetAddress[] { PUBLIC }));

		assertThat(guard.resolve("example.com")).containsExactly(PUBLIC);
	}

	@Test
	void refusesAHostThatResolvesToAPrivateAddress() {
		GuardedDnsResolver guard = new GuardedDnsResolver(FakeDns.of(host -> new InetAddress[] { LOOPBACK }));

		assertThatThrownBy(() -> guard.resolve("internal.example.com")).isInstanceOf(BlockedDestinationException.class)
			.hasMessageContaining("internal.example.com");
	}

	@Test
	void oneBlockedAddressAmongPublicOnesRefusesTheWholeName() {
		GuardedDnsResolver guard = new GuardedDnsResolver(FakeDns.of(host -> new InetAddress[] { PUBLIC, METADATA }));

		assertThatThrownBy(() -> guard.resolve("mixed.example.com")).isInstanceOf(BlockedDestinationException.class);
	}

	@Test
	void refusesLocalhostWithoutAskingTheResolver() {
		AtomicInteger lookups = new AtomicInteger();
		GuardedDnsResolver guard = new GuardedDnsResolver(FakeDns.of(host -> {
			lookups.incrementAndGet();
			return new InetAddress[] { PUBLIC };
		}));

		assertThatThrownBy(() -> guard.resolve("LocalHost.")).isInstanceOf(BlockedDestinationException.class);
		assertThat(lookups).hasValue(0);
	}

	/**
	 * DNS rebinding: the name points to a public address when the endpoint is registered and to loopback when the
	 * delivery connects. The guard judges every lookup again, so the second one is refused.
	 */
	@Test
	void aNameThatRebindsToLoopbackIsRefusedAtTheMomentOfConnecting() throws Exception {
		AtomicInteger lookups = new AtomicInteger();
		GuardedDnsResolver guard = new GuardedDnsResolver(
				FakeDns.of(host -> new InetAddress[] { lookups.getAndIncrement() == 0 ? PUBLIC : LOOPBACK }));

		assertThat(guard.resolve("rebind.example.com")).containsExactly(PUBLIC);
		assertThatThrownBy(() -> guard.resolve("rebind.example.com")).isInstanceOf(BlockedDestinationException.class);
	}

	@Test
	void anUnknownHostStaysAnOrdinaryLookupFailure() {
		GuardedDnsResolver guard = new GuardedDnsResolver(FakeDns.of(host -> {
			throw new UnknownHostException(host);
		}));

		assertThatThrownBy(() -> guard.resolve("nope.example.com")).isInstanceOf(UnknownHostException.class)
			.isNotInstanceOf(BlockedDestinationException.class);
	}

}
