package io.github.emiliopg13.webhooks.security;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.apache.hc.client5.http.DnsResolver;

/** A resolver whose answers a test scripts, so DNS tricks can be played without a network. */
public final class FakeDns {

	@FunctionalInterface
	public interface Lookup {

		InetAddress[] resolve(String host) throws UnknownHostException;

	}

	private FakeDns() {
	}

	public static DnsResolver of(Lookup lookup) {
		return new DnsResolver() {
			@Override
			public InetAddress[] resolve(String host) throws UnknownHostException {
				return lookup.resolve(host);
			}

			@Override
			public String resolveCanonicalHostname(String host) {
				return host;
			}
		};
	}

	public static InetAddress ip(int a, int b, int c, int d) {
		try {
			return InetAddress.getByAddress(new byte[] { (byte) a, (byte) b, (byte) c, (byte) d });
		}
		catch (UnknownHostException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
