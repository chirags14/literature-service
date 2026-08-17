package com.embl.ebi.funding.domain;

/**
 * A funding/grant reference exactly as reported by Europe PMC against a publication, before any
 * attempt to resolve it against the Grants (GRIST) API.
 *
 * <p>Real Europe PMC data observed during investigation shows both fields are independently
 * optional (a grant id with no agency, or an agency-only entry with no id), and that a single
 * {@code grantId} string can itself contain multiple comma-separated identifiers. Callers that
 * split such a field should populate {@code rawGrantIdField} with the original, un-split text so
 * that provenance back to the source data is preserved.
 */
public record FundingReference(String grantId, String agency, String rawGrantIdField) {

    public FundingReference {
        grantId = blankToNull(grantId);
        agency = blankToNull(agency);
    }

    public static FundingReference of(String grantId, String agency) {
        return new FundingReference(grantId, agency, null);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
