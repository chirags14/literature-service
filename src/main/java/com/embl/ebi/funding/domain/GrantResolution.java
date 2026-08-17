package com.embl.ebi.funding.domain;

import java.util.List;

/**
 * The result of attempting to resolve one {@link FundingReference} against the Grants API.
 * Carries the outcome ({@code RESOLVED}, {@code UNRESOLVED}, {@code AMBIGUOUS}), any resolved
 * grant data, and a human-readable reason — because "we couldn't resolve this" is only useful if
 * the caller also knows why, and dropping references silently would make data gaps invisible.
 */
public record GrantResolution(
        FundingReference reported,
        ResolutionStatus status,
        ResolvedGrant resolvedGrant,
        List<ResolvedGrant> ambiguousCandidates,
        String reason
) {
    public GrantResolution {
        ambiguousCandidates = ambiguousCandidates == null ? List.of() : List.copyOf(ambiguousCandidates);
    }

    public static GrantResolution resolved(FundingReference reported, ResolvedGrant grant, String reason) {
        return new GrantResolution(reported, ResolutionStatus.RESOLVED, grant, List.of(), reason);
    }

    public static GrantResolution unresolved(FundingReference reported, String reason) {
        return new GrantResolution(reported, ResolutionStatus.UNRESOLVED, null, List.of(), reason);
    }

    public static GrantResolution ambiguous(FundingReference reported, List<ResolvedGrant> candidates, String reason) {
        return new GrantResolution(reported, ResolutionStatus.AMBIGUOUS, null, candidates, reason);
    }
}
