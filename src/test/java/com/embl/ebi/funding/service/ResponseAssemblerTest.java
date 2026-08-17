package com.embl.ebi.funding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.embl.ebi.funding.domain.FundingReference;
import com.embl.ebi.funding.domain.GrantResolution;
import com.embl.ebi.funding.domain.Publication;
import com.embl.ebi.funding.domain.PublicationId;
import com.embl.ebi.funding.domain.ResolvedGrant;
import com.embl.ebi.funding.web.dto.PublicationSearchResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The two aggregations a caller actually reasons about: {@code resolvedGrants} (verified grants,
 * traceable back to every publication that cited them) and {@code topReportedFunders} (which funders
 * occur most frequently across the returned publications).
 */
class ResponseAssemblerTest {

    private final ResponseAssembler assembler = new ResponseAssembler();

    @Test
    void tracesASharedResolvedGrantBackToEveryReferencingPublication() {
        PublicationId id1 = new PublicationId("MED", "p1");
        PublicationId id2 = new PublicationId("MED", "p2");
        Publication p1 = publication(id1, FundingReference.of("083611", "Wellcome Trust"));
        Publication p2 = publication(id2, FundingReference.of("083611", "Wellcome Trust"));

        ResolvedGrant grant = new ResolvedGrant("083611", "Wellcome Trust", "fundref:1", "A grant", "2007", "2013");
        Map<PublicationId, List<GrantResolution>> resolutions = Map.of(
                id1, List.of(GrantResolution.resolved(p1.reportedFunding().get(0), grant, "matched")),
                id2, List.of(GrantResolution.resolved(p2.reportedFunding().get(0), grant, "matched")));

        PublicationSearchResponse response =
                assembler.assemble("query", 25, 2, List.of(p1, p2), resolutions, List.of());

        assertThat(response.summary().resolvedGrants()).hasSize(1);
        assertThat(response.summary().resolvedGrants().get(0).publicationIds())
                .containsExactlyInAnyOrder("MED:p1", "MED:p2");
        assertThat(response.summary().resolvedReferenceCount()).isEqualTo(2);
        assertThat(response.summary().topReportedFunders())
                .extracting("funderName", "fundRefId", "publicationCount")
                .containsExactly(tuple("Wellcome Trust", "fundref:1", 2L));
    }

    // Unresolved and ambiguous references are never dropped: they keep their own counts, their
    // candidates, and they still contribute their reported agency to the funder tally — just
    // without a fundRefId, since nothing was verified.
    @Test
    void countsUnresolvedAndAmbiguousSeparately_andStillTalliesTheirReportedAgencies() {
        PublicationId id1 = new PublicationId("MED", "p1");
        FundingReference unresolvedRef = FundingReference.of("999", "NIH HHS");
        FundingReference ambiguousRef = FundingReference.of("336677", "Some Agency");
        Publication p1 = publication(id1, unresolvedRef, ambiguousRef);

        Map<PublicationId, List<GrantResolution>> resolutions = Map.of(id1, List.of(
                GrantResolution.unresolved(unresolvedRef, "grant id not found in the Grants API"),
                GrantResolution.ambiguous(ambiguousRef, List.of(
                        new ResolvedGrant("336677", "Funder A", null, null, null, null),
                        new ResolvedGrant("336677", "Funder B", null, null, null, null)), "ambiguous")));

        PublicationSearchResponse response =
                assembler.assemble("query", 25, 1, List.of(p1), resolutions, List.of());

        assertThat(response.summary().unresolvedReferenceCount()).isEqualTo(1);
        assertThat(response.summary().ambiguousReferenceCount()).isEqualTo(1);
        assertThat(response.summary().resolvedGrants()).isEmpty();
        assertThat(response.publications().get(0).fundingReferences()).hasSize(2);
        assertThat(response.publications().get(0).fundingReferences().get(1).ambiguousCandidates()).hasSize(2);
        assertThat(response.summary().topReportedFunders())
                .extracting("funderName", "fundRefId", "publicationCount")
                .containsExactlyInAnyOrder(tuple("NIH HHS", null, 1L), tuple("Some Agency", null, 1L));
    }

    // The unit of counting is the publication, not the grant: a publication reporting two NIH
    // grants and one WHO grant contributes +1 to each, not +2 to NIH. Also covers the fundRefId
    // being picked up from whichever of a funder's references resolved.
    @Test
    void talliesFundersOncePerPublication_evenWhenOneFunderAwardedSeveralGrants() {
        PublicationId id1 = new PublicationId("MED", "p1");
        FundingReference nihGrantA = FundingReference.of("A1", "NIH");
        FundingReference nihGrantB = FundingReference.of("B2", "NIH");
        FundingReference whoGrantC = FundingReference.of("C3", "WHO");
        Publication p1 = publication(id1, nihGrantA, nihGrantB, whoGrantC);

        Map<PublicationId, List<GrantResolution>> resolutions = Map.of(id1, List.of(
                GrantResolution.unresolved(nihGrantA, "grant id not found in the Grants API"),
                GrantResolution.resolved(nihGrantB,
                        new ResolvedGrant("B2", "NIH", "fundref:nih", "Grant B", null, null), "matched"),
                GrantResolution.resolved(whoGrantC,
                        new ResolvedGrant("C3", "WHO", "fundref:who", "Grant C", null, null), "matched")));

        PublicationSearchResponse response =
                assembler.assemble("query", 25, 1, List.of(p1), resolutions, List.of());

        assertThat(response.summary().topReportedFunders())
                .extracting("funderName", "fundRefId", "publicationCount")
                .containsExactlyInAnyOrder(tuple("NIH", "fundref:nih", 1L), tuple("WHO", "fundref:who", 1L));
        assertThat(response.summary().totalFundingReferencesReported()).isEqualTo(3);
    }

