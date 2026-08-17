package com.embl.ebi.funding.domain;

import java.util.List;

/**
 * Internal representation of a publication, deliberately decoupled from Europe PMC's raw JSON
 * shape so that upstream schema quirks (missing fields, inconsistent naming) do not leak into our
 * response contract or matching logic.
 *
 * <p>Fields that Europe PMC does not guarantee to be present for every publication (abstract,
 * DOI, PMCID, citation count, journal, publication date) are nullable here rather than defaulted,
 * so that "missing" is always distinguishable from "empty".
 */
public record Publication(
        PublicationId id,
        String title,
        List<String> authors,
        String journalTitle,
        String publicationDate,
        Integer pubYear,
        String abstractText,
        Integer citedByCount,
        String doi,
        String pmid,
        String pmcid,
        List<FundingReference> reportedFunding
) {
    public Publication {
        authors = authors == null ? List.of() : List.copyOf(authors);
        reportedFunding = reportedFunding == null ? List.of() : List.copyOf(reportedFunding);
    }
}
