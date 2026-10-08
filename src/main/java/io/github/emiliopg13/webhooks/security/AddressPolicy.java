package io.github.emiliopg13.webhooks.security;

import java.net.InetAddress;
import java.util.List;
import java.util.Locale;

/**
 * Which destinations a webhook must never be sent to: loopback, private networks, link-local (the cloud metadata
 * service lives at 169.254.169.254), multicast and the other special-purpose ranges of the IANA registries.
 *
 * <p>The check works on resolved addresses, never on the text of a URL, so it does not matter how an address was
 * written (decimal, octal, hex, shortened, IPv4 inside IPv6): whatever the resolver returns is what gets judged.
 * An IPv6 address that embeds an IPv4 one (mapped, NAT64, 6to4) is judged by the IPv4 address inside it.
 */
public final class AddressPolicy {

	private record Cidr4(int network, int prefixLength) {

		boolean contains(int address) {
			int mask = prefixLength == 0 ? 0 : -1 << (32 - prefixLength);
			return (address & mask) == network;
		}

	}

	private static Cidr4 cidr(int a, int b, int c, int d, int prefixLength) {
		return new Cidr4(a << 24 | b << 16 | c << 8 | d, prefixLength);
	}

	private static final List<Cidr4> BLOCKED_IPV4 = List.of(
			cidr(0, 0, 0, 0, 8), // "this network"
			cidr(10, 0, 0, 0, 8), // private
			cidr(100, 64, 0, 0, 10), // carrier-grade NAT
			cidr(127, 0, 0, 0, 8), // loopback
			cidr(169, 254, 0, 0, 16), // link-local, includes the metadata service
			cidr(172, 16, 0, 0, 12), // private
			cidr(192, 0, 0, 0, 24), // IETF protocol assignments
			cidr(192, 0, 2, 0, 24), // documentation
			cidr(192, 168, 0, 0, 16), // private
			cidr(198, 18, 0, 0, 15), // network benchmarking
			cidr(198, 51, 100, 0, 24), // documentation
			cidr(203, 0, 113, 0, 24), // documentation
			cidr(224, 0, 0, 0, 4), // multicast
			cidr(240, 0, 0, 0, 4)); // reserved, includes the broadcast address

	private AddressPolicy() {
	}

	public static boolean isBlocked(InetAddress address) {
		byte[] bytes = address.getAddress();
		return bytes.length == 4 ? isBlockedIpv4(bytes, 0) : isBlockedIpv6(bytes);
	}

	/**
	 * Names that stand for the local machine whatever a resolver says. {@code .localhost} is reserved for
	 * loopback by RFC 6761, so every subdomain of it counts.
	 */
	public static boolean isBlockedName(String host) {
		String name = host.toLowerCase(Locale.ROOT);
		if (name.endsWith(".")) {
			name = name.substring(0, name.length() - 1);
		}
		return name.equals("localhost") || name.endsWith(".localhost");
	}

	private static boolean isBlockedIpv4(byte[] bytes, int offset) {
		int address = (bytes[offset] & 0xff) << 24 | (bytes[offset + 1] & 0xff) << 16
				| (bytes[offset + 2] & 0xff) << 8 | (bytes[offset + 3] & 0xff);
		return BLOCKED_IPV4.stream().anyMatch(range -> range.contains(address));
	}

	private static boolean isBlockedIpv6(byte[] b) {
		if (isZero(b, 0, 10) && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
			return isBlockedIpv4(b, 12); // ::ffff:a.b.c.d, an IPv4 address in IPv6 clothes
		}
		if (isZero(b, 0, 12)) {
			return true; // ::, ::1 and the deprecated IPv4-compatible ::a.b.c.d
		}
		if ((b[0] & 0xff) == 0x00 && (b[1] & 0xff) == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b
				&& isZero(b, 4, 12)) {
			return isBlockedIpv4(b, 12); // 64:ff9b::/96, NAT64: the IPv4 address sits in the last 32 bits
		}
		if ((b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x02) {
			return isBlockedIpv4(b, 2); // 2002::/16, 6to4: the IPv4 address follows the prefix
		}
		if ((b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x01 && b[2] == 0x0d && (b[3] & 0xff) == 0xb8) {
			return true; // 2001:db8::/32, documentation
		}
		int first = b[0] & 0xff;
		int second = b[1] & 0xff;
		return (first & 0xfe) == 0xfc // fc00::/7, unique local
				|| (first == 0xfe && (second & 0xc0) == 0x80) // fe80::/10, link-local
				|| (first == 0xfe && (second & 0xc0) == 0xc0) // fec0::/10, deprecated site-local
				|| first == 0xff; // ff00::/8, multicast
	}

	private static boolean isZero(byte[] bytes, int from, int to) {
		for (int i = from; i < to; i++) {
			if (bytes[i] != 0) {
				return false;
			}
		}
		return true;
	}

}
