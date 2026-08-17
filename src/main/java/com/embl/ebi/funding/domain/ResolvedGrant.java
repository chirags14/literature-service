package com.embl.ebi.funding.domain;

/**
 * A grant record as returned by the Europe PMC Grants (GRIST) API, normalized to the fields we
 * consider useful for this service (funder, canonical funder identifier, title, and dates).
 */
public record ResolvedGrant(
        String grantId,
        String funderName,
        String fundRefId,
        String title,
        String startDate,
        String endDate
) {

    /** Stable key for grouping/deduplicating the same underlying grant across publications. */
    public String identityKey() {
        return (funderName == null ? "" : funderName.trim().toLowerCase()) + "|" + grantId;
    }
}
