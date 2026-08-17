package com.embl.ebi.funding.web.dto;

import com.embl.ebi.funding.domain.ResolvedGrant;

public record ResolvedGrantDto(
        String grantId,
        String funderName,
        String fundRefId,
        String title,
        String startDate,
        String endDate
) {
    public static ResolvedGrantDto from(ResolvedGrant grant) {
        return new ResolvedGrantDto(grant.grantId(), grant.funderName(), grant.fundRefId(), grant.title(),
                grant.startDate(), grant.endDate());
    }
}
