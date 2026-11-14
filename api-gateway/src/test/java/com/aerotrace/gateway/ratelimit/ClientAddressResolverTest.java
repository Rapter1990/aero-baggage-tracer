package com.aerotrace.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The client address behind a reverse proxy. {@code X-Forwarded-For} is only believed when the
 * direct peer is a configured trusted proxy, and then only the part that proxy added.
 */
class ClientAddressResolverTest {

  private static MockHttpServletRequest request(String peer, String... forwardedFor) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
    request.setRemoteAddr(peer);
    for (String line : forwardedFor) {
      request.addHeader("X-Forwarded-For", line);
    }
    return request;
  }

  private final ClientAddressResolver trustingTenNet =
      new ClientAddressResolver(List.of("10.0.0.0/8", "192.168.1.5"));

  @Test
  void withNoTrustedProxyTheHeaderIsIgnoredAndThePeerIsTheClient() {
    ClientAddressResolver none = new ClientAddressResolver(List.of());

    assertThat(none.resolve(request("203.0.113.9", "6.6.6.6"))).isEqualTo("203.0.113.9");
  }

  @Test
  void aForgedHeaderFromAnUntrustedPeerChangesNothing() {
    assertThat(trustingTenNet.resolve(request("203.0.113.9", "6.6.6.6, 7.7.7.7")))
        .isEqualTo("203.0.113.9");
  }

  @Test
  void aTrustedProxyVouchesForTheAddressItSaw() {
    assertThat(trustingTenNet.resolve(request("10.1.2.3", "203.0.113.7"))).isEqualTo("203.0.113.7");
  }

  @Test
  void whatTheClientPutInFrontOfTheProxysEntryIsIgnored() {
    // The client sent "6.6.6.6"; the proxy appended the address it really saw.
    assertThat(trustingTenNet.resolve(request("10.1.2.3", "6.6.6.6, 203.0.113.7")))
        .isEqualTo("203.0.113.7");
  }

  @Test
  void aChainOfTrustedProxiesIsWalkedFromTheRight() {
    assertThat(trustingTenNet.resolve(request("10.3.3.3", "203.0.113.7, 10.1.1.1, 10.2.2.2")))
        .isEqualTo("203.0.113.7");
  }

  @Test
  void whenEveryHopIsTrustedTheLeftmostIsTheClient() {
    assertThat(trustingTenNet.resolve(request("10.3.3.3", "10.9.9.9, 10.1.1.1")))
        .isEqualTo("10.9.9.9");
  }

  @Test
  void aSingleTrustedAddressWorksLikeANetwork() {
    assertThat(trustingTenNet.resolve(request("192.168.1.5", "203.0.113.7")))
        .isEqualTo("203.0.113.7");
    assertThat(trustingTenNet.resolve(request("192.168.1.6", "203.0.113.7")))
        .isEqualTo("192.168.1.6");
  }

  @Test
  void headerLinesAreJoinedInOrder() {
    assertThat(trustingTenNet.resolve(request("10.1.2.3", "6.6.6.6", "203.0.113.7")))
        .isEqualTo("203.0.113.7");
  }

  @Test
  void aMissingOrBlankHeaderMeansThePeer() {
    assertThat(trustingTenNet.resolve(request("10.1.2.3"))).isEqualTo("10.1.2.3");
    assertThat(trustingTenNet.resolve(request("10.1.2.3", "  "))).isEqualTo("10.1.2.3");
  }

  @Test
  void aMalformedEntryIsNeverResolvedAndFallsBackToThePeer() {
    assertThat(trustingTenNet.resolve(request("10.1.2.3", "evil.example.com")))
        .isEqualTo("10.1.2.3");
    assertThat(trustingTenNet.resolve(request("10.1.2.3", "203.0.113.7, not-an-ip")))
        .isEqualTo("10.1.2.3");
    assertThat(trustingTenNet.resolve(request("10.1.2.3", "999.1.1.1"))).isEqualTo("10.1.2.3");
    assertThat(trustingTenNet.resolve(request("10.1.2.3", "1.2.3.4:8080"))).isEqualTo("10.1.2.3");
  }

  @Test
  void ipv6AddressesAreUnderstoodAndWrittenInOneForm() {
    ClientAddressResolver v6 = new ClientAddressResolver(List.of("::1", "fd00::/8"));

    String short1 = v6.resolve(request("::1", "2001:DB8::1"));
    String long1 = v6.resolve(request("0:0:0:0:0:0:0:1", "2001:db8:0:0:0:0:0:1"));

    assertThat(short1).isEqualTo(long1);
    assertThat(short1).contains("2001:db8");
  }

  @Test
  void anUnusableTrustedProxySettingIsRefusedAtStartUp() {
    assertThatThrownBy(() -> new ClientAddressResolver(List.of("not-a-network")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not-a-network");
  }

  @Test
  void blankEntriesInTheSettingAreSkipped() {
    ClientAddressResolver resolver = new ClientAddressResolver(List.of(" ", "10.0.0.0/8", ""));

    assertThat(resolver.resolve(request("10.1.2.3", "203.0.113.7"))).isEqualTo("203.0.113.7");
  }
}
