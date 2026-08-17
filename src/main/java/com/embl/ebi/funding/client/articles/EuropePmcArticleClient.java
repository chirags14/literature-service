package com.embl.ebi.funding.client.articles;

/**
 * Client for the Europe PMC Articles REST API's {@code /search} endpoint.
 *
 * <p>Kept as an interface so that the service layer and tests are never coupled to the live Europe
 * PMC service — a fake or stub can be substituted without touching any other code.
 */
public interface EuropePmcArticleClient {

    /**
     * Searches Europe PMC for the given query, paginating internally (via {@code cursorMark})
     * until either {@code limit} publications have been collected or Europe PMC's results are
     * exhausted, using Europe PMC's default (relevance) ordering throughout.
     *
     * @param query the Europe PMC literature query, passed through close to verbatim
     * @param limit the maximum number of publications to return
     */
    ArticleSearchResult search(String query, int limit);
}
