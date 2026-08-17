package com.embl.ebi.funding.domain;

/**
 * Outcome of attempting to resolve a {@link FundingReference} against the Europe PMC Grants
 * (GRIST) API.
 */
public enum ResolutionStatus {
    /** Matched exactly one Grants API record with a corresponding funder. */
    RESOLVED,
    /** No corresponding Grants API record could be identified with confidence. */
    UNRESOLVED,
    /** More than one candidate Grants API record matched and could not be disambiguated. */
    AMBIGUOUS
}
