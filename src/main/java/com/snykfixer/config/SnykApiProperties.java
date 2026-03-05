package com.snykfixer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "snyk.api")
public record SnykApiProperties(
        String baseUrl,
        String token,
        String orgId,
        String restVersion
) {}
