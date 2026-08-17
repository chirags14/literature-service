package com.embl.ebi.funding.web.dto;

import java.util.List;

/**
 * A distinct resolved grant, deduplicated across all publications in the response, together with
 * the ids of every publication that referenced it. Preserving this cross-publication view means a
 * caller can trace any resolved grant back to whichever publications reported it, without having
 * to join the per-publication funding references themselves.
 */
public record ResolvedGrantSummaryDto(
        String grantId,
        String funderName,
        String fundRefId,
        String title,
        List<String> publicationIds
) {
}
