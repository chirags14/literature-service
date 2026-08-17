package com.embl.ebi.funding.web;

import com.embl.ebi.funding.exception.InvalidRequestException;
import com.embl.ebi.funding.service.LiteratureSearchService;
import com.embl.ebi.funding.web.dto.PublicationSearchResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public HTTP entry point. {@code GET /publications?query=&limit=} is idiomatic REST for a
 * read-only search operation — there is no mutation, no distinct resource to address, and no
 * reason to deviate from the simplest shape that works.
 */
@RestController
public class PublicationController {

    /** Default applied when the caller omits {@code limit}. */
    static final int DEFAULT_LIMIT = 25;

    /**
     * Bounds worst-case per-request work (pagination round-trips and grant lookups). Also keeps us
     * within Europe PMC's documented per-page maximum of 1000 without needing to special-case it.
     */
    static final int MAX_LIMIT = 200;

    private final LiteratureSearchService literatureSearchService;

    public PublicationController(LiteratureSearchService literatureSearchService) {
        this.literatureSearchService = literatureSearchService;
    }

    @GetMapping("/publications")
    public PublicationSearchResponse search(@RequestParam String query,
                                             @RequestParam(required = false) Integer limit) {
        if (query == null || query.isBlank()) {
            throw new InvalidRequestException("'query' must not be blank");
        }
        int resolvedLimit = resolveLimit(limit);
        return literatureSearchService.search(query, resolvedLimit);
    }

    private int resolveLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidRequestException("'limit' must be between 1 and " + MAX_LIMIT);
        }
        return limit;
    }
}
