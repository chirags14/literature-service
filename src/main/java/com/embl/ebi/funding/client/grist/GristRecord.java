package com.embl.ebi.funding.client.grist;

/**
 * A single grant record as returned by the Europe PMC Grants (GRIST) API, already flattened out
 * of GRIST's {@code Person}/{@code Grant}/{@code Funder} envelope.
 */
public record GristRecord(
        String grantId,
        String funderName,
        String fundRefId,
        String title,
        String startDate,
        String endDate
) {
}
