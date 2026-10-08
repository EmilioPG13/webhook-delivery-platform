package io.github.emiliopg13.webhooks.security;

import java.net.UnknownHostException;

/**
 * The destination resolves to an address the platform refuses to connect to. It extends
 * {@link UnknownHostException} because that is what a {@code DnsResolver} may throw, and the HTTP client then
 * never opens a socket.
 */
public final class BlockedDestinationException extends UnknownHostException {

	public BlockedDestinationException(String host) {
		super(host + " is not an allowed destination: it is, or resolves to, a private or reserved address");
	}

}
