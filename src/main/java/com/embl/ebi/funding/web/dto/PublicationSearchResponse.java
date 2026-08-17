package com.embl.ebi.funding.web.dto;

import java.util.List;

/**
 * Top-level response body for {@code GET /publications}.
 *
 * <p>{@code warnings} surfaces request-level degraded-mode conditions (e.g. Europe PMC becoming
 * unavailable partway through pagination, or a Grants API lookup failing after retries) — always
 * present as an (possibly empty) list, never silently absorbed.
 *
 * <p>Component order is deliberate, since Jackson serializes records in declaration order and
 * {@code publications} can run to thousands of lines: what was asked, whether the answer can be
 * trusted, the answer, then the evidence behind it. Object member order carries no meaning in JSON,
 * so this is purely for whoever reads the response.
 */
public record PublicationSearchResponse(
        String query,
        int requestedLimit,
        List<String> warnings,
        SummaryDto summary,
        List<PublicationDto> publications
) {
}
