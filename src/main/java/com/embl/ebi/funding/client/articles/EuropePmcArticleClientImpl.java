package com.embl.ebi.funding.client.articles;

import com.embl.ebi.funding.client.RetrySupport;
import com.embl.ebi.funding.client.articles.dto.ArticleSearchResponseDto;
import com.embl.ebi.funding.config.EuropePmcProperties;
import com.embl.ebi.funding.domain.Publication;
import com.embl.ebi.funding.exception.EuropePmcQueryException;
import com.embl.ebi.funding.exception.EuropePmcUpstreamException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Live implementation of {@link EuropePmcArticleClient} against
 * {@code GET /europepmc/webservices/rest/search}.
 *
 * <p>Uses {@code resultType=core} (required to obtain {@code grantsList} and {@code abstractText}
 * — verified absent from {@code lite}) and cursorMark-based pagination, stopping when either the
 * requested limit is reached or the response omits {@code nextCursorMark} (the verified
 * end-of-pagination signal; Europe PMC does not repeat the last cursor value).
 *
 * <p>Europe PMC signals malformed-query errors with HTTP 200 and an embedded
 * {@code {"errCode":...}} body rather than a non-2xx status (verified live), so every response
 * body is inspected for that shape before being parsed as a successful result.
 *
 * <p><b>Fault tolerance:</b> each page fetch gets a small bounded retry (see {@link RetrySupport})
 * for transient transport failures. If the <em>first</em> page fails after retries there is
 * nothing to return, so the failure propagates as a {@link EuropePmcUpstreamException} (mapped to
 * {@code 502} by the controller). If a <em>later</em> page fails after retries, the publications
 * already collected from earlier pages are still returned, marked {@code partial=true} with a
 * reason — Europe PMC going down mid-pagination should degrade to "fewer results than requested",
 * not "no results at all".
 */
@Component
public class EuropePmcArticleClientImpl implements EuropePmcArticleClient {

    private static final Logger log = LoggerFactory.getLogger(EuropePmcArticleClientImpl.class);
    private static final int MAX_PAGE_SIZE = 1000;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final int retryAttempts;
    private final long retryDelayMs;

    public EuropePmcArticleClientImpl(@Qualifier("articlesRestClient") RestClient restClient,
                                       ObjectMapper objectMapper,
                                       EuropePmcProperties properties) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.retryAttempts = Math.max(1, properties.retryAttempts());
        this.retryDelayMs = Math.max(0, properties.retryDelayMs());
    }

    @Override
    public ArticleSearchResult search(String query, int limit) {
        List<Publication> publications = new ArrayList<>();
        String cursorMark = "*";
        long hitCount = 0;
        boolean firstPage = true;

        while (publications.size() < limit) {
            int pageSize = Math.min(MAX_PAGE_SIZE, limit - publications.size());
            ArticleSearchResponseDto page;
            try {
                page = fetchPageWithRetry(query, cursorMark, pageSize);
            } catch (EuropePmcUpstreamException e) {
                if (firstPage) {
                    log.warn("Europe PMC Articles API unavailable on first page fetch for query '{}': {}",
                            query, e.getMessage());
                    throw e;
                }
                log.warn("Europe PMC Articles API became unavailable mid-pagination after {} results for query '{}': {}",
                        publications.size(), query, e.getMessage());
                return new ArticleSearchResult(publications, hitCount, true,
                        "Europe PMC Articles API became unavailable after " + publications.size()
                                + " of the requested " + limit + " publications were fetched: " + e.getMessage());
            }

            if (firstPage) {
                hitCount = page.hitCount();
                firstPage = false;
            }

            for (var result : page.resultList().result()) {
                publications.add(com.embl.ebi.funding.client.articles.ArticleMapper.toDomain(result));
                if (publications.size() >= limit) {
                    break;
                }
            }

            if (page.resultList().result().isEmpty() || page.nextCursorMark() == null) {
                break;
            }
            cursorMark = page.nextCursorMark();
        }

        return new ArticleSearchResult(publications, hitCount, false, null);
    }

    private ArticleSearchResponseDto fetchPageWithRetry(String query, String cursorMark, int pageSize) {
        return RetrySupport.withRetry(retryAttempts, retryDelayMs,
                () -> fetchPage(query, cursorMark, pageSize),
                (attempt, e) -> log.warn("Europe PMC Articles API transient failure (attempt {}), retrying: {}",
                        attempt, e.getMessage()));
    }

    private ArticleSearchResponseDto fetchPage(String query, String cursorMark, int pageSize) {
        String rawBody;
        try {
            rawBody = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/search")
                            .queryParam("query", query)
                            .queryParam("format", "json")
                            .queryParam("resultType", "core")
                            .queryParam("cursorMark", cursorMark)
                            .queryParam("pageSize", pageSize)
                            .build())
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            throw new EuropePmcUpstreamException("Failed to reach Europe PMC Articles API: " + e.getMessage(), e);
        }

        JsonNode tree;
        try {
            tree = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            throw new EuropePmcUpstreamException("Europe PMC Articles API returned an unparseable response", e);
        }

        if (tree.has("errCode")) {
            String errMsg = tree.hasNonNull("errMsg")
                    ? tree.get("errMsg").asString()
                    : "Europe PMC rejected the query";
            throw new EuropePmcQueryException(errMsg);
        }

        try {
            return objectMapper.treeToValue(tree, ArticleSearchResponseDto.class);
        } catch (Exception e) {
            throw new EuropePmcUpstreamException("Europe PMC Articles API returned an unexpected response shape", e);
        }
    }
}
