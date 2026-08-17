package com.embl.ebi.funding.client.articles;

import com.embl.ebi.funding.domain.Publication;
import java.util.List;

/**
 * Outcome of a (possibly multi-page) Europe PMC Articles search: the publications retrieved
 * (capped at the caller's requested limit) plus the total number of matches Europe PMC reports,
 * which may be far larger than what was actually fetched.
 *
 * <p>{@code partial} is {@code true} when Europe PMC became unavailable partway through
 * pagination: rather than discarding publications already fetched from earlier pages, they are
 * returned together with {@code partialReason} explaining why fewer than the requested number of
 * publications may be present.
 */
public record ArticleSearchResult(List<Publication> publications, long totalHitCount, boolean partial,
                                   String partialReason) {
}
