package com.embl.ebi.funding.web.dto;

import java.util.List;

public record SummaryDto(
        long totalPublicationsMatchedByEuropePmc,
        int publicationsReturned,
        // These three count funding *references*, not publications: one publication reporting three
        // grants contributes three, and they sum to totalFundingReferencesReported. Publication-level
        // counts live in publicationsReturned and in each topReportedFunders entry.
        int totalFundingReferencesReported,
        int resolvedReferenceCount,
        int unresolvedReferenceCount,
        int ambiguousReferenceCount,
        /** The most frequently reported funders among the returned publications, ranked by
         *  publication count and capped to a top-N slice — see {@link ResponseAssembler}. */
        List<FunderCountDto> topReportedFunders,
        List<ResolvedGrantSummaryDto> resolvedGrants
) {
}
