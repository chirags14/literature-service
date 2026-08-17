package com.embl.ebi.funding.web.dto;

import java.util.List;

public record PublicationDto(
        String id,
        String source,
        String pmid,
        String pmcid,
        String doi,
        String title,
        List<String> authors,
        String journal,
        String publicationDate,
        Integer pubYear,
        String abstractText,
        Integer citedByCount,
        List<FundingReferenceDto> fundingReferences
) {
}