    // A funder the Grants API verified is credited under its canonical name, so two publishers
    // spelling the same funder differently produce one ranked entry rather than two.
    @Test
    void groupsResolvedFundersByCanonicalNameAcrossPublications() {
        PublicationId id1 = new PublicationId("MED", "p1");
        PublicationId id2 = new PublicationId("MED", "p2");
        FundingReference reportedAsAcronym = FundingReference.of("111", "NIH HHS");
        FundingReference reportedAsAbbreviation = FundingReference.of("222", "Nat. Institutes of Health");
        Publication p1 = publication(id1, reportedAsAcronym);
        Publication p2 = publication(id2, reportedAsAbbreviation);

        Map<PublicationId, List<GrantResolution>> resolutions = Map.of(
                id1, List.of(GrantResolution.resolved(reportedAsAcronym,
                        new ResolvedGrant("111", "National Institutes of Health", "fundref:nih", "G1", null, null),
                        "matched")),
                id2, List.of(GrantResolution.resolved(reportedAsAbbreviation,
                        new ResolvedGrant("222", "National Institutes of Health", "fundref:nih", "G2", null, null),
                        "matched")));

        PublicationSearchResponse response =
                assembler.assemble("query", 25, 2, List.of(p1, p2), resolutions, List.of());

        assertThat(response.summary().topReportedFunders())
                .extracting("funderName", "fundRefId", "publicationCount")
                .containsExactly(tuple("National Institutes of Health", "fundref:nih", 2L));
    }

    // Two things that look like funders but aren't: a publisher using the agency field to declare
    // there was no funding at all, and a reference with no agency reported. Neither belongs in a
    // "most frequent funders" ranking, but both stay visible on the publication itself.
    @Test
    void excludesNoFundingDeclarationsAndMissingAgenciesFromTheTally() {
        PublicationId id1 = new PublicationId("MED", "p1");
        FundingReference noFundingRef =
                FundingReference.of(null, "no funding associated with the work featured in this article");
        FundingReference noAgencyRef = FundingReference.of("999", null);
        FundingReference realFunderRef = FundingReference.of(null, "Wellcome Trust");
        Publication p1 = publication(id1, noFundingRef, noAgencyRef, realFunderRef);

        Map<PublicationId, List<GrantResolution>> resolutions = Map.of(id1, List.of(
                GrantResolution.unresolved(noFundingRef, "no grant identifier was reported"),
                GrantResolution.unresolved(noAgencyRef, "grant id not found in the Grants API"),
                GrantResolution.unresolved(realFunderRef, "no grant identifier was reported")));

        PublicationSearchResponse response =
                assembler.assemble("query", 25, 1, List.of(p1), resolutions, List.of());

        assertThat(response.summary().topReportedFunders())
                .extracting("funderName")
                .containsExactly("Wellcome Trust");
        assertThat(response.publications().get(0).fundingReferences()).hasSize(3);
    }

    // "Which funders occur most frequently" is a ranking, not an inventory: with more distinct
    // funders than the cap, only the most frequent ones come back, highest count first.
    @Test
    void capsTheTallyToTheTopFunders_orderedByPublicationCountDescending() {
        List<Publication> publications = new ArrayList<>();
        Map<PublicationId, List<GrantResolution>> resolutions = new HashMap<>();

        // "Frequent Funder" appears on 3 publications; eight others appear on one each — more
        // distinct funders than the cap allows through.
        for (int i = 0; i < 3; i++) {
            addPublicationFunded(publications, resolutions, "frequent" + i, "Frequent Funder");
        }
        for (int i = 0; i < 8; i++) {
            addPublicationFunded(publications, resolutions, "rare" + i, "Rare Funder " + i);
        }

        PublicationSearchResponse response =
                assembler.assemble("query", 25, publications.size(), publications, resolutions, List.of());

        assertThat(response.summary().topReportedFunders()).hasSize(5);
        assertThat(response.summary().topReportedFunders().get(0).funderName()).isEqualTo("Frequent Funder");
        assertThat(response.summary().topReportedFunders().get(0).publicationCount()).isEqualTo(3);
    }

    private static Publication publication(PublicationId id, FundingReference... references) {
        return new Publication(id, "Title " + id.id(), List.of(), null, null, null, null, null, null, null, null,
                List.of(references));
    }

    private static void addPublicationFunded(List<Publication> publications,
                                              Map<PublicationId, List<GrantResolution>> resolutions,
                                              String publicationId,
                                              String agency) {
        PublicationId id = new PublicationId("MED", publicationId);
        FundingReference reference = FundingReference.of(null, agency);
        publications.add(publication(id, reference));
        resolutions.put(id, List.of(GrantResolution.unresolved(reference, "no grant identifier was reported")));
    }
}
