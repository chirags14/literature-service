package com.embl.ebi.funding.web.dto;

/**
 * How many of the returned publications a funder appears on. The unit of counting is the
 * publication, not the grant: a publication reporting three grants from one funder counts once for
 * that funder. References contribute regardless of whether they resolved against the Grants API.
 *
 * <p>{@code funderName} is the Grants API's canonical funder name when at least one of a
 * publication's references to that funder resolved, and the publisher-supplied agency text
 * otherwise — so an entry without a {@code fundRefId} is unverified text rather than a confirmed
 * funder identity. Two genuinely different spellings that never resolved (e.g. {@code "NSFC"} vs
 * {@code "National Natural Science Foundation of China"}) therefore remain distinct entries: no
 * fuzzy grouping is attempted, for the same reason it isn't attempted during grant resolution (see
 * {@code GrantResolutionService}).
 */
public record FunderCountDto(String funderName, String fundRefId, long publicationCount) {
}
