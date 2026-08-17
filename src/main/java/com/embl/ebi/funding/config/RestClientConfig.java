package com.embl.ebi.funding.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Defines the two outbound {@link RestClient} beans (Europe PMC Articles and Grants/GRIST APIs),
 * each with an explicit connect/read timeout so that a slow upstream call cannot hang a request
 * indefinitely (an explicit operational concern identified during API investigation, since Europe
 * PMC publishes no documented rate limits or SLAs).
 */
@Configuration
@EnableConfigurationProperties(EuropePmcProperties.class)
public class RestClientConfig {

    @Bean
    public ClientHttpRequestFactory europePmcRequestFactory(EuropePmcProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
                // HTTP/1.1 rather than the JDK client's default HTTP/2 upgrade attempt: observed
                // spurious mid-response GOAWAY resets against Europe PMC through some network
                // paths, which HTTP/1.1 does not exhibit.
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(properties.readTimeoutMs()));
        return factory;
    }

    @Bean("articlesRestClient")
    public RestClient articlesRestClient(EuropePmcProperties properties, ClientHttpRequestFactory requestFactory) {
        return RestClient.builder()
                .baseUrl(properties.articles().baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Bean("gristRestClient")
    public RestClient gristRestClient(EuropePmcProperties properties, ClientHttpRequestFactory requestFactory) {
        return RestClient.builder()
                .baseUrl(properties.grist().baseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
