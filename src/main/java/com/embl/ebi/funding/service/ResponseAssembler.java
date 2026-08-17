package com.embl.ebi.funding.service;

import com.embl.ebi.funding.domain.GrantResolution;
import com.embl.ebi.funding.domain.Publication;
import com.embl.ebi.funding.domain.PublicationId;
import com.embl.ebi.funding.domain.ResolutionStatus;
import com.embl.ebi.funding.domain.ResolvedGrant;
import com.embl.ebi.funding.web.dto.FunderCountDto;
import com.embl.ebi.funding.web.dto.FundingReferenceDto;
import com.embl.ebi.funding.web.dto.PublicationDto;
import com.embl.ebi.funding.web.dto.PublicationSearchResponse;
import com.embl.ebi.funding.web.dto.ResolvedGrantSummaryDto;
import com.embl.ebi.funding.web.dto.SummaryDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Builds the public JSON response from the internal domain model and grant-resolution outcomes.
 * Kept separate from {@link LiteratureSearchService} so that aggregation logic (top funders,
 * resolved-grant traceability) does not leak into orchestration or matching code.
 */
@Component
public class ResponseAssembler {

    // "Top" funders, not "every funder that was reported" — capped so the field stays a useful
    // at-a-glance ranking rather than growing as long as the publication list itself.
    private static final int TOP_FUNDERS_LIMIT = 5;

    public PublicationSearchResponse assemble(String query,
                                               int requestedLimit,
                                               long totalHitCount,
                                               List<Publication> publications,
                                               Map<PublicationId, List<GrantResolution>> resolutionsByPublication,
                                               List<String> warnings) {
        List<PublicationDto> publicationDtos = new ArrayList<>();
        int totalReferences = 0;
        int resolvedReferences = 0;
        int unresolvedReferences = 0;
        int ambiguousReferences = 0;

        // The funder tally answers "which funders occur most frequently in the returned
        // publications", so the unit of counting is the publication, not the grant: each distinct
        // funder is credited at most once per publication even when that publication reports
        // several grants from it. Every funding reference contributes regardless of resolution
        // status — GRIST's coverage is narrow enough (see FunderNameMatcher/README) that a
        // RESOLVED-only tally would be empty for most real queries.
        //
        // resolvedGrants remains restricted to RESOLVED references, deduplicated by GRIST-verified
        // grant identity across publications, retaining every referencing publication id for
        // traceability.
        Map<String, FunderAccumulator> funderCounts = new LinkedHashMap<>();
        Map<String, ResolvedGrantAccumulator> resolvedGrants = new LinkedHashMap<>();

        for (Publication publication : publications) {
            List<GrantResolution> resolutions = resolutionsByPublication.getOrDefault(publication.id(), List.of());
            List<FundingReferenceDto> referenceDtos = new ArrayList<>();
            Map<String, FunderIdentity> fundersOnThisPublication = new LinkedHashMap<>();

            for (GrantResolution resolution : resolutions) {
                totalReferences++;
                referenceDtos.add(FundingReferenceDto.from(resolution));

                switch (resolution.status()) {
                    case RESOLVED -> {
                        resolvedReferences++;
                        ResolvedGrant grant = resolution.resolvedGrant();
                        resolvedGrants
                                .computeIfAbsent(grant.identityKey(), k -> new ResolvedGrantAccumulator(grant))
                                .publicationIds.add(publication.id().asString());
                    }
                    case UNRESOLVED -> unresolvedReferences++;
                    case AMBIGUOUS -> ambiguousReferences++;
                }

                creditFunder(fundersOnThisPublication, resolution);
            }

            fundersOnThisPublication.forEach((key, identity) -> {
                FunderAccumulator accumulator =
                        funderCounts.computeIfAbsent(key, k -> new FunderAccumulator(identity.funderName()));
                accumulator.count++;
                if (accumulator.fundRefId == null) {
                    accumulator.fundRefId = identity.fundRefId();
                }
            });

            publicationDtos.add(toPublicationDto(publication, referenceDtos));
        }

        List<FunderCountDto> topReportedFunders = funderCounts.values().stream()
                .sorted((a, b) -> Long.compare(b.count, a.count))
                .limit(TOP_FUNDERS_LIMIT)
                .map(f -> new FunderCountDto(f.funderName, f.fundRefId, f.count))
                .toList();

        List<ResolvedGrantSummaryDto> resolvedGrantSummaries = resolvedGrants.values().stream()
                .map(acc -> new ResolvedGrantSummaryDto(acc.grant.grantId(), acc.grant.funderName(),
                        acc.grant.fundRefId(), acc.grant.title(), List.copyOf(acc.publicationIds)))
                .toList();

        SummaryDto summary = new SummaryDto(
                totalHitCount,
                publications.size(),
                totalReferences,
                resolvedReferences,
                unresolvedReferences,
                ambiguousReferences,
                topReportedFunders,
                resolvedGrantSummaries
        );

        return new PublicationSearchResponse(query, requestedLimit, warnings, summary, publicationDtos);
    }

