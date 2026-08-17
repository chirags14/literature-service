package com.embl.ebi.funding.client.articles.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Raw shape of one publication under {@code resultType=core}. Every field except {@code id} and
 * {@code source} is nullable in real responses (confirmed live: abstract, grantsList, journalInfo
 * and structured author lists are all absent from some real publications), so this class must
 * never assume non-null.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ArticleResultDto(
        String id,
        String source,
        String pmid,
        String pmcid,
        String doi,
        String title,
        String authorString,
        AuthorListDto authorList,
        JournalInfoDto journalInfo,
        Integer pubYear,
        String firstPublicationDate,
        String abstractText,
        Integer citedByCount,
        GrantsListDto grantsList
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AuthorListDto(java.util.List<AuthorDto> author) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AuthorDto(String fullName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JournalInfoDto(String dateOfPublication, Integer yearOfPublication, JournalDto journal) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JournalDto(String title) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GrantsListDto(java.util.List<GrantEntryDto> grant) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GrantEntryDto(String grantId, String agency) {
    }
}
