package com.embl.ebi.funding.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link FunderNameMatcher} backs the RESOLVED vs UNRESOLVED decision in
 * {@link GrantResolutionService} whenever a grant id match needs cross-checking against the
 * reported agency, so its normalization rules are worth pinning down directly rather than only
 * observing their effect indirectly through resolution-status assertions elsewhere.
 */
class FunderNameMatcherTest {

    @Test
    void matches_ignoringCaseAndWhitespaceDifferences() {
        assertThat(FunderNameMatcher.matches("Wellcome Trust", "Wellcome Trust")).isTrue();
        assertThat(FunderNameMatcher.matches("  wellcome   trust  ", "WELLCOME TRUST")).isTrue();
    }

    // The real, verified case this class exists for: the same grant appears twice on one
    // publication with agency strings differing only by an added parenthetical.
    @Test
    void matches_whenOneNameIsTheOtherPlusAParenthetical() {
        assertThat(FunderNameMatcher.matches(
                "National Natural Science Foundation of China",
                "National Natural Science Foundation of China (National Science Foundation of China)"))
                .isTrue();
    }

    // The guard that keeps a grant-id match from being trusted on its own: unrelated funders must
    // not match, and neither must a null or punctuation-only agency string.
    @Test
    void doesNotMatch_unrelatedFundersOrAbsentNames() {
        assertThat(FunderNameMatcher.matches("Wellcome Trust", "Academy of Finland")).isFalse();
        assertThat(FunderNameMatcher.matches(null, "Wellcome Trust")).isFalse();
        assertThat(FunderNameMatcher.matches("Wellcome Trust", null)).isFalse();
        assertThat(FunderNameMatcher.matches("   ", "Wellcome Trust")).isFalse();
        assertThat(FunderNameMatcher.matches("---", "Wellcome Trust")).isFalse();
    }
}
