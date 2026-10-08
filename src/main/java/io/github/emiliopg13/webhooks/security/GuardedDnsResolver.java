package io.github.emiliopg13.webhooks.security;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.apache.hc.client5.http.DnsResolver;

/**
 * The SSRF guard. The HTTP client asks this resolver for the addresses to connect to, and connects to exactly the
 * ones it returns, so there is no gap between "checked" and "used" for DNS rebinding to slip through: a name that
 * pointed to a public address when the endpoint was registered and to {@code 127.0.0.1} at delivery time is
 * refused at delivery time.
 *
 * <p>If <em>any</em> returned address is blocked the whole name is refused, not just that address. Otherwise an
 * attacker could publish one public and one internal record and rely on the client picking the internal one.
 */
public final class GuardedDnsResolver implements DnsResolver {

	private final DnsResolver delegate;

	public GuardedDnsResolver(DnsResolver delegate) {
		this.delegate = delegate;
	}

	@Override
	public InetAddress[] resolve(String host) throws UnknownHostException {
		if (AddressPolicy.isBlockedName(host)) {
			throw new BlockedDestinationException(host);
		}
		InetAddress[] addresses = delegate.resolve(host);
		for (InetAddress address : addresses) {
			if (AddressPolicy.isBlocked(address)) {
				throw new BlockedDestinationException(host);
			}
		}
		return addresses;
	}

	@Override
	public String resolveCanonicalHostname(String host) throws UnknownHostException {
		return delegate.resolveCanonicalHostname(host);
	}

}
