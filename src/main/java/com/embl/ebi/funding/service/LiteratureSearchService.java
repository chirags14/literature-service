package com.embl.ebi.funding.service;

import com.embl.ebi.funding.client.articles.ArticleSearchResult;
import com.embl.ebi.funding.client.articles.EuropePmcArticleClient;
import com.embl.ebi.funding.web.dto.PublicationSearchResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Orchestrates a publication search: fetch matching publications from Europe PMC, resolve their
 * funding references against the Grants API, and assemble the public response.
 *
 * <p>Neither Europe PMC dependency failing outright fails the whole request more than necessary:
 * a mid-pagination Articles API failure still returns whatever was already fetched (see
 * {@link ArticleSearchResult#partial()}), and individual failed grant lookups are reported as
 * unresolved rather than aborting resolution for every other publication. Both degraded states are
 * surfaced as top-level {@code warnings} rather than silently absorbed, so a caller can always tell
 * when a response is incomplete instead of mistaking it for a clean result.
 */
@Service
public class LiteratureSearchService {

    private final EuropePmcArticleClient articleClient;
    private final GrantResolutionService grantResolutionService;
    private final ResponseAssembler responseAssembler;

    public LiteratureSearchService(EuropePmcArticleClient articleClient,
                                    GrantResolutionService grantResolutionService,
                                    ResponseAssembler responseAssembler) {
        this.articleClient = articleClient;
        this.grantResolutionService = grantResolutionService;
        this.responseAssembler = responseAssembler;
    }

    public PublicationSearchResponse search(String query, int limit) {
        ArticleSearchResult searchResult = articleClient.search(query, limit);
        GrantResolutionService.GrantResolutionOutcome resolutionOutcome =
                grantResolutionService.resolve(searchResult.publications());

        List<String> warnings = new ArrayList<>();
        if (searchResult.partial()) {
            warnings.add(searchResult.partialReason());
        }
        if (resolutionOutcome.failedLookupCount() > 0) {
            warnings.add(resolutionOutcome.failedLookupCount()
                    + " grant id lookup(s) against the Grants API failed even after retrying and were"
                    + " treated as unresolved (see individual funding reference reasons)");
        }

        return responseAssembler.assemble(query, limit, searchResult.totalHitCount(),
                searchResult.publications(), resolutionOutcome.resolutionsByPublication(), warnings);
    }
}
