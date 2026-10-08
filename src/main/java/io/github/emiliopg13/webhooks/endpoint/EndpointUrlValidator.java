package io.github.emiliopg13.webhooks.endpoint;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import io.github.emiliopg13.webhooks.common.ApiException;
import io.github.emiliopg13.webhooks.security.AddressPolicy;

/**
 * Checks a destination URL when an endpoint is registered, so a mistake gets a clear error at once. Rejections are
 * reported as a 400 on the {@code url} field.
 *
 * <p>This is a convenience, not the defence. It never does a DNS lookup (a name can point somewhere else by the
 * time of delivery, and registration must not depend on DNS being up), so it only judges what the URL says: the
 * name {@code localhost}, and IP addresses written out in it. The guard that cannot be bypassed is
 * {@code GuardedDnsResolver}, which judges the addresses a name resolves to at the moment of connecting.
 */
@Component
class EndpointUrlValidator {

	static final int MAX_LENGTH = 2048;

	private static final String PRIVATE_ADDRESS = "must not point to a private or reserved address";

	private static final Pattern DOTTED_QUAD = Pattern
		.compile("(0|[1-9]\\d{0,2})\\.(0|[1-9]\\d{0,2})\\.(0|[1-9]\\d{0,2})\\.(0|[1-9]\\d{0,2})");

	/** A last label that is all digits, or 0x and hex digits: such a host is an IPv4 address, written some way. */
	private static final Pattern NUMERIC_LABEL = Pattern.compile("\\d+|0[xX][0-9a-fA-F]*");

	private final boolean allowInsecureHttp;

	private final boolean allowPrivateDestinations;

	EndpointUrlValidator(EndpointProperties properties) {
		this.allowInsecureHttp = properties.allowInsecureHttp();
		this.allowPrivateDestinations = properties.allowPrivateDestinations();
	}

	void validate(String url) {
		if (url.length() > MAX_LENGTH) {
			throw reject("must be at most " + MAX_LENGTH + " characters");
		}
		URI uri;
		try {
			uri = new URI(url);
		}
		catch (URISyntaxException ex) {
			throw reject("must be a valid absolute URL");
		}
		String scheme = uri.getScheme();
		boolean schemeAllowed = "https".equalsIgnoreCase(scheme) || (allowInsecureHttp && "http".equalsIgnoreCase(scheme));
		if (!schemeAllowed) {
			throw reject(allowInsecureHttp ? "must use http or https" : "must use https");
		}
		if (uri.getHost() == null || uri.getHost().isBlank()) {
			throw reject("must include a host");
		}
		if (uri.getUserInfo() != null) {
			throw reject("must not contain credentials");
		}
		if (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535)) {
			throw reject("has an invalid port");
		}
		if (!allowPrivateDestinations) {
			rejectPrivateHost(uri.getHost());
		}
	}

	private static void rejectPrivateHost(String host) {
		if (host.startsWith("[")) {
			if (isBlocked(literal(host.substring(1, host.length() - 1)))) {
				throw reject(PRIVATE_ADDRESS);
			}
			return;
		}
		String name = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
		if (AddressPolicy.isBlockedName(name)) {
			throw reject(PRIVATE_ADDRESS);
		}
		var quad = DOTTED_QUAD.matcher(name);
		if (quad.matches()) {
			byte[] bytes = new byte[4];
			for (int i = 0; i < 4; i++) {
				int part = Integer.parseInt(quad.group(i + 1));
				if (part > 255) {
					throw reject("has an invalid IP address");
				}
				bytes[i] = (byte) part;
			}
			if (isBlocked(literal(bytes))) {
				throw reject(PRIVATE_ADDRESS);
			}
			return;
		}
		// 2130706433, 0177.0.0.1, 0x7f.1 and 127.1 are all 127.0.0.1 to some parsers. No real host name ends in a
		// number, so refusing every such name is simpler and safer than trying to interpret each notation.
		if (NUMERIC_LABEL.matcher(name.substring(name.lastIndexOf('.') + 1)).matches()) {
			throw reject("must not use a non-standard IP address notation");
		}
	}

	private static boolean isBlocked(InetAddress address) {
		return AddressPolicy.isBlocked(address);
	}

	/** Parses an IPv6 literal. Text with a colon is never looked up in DNS. */
	private static InetAddress literal(String text) {
		try {
			return InetAddress.getByName(text);
		}
		catch (UnknownHostException ex) {
			throw reject("has an invalid IP address");
		}
	}

	private static InetAddress literal(byte[] bytes) {
		try {
			return InetAddress.getByAddress(bytes);
		}
		catch (UnknownHostException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static ApiException reject(String message) {
		return ApiException.invalidField("url", message);
	}

}
