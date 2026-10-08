package io.github.emiliopg13.webhooks.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Every blocked range, at both ends and just outside, so an off-by-one in a prefix length shows up. */
class AddressPolicyTest {

	@ParameterizedTest
	@ValueSource(strings = {
			// 0.0.0.0/8 and the broadcast address
			"0.0.0.0", "0.255.255.255", "255.255.255.255",
			// 10.0.0.0/8
			"10.0.0.0", "10.255.255.255",
			// 100.64.0.0/10
			"100.64.0.0", "100.127.255.255",
			// 127.0.0.0/8
			"127.0.0.1", "127.255.255.255",
			// 169.254.0.0/16, with the cloud metadata service
			"169.254.0.0", "169.254.169.254", "169.254.255.255",
			// 172.16.0.0/12
			"172.16.0.0", "172.31.255.255",
			// 192.0.0.0/24, 192.0.2.0/24, 192.168.0.0/16
			"192.0.0.0", "192.0.0.255", "192.0.2.1", "192.168.0.0", "192.168.255.255",
			// 198.18.0.0/15, 198.51.100.0/24, 203.0.113.0/24
			"198.18.0.0", "198.19.255.255", "198.51.100.7", "203.0.113.7",
			// 224.0.0.0/4 and 240.0.0.0/4
			"224.0.0.1", "239.255.255.255", "240.0.0.0", "254.255.255.255",
			// IPv6: unspecified, loopback, unique local, link-local, site-local, multicast, documentation
			"::", "::1", "fc00::1", "fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "fe80::1", "febf::1", "fec0::1", "ff02::1",
			"2001:db8::1",
			// IPv6 that carries an IPv4 address inside: mapped, IPv4-compatible, NAT64 and 6to4
			"::ffff:127.0.0.1", "::ffff:10.1.2.3", "::ffff:169.254.169.254", "::7f00:1", "64:ff9b::7f00:1",
			"64:ff9b::a9fe:a9fe", "2002:7f00:1::", "2002:a9fe:a9fe::1" })
	void blocks(String address) throws UnknownHostException {
		assertThat(AddressPolicy.isBlocked(InetAddress.getByName(address))).as(address).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			// just outside each IPv4 range
			"1.0.0.1", "9.255.255.255", "11.0.0.0", "100.63.255.255", "100.128.0.0", "126.255.255.255", "128.0.0.1",
			"169.253.255.255", "169.255.0.0", "172.15.255.255", "172.32.0.0", "192.0.1.1", "192.167.255.255",
			"192.169.0.0", "198.17.255.255", "198.20.0.0", "223.255.255.255",
			// ordinary public addresses
			"8.8.8.8", "1.1.1.1", "93.184.216.34",
			// public IPv6, and IPv6 whose embedded IPv4 address is public
			"2606:4700:4700::1111", "2001:4860:4860::8888", "::ffff:8.8.8.8", "64:ff9b::808:808", "2002:808:808::1" })
	void allows(String address) throws UnknownHostException {
		assertThat(AddressPolicy.isBlocked(InetAddress.getByName(address))).as(address).isFalse();
	}

	@ParameterizedTest
	@CsvSource({ "localhost,true", "LOCALHOST,true", "localhost.,true", "api.localhost,true", "a.b.LocalHost.,true",
			"notlocalhost,false", "localhost.example.com,false", "example.com,false" })
	void blocksTheLocalhostNameInAnyCaseAndDepth(String host, boolean blocked) {
		assertThat(AddressPolicy.isBlockedName(host)).as(host).isEqualTo(blocked);
	}

}
