package com.embl.ebi.funding.service;

/**
 * Normalizes and compares funder/agency names.
 *
 * <p>Real Europe PMC data observed during investigation shows the same grant can appear twice on
 * one publication with agency strings that differ only by an added parenthetical, e.g.
 * {@code "National Natural Science Foundation of China"} vs
 * {@code "National Natural Science Foundation of China (National Science Foundation of China)"}.
 * A simple case/whitespace-insensitive containment check (in either direction) handles this
 * without resorting to fuzzy/approximate string matching, which the API investigation found no
 * evidence to justify.
 */
final class FunderNameMatcher {

    private FunderNameMatcher() {
    }

    static boolean matches(String reportedAgency, String funderName) {
        if (reportedAgency == null || funderName == null) {
            return false;
        }
        String a = normalize(reportedAgency);
        String b = normalize(funderName);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        return a.equals(b) || a.contains(b) || b.contains(a);
    }

    /** Also used by {@link ResponseAssembler} to key the funder tally, so one definition governs both. */
    static String normalize(String value) {
        return value.trim().toLowerCase().replaceAll("[^a-z0-9]+", " ").trim();
    }
}
