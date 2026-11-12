package com.aerotrace.identity.token;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Access token lifetime. Key rotation uses it too: a replaced key stays published for the token
 * lifetime plus the clock skew resource servers tolerate, so tokens signed with it keep working.
 */
@ConfigurationProperties("aerotrace.identity.token")
public record TokenProperties(
    @DefaultValue("15m") Duration ttl, @DefaultValue("1m") Duration clockSkew) {}
