package com.aerotrace.identity.token;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Access token settings.
 *
 * @param issuer the {@code iss} claim; resource servers are configured with the same value. No
 *     default: the service refuses to start without it rather than guess.
 * @param audience the {@code aud} claim resource servers require
 * @param ttl access token lifetime. Key rotation uses it too: a replaced key stays published for
 *     the lifetime plus the clock skew, so tokens signed with it keep working.
 * @param clockSkew the clock difference resource servers tolerate
 */
@ConfigurationProperties("aerotrace.identity.token")
public record TokenProperties(
    String issuer,
    @DefaultValue("aerotrace-api") String audience,
    @DefaultValue("15m") Duration ttl,
    @DefaultValue("1m") Duration clockSkew) {}
