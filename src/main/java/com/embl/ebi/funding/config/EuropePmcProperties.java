package com.embl.ebi.funding.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the two Europe PMC dependencies. Externalised (rather than hard-coded) so
 * that base URLs/timeouts can be adjusted without a code change, and so tests can point the
 * clients at a local WireMock server.
 */
@ConfigurationProperties(prefix = "europepmc")
public record EuropePmcProperties(
        Articles articles,
        Grist grist,
        int connectTimeoutMs,
        int readTimeoutMs,
        int grantResolutionMaxConcurrency,
        int retryAttempts,
        int retryDelayMs
) {
    public record Articles(String baseUrl) {
    }

    public record Grist(String baseUrl) {
    }
}
