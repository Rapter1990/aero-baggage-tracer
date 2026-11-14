package com.aerotrace.gateway.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Finds the address of the real client behind a reverse proxy, so the rate limit is not shared by
 * everyone who comes in through the same proxy and cannot be dodged by sending a forged header.
 *
 * <p>{@code X-Forwarded-For} is believed only when the direct peer is one of the configured trusted
 * proxies. The header is then read from the right, skipping trusted proxies: the first address that
 * is not trusted is the one the nearest trusted proxy saw. Anything a client put in front of that
 * is ignored. With no trusted proxy configured (the default) the header is never read.
 *
 * <p>Header entries are only ever parsed as IP literals, never looked up in DNS, and an entry that
 * is not an IP address makes the whole header untrusted (the peer address is used).
 */
public class ClientAddressResolver {

  private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})(\\.\\d{1,3}){3}");
  private static final Pattern IPV6_CHARS = Pattern.compile("[0-9a-fA-F:.]{2,45}");

  private record Network(byte[] address, int prefixBits) {

    boolean contains(byte[] candidate) {
      if (candidate.length != address.length) {
        return false;
      }
      int fullBytes = prefixBits / 8;
      for (int i = 0; i < fullBytes; i++) {
        if (candidate[i] != address[i]) {
          return false;
        }
      }
      int rest = prefixBits % 8;
      if (rest == 0) {
        return true;
      }
      int mask = 0xFF << (8 - rest) & 0xFF;
      return (candidate[fullBytes] & mask) == (address[fullBytes] & mask);
    }
  }

  private final List<Network> trusted;

  /**
   * @param trustedProxies addresses or networks in CIDR form (for example {@code 10.0.0.0/8});
   *     blank entries are skipped
   * @throws IllegalArgumentException for an entry that is not an IP address or network
   */
  public ClientAddressResolver(List<String> trustedProxies) {
    this.trusted =
        (trustedProxies == null ? List.<String>of() : trustedProxies)
            .stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .map(this::network)
                .toList();
  }

  /** The client address: the peer, or the address a trusted proxy reports for the client. */
  public String resolve(HttpServletRequest request) {
    String peer = request.getRemoteAddr();
    if (peer == null) {
      return "unknown";
    }
    if (trusted.isEmpty() || !isTrusted(parse(peer))) {
      return peer;
    }
    String forwarded =
        String.join(",", Collections.list(request.getHeaders("X-Forwarded-For"))).trim();
    if (forwarded.isEmpty()) {
      return peer;
    }
    String[] hops = forwarded.split(",", -1);
    String candidate = peer;
    for (int i = hops.length - 1; i >= 0; i--) {
      InetAddress address = parse(hops[i].trim());
      if (address == null) {
        return peer;
      }
      candidate = address.getHostAddress();
      if (!isTrusted(address)) {
        return candidate;
      }
    }
    return candidate;
  }

  private boolean isTrusted(InetAddress address) {
    if (address == null) {
      return false;
    }
    byte[] bytes = address.getAddress();
    return trusted.stream().anyMatch(network -> network.contains(bytes));
  }

  private Network network(String entry) {
    String text = entry;
    int slash = entry.indexOf('/');
    Integer prefix = null;
    if (slash >= 0) {
      text = entry.substring(0, slash);
      try {
        prefix = Integer.parseInt(entry.substring(slash + 1));
      } catch (NumberFormatException e) {
        throw invalid(entry);
      }
    }
    InetAddress address = parse(text);
    if (address == null) {
      throw invalid(entry);
    }
    int bits = address.getAddress().length * 8;
    if (prefix != null && (prefix < 0 || prefix > bits)) {
      throw invalid(entry);
    }
    return new Network(address.getAddress(), prefix == null ? bits : prefix);
  }

  private static IllegalArgumentException invalid(String entry) {
    return new IllegalArgumentException(
        "Not an IP address or network (use 10.0.0.1 or 10.0.0.0/8): " + entry);
  }

  /** An IP literal, or null. Never asks DNS: only text shaped like an address reaches the JDK. */
  private static InetAddress parse(String text) {
    if (text == null || text.isEmpty() || !looksLikeAddress(text)) {
      return null;
    }
    try {
      return InetAddress.getByName(text);
    } catch (UnknownHostException e) {
      return null;
    }
  }

  private static boolean looksLikeAddress(String text) {
    if (text.indexOf(':') >= 0) {
      return IPV6_CHARS.matcher(text).matches();
    }
    if (!IPV4.matcher(text).matches()) {
      return false;
    }
    for (String octet : text.split("\\.")) {
      if (Integer.parseInt(octet) > 255) {
        return false;
      }
    }
    return true;
  }
}
