package com.embl.ebi.funding.web.dto;

import com.embl.ebi.funding.domain.GrantResolution;
import java.util.List;

/**
 * A single funding reference as reported by the publication, together with its resolution outcome.
 * Every reference — resolved, unresolved, or ambiguous — always appears here with an explicit
 * status and reason, so a client can tell exactly what was found and what wasn't without having to
 * infer from missing data.
 */
public record FundingReferenceDto(
        String reportedGrantId,
        String reportedAgency,
        String status,
        String reason,
        ResolvedGrantDto resolvedGrant,
        List<ResolvedGrantDto> ambiguousCandidates
) {
    public static FundingReferenceDto from(GrantResolution resolution) {
        return new FundingReferenceDto(
                resolution.reported().grantId(),
                resolution.reported().agency(),
                resolution.status().name(),
                resolution.reason(),
                resolution.resolvedGrant() == null ? null : ResolvedGrantDto.from(resolution.resolvedGrant()),
                resolution.ambiguousCandidates().stream().map(ResolvedGrantDto::from).toList()
        );
    }
}
