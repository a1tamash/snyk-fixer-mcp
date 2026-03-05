package com.snykfixer.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@EnableConfigurationProperties({SnykApiProperties.class, GitHubApiProperties.class})
public class AppConfig {

    @Bean
    public WebClient snykWebClient(SnykApiProperties props) {
        return WebClient.builder()
                .baseUrl(props.baseUrl())
                .defaultHeader("Authorization", "token " + props.token())
                .defaultHeader("Content-Type", "application/vnd.api+json")
                .build();
    }
}
