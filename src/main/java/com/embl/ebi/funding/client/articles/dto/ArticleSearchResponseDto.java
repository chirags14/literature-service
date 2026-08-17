package com.embl.ebi.funding.client.articles.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Raw shape of a Europe PMC Articles {@code /search} JSON response, as verified live against
 * {@code https://www.ebi.ac.uk/europepmc/webservices/rest/search}.
 *
 * <p>{@code nextCursorMark} is present while more pages remain, and is entirely absent from the
 * response once the last page has been consumed (verified: it is not simply repeated, as classic
 * Solr cursorMark semantics would suggest).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ArticleSearchResponseDto(
        long hitCount,
        String nextCursorMark,
        ResultListDto resultList
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResultListDto(List<ArticleResultDto> result) {
        public ResultListDto {
            result = result == null ? List.of() : result;
        }
    }
}