    /**
     * Credits one funding reference to the funder tally of the publication currently being
     * assembled. Keyed by funder identity so that a publication reporting several grants from the
     * same funder — or the same funder under two spellings — still counts once for that funder.
     */
    private void creditFunder(Map<String, FunderIdentity> fundersOnThisPublication, GrantResolution resolution) {
        FunderIdentity identity = funderIdentityOf(resolution);
        if (identity == null || identity.key().isEmpty()) {
            return;
        }
        FunderIdentity existing = fundersOnThisPublication.get(identity.key());
        // A GRIST-verified identity supersedes an as-reported one for the same funder, so the tally
        // carries the canonical name and fundRefId whenever any of this publication's references to
        // that funder resolved.
        if (existing == null || (existing.fundRefId() == null && identity.fundRefId() != null)) {
            fundersOnThisPublication.put(identity.key(), identity);
        }
    }

    /**
     * The funder a reference should be counted against: GRIST's canonical funder when the grant
     * resolved, so the same funder spelled differently by two publishers collapses into one entry;
     * otherwise the publisher's reported agency text. Returns {@code null} when there is nothing
     * meaningful to count — no agency was reported, or the agency field declares the absence of
     * funding.
     */
    private static FunderIdentity funderIdentityOf(GrantResolution resolution) {
        if (resolution.status() == ResolutionStatus.RESOLVED
                && resolution.resolvedGrant().funderName() != null) {
            ResolvedGrant grant = resolution.resolvedGrant();
            return new FunderIdentity(grant.funderName(), grant.fundRefId());
        }
        String agency = resolution.reported().agency();
        if (agency == null || declaresNoFunding(agency)) {
            return null;
        }
        return new FunderIdentity(agency, null);
    }

    /**
     * Some publishers use the {@code agency} field to declare the <em>absence</em> of funding —
     * observed live: {@code "no funding associated with the work featured in this article"}. Ranking
     * that as a funder would be actively misleading, so it is excluded from the tally.
     *
     * <p>This affects the ranking only, and is deliberately not read as evidence that the
     * publication was unfunded: the reference is still reported in full against the publication and
     * still counts towards the reported/unresolved reference totals. Europe PMC exposes no
     * structured no-funding indicator, so inferring one from free text either way would be a guess.
     * The check is correspondingly narrow rather than a general blocklist.
     */
    private static boolean declaresNoFunding(String agency) {
        return FunderNameMatcher.normalize(agency).contains("no funding");
    }

    /**
     * A funder to credit a publication to, plus the key used to deduplicate it. The key reuses
     * {@link FunderNameMatcher}'s normalization so that casing and punctuation differences alone
     * don't split one funder into two entries; genuinely different strings are still kept apart,
     * since no fuzzy matching is attempted here for the same reason it isn't during resolution.
     */
    private record FunderIdentity(String funderName, String fundRefId) {
        String key() {
            return FunderNameMatcher.normalize(funderName);
        }
    }

    private PublicationDto toPublicationDto(Publication publication, List<FundingReferenceDto> referenceDtos) {
        return new PublicationDto(
                publication.id().asString(),
                publication.id().source(),
                publication.pmid(),
                publication.pmcid(),
                publication.doi(),
                publication.title(),
                publication.authors(),
                publication.journalTitle(),
                publication.publicationDate(),
                publication.pubYear(),
                publication.abstractText(),
                publication.citedByCount(),
                referenceDtos
        );
    }

    private static final class FunderAccumulator {
        final String funderName;
        String fundRefId;
        long count;

        FunderAccumulator(String funderName) {
            this.funderName = funderName;
        }
    }

    private static final class ResolvedGrantAccumulator {
        final ResolvedGrant grant;
        final List<String> publicationIds = new ArrayList<>();

        ResolvedGrantAccumulator(ResolvedGrant grant) {
            this.grant = grant;
        }
    }
}
