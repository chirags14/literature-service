package com.embl.ebi.funding.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.embl.ebi.funding.client.grist.EuropePmcGrantClient;
import com.embl.ebi.funding.client.grist.GristRecord;
import com.embl.ebi.funding.config.EuropePmcProperties;
import com.embl.ebi.funding.domain.FundingReference;
import com.embl.ebi.funding.domain.GrantResolution;
import com.embl.ebi.funding.domain.Publication;
import com.embl.ebi.funding.domain.PublicationId;
import com.embl.ebi.funding.domain.ResolutionStatus;
import com.embl.ebi.funding.exception.EuropePmcUpstreamException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Exercises the deterministic grant-matching strategy documented in the API investigation report,
 * against a fake {@link EuropePmcGrantClient} so no live Europe PMC call is ever made.
 */
class GrantResolutionServiceTest {

    private static Publication publicationWith(String id, FundingReference... refs) {
        return new Publication(new PublicationId("MED", id), "Title " + id, List.of(), null, null, null,
                null, null, null, null, null, List.of(refs));
    }

    private static EuropePmcProperties properties(int concurrency) {
        return new EuropePmcProperties(
                new EuropePmcProperties.Articles("https://example.invalid"),
                new EuropePmcProperties.Grist("https://example.invalid"),
                1000, 1000, concurrency, 1, 0);
    }

    @Test
    void resolves_whenGrantIdAndFunderBothMatchASingleRecord() {
        var fake = new FakeGrantClient(Map.of("083611",
                List.of(new GristRecord("083611", "Wellcome Trust", "fundref:1", "A grant", "2007", "2013"))));
        var service = new GrantResolutionService(fake, properties(4));

        Publication publication = publicationWith("p1", FundingReference.of("083611", "Wellcome Trust"));
        Map<PublicationId, List<GrantResolution>> result = service.resolve(List.of(publication)).resolutionsByPublication();

        GrantResolution resolution = result.get(publication.id()).get(0);
        assertThat(resolution.status()).isEqualTo(ResolutionStatus.RESOLVED);
        assertThat(resolution.resolvedGrant().funderName()).isEqualTo("Wellcome Trust");
    }

    // Real, verified scenario: grant id 336677 is a genuine Wellcome Trust award in GRIST, but a
    // real publication independently reports the same numeric id against "Academy of Finland" —
    // an unrelated, GRIST-uncovered award. Id-only matching would silently produce a false match.
    @Test
    void doesNotResolve_whenGrantIdMatchesButFunderDiffers() {
        var fake = new FakeGrantClient(Map.of("336677",
                List.of(new GristRecord("336677", "Wellcome Trust", null, "DREAMS", null, null))));
        var service = new GrantResolutionService(fake, properties(4));

        Publication publication = publicationWith("p1", FundingReference.of("336677", "Academy of Finland"));
        Map<PublicationId, List<GrantResolution>> result = service.resolve(List.of(publication)).resolutionsByPublication();

        GrantResolution resolution = result.get(publication.id()).get(0);
        assertThat(resolution.status()).isEqualTo(ResolutionStatus.UNRESOLVED);
        assertThat(resolution.reason()).contains("different funder");
    }

    @Test
    void isAmbiguous_whenMultipleCandidatesShareAnIdAndFunderCannotDisambiguate() {
        var fake = new FakeGrantClient(Map.of("336677", List.of(
                new GristRecord("336677", "Wellcome Trust", null, "DREAMS", null, null),
                new GristRecord("336677", "Some Other Funder", null, "Other", null, null))));
        var service = new GrantResolutionService(fake, properties(4));

        Publication publication = publicationWith("p1", FundingReference.of("336677", "Unrelated Agency"));
        Map<PublicationId, List<GrantResolution>> result = service.resolve(List.of(publication)).resolutionsByPublication();

        GrantResolution resolution = result.get(publication.id()).get(0);
        assertThat(resolution.status()).isEqualTo(ResolutionStatus.AMBIGUOUS);
        assertThat(resolution.ambiguousCandidates()).hasSize(2);
    }

    @Test
    void isUnresolved_whenNoGrantIdWasReported() {
        var fake = new FakeGrantClient(Map.of());
        var service = new GrantResolutionService(fake, properties(4));

        Publication publication = publicationWith("p1", FundingReference.of(null, "Davidoff Foundation Fellowship"));
        Map<PublicationId, List<GrantResolution>> result = service.resolve(List.of(publication)).resolutionsByPublication();

        GrantResolution resolution = result.get(publication.id()).get(0);
        assertThat(resolution.status()).isEqualTo(ResolutionStatus.UNRESOLVED);
        assertThat(resolution.reason()).contains("no grant identifier");
        assertThat(fake.callCount("Davidoff Foundation Fellowship")).isZero();
    }

    @Test
    void isUnresolved_whenGrantIdNotFoundInGrantsApi() {
        var fake = new FakeGrantClient(Map.of("82525064", List.of()));
        var service = new GrantResolutionService(fake, properties(4));

        Publication publication = publicationWith("p1",
                FundingReference.of("82525064", "National Natural Science Foundation of China"));
        Map<PublicationId, List<GrantResolution>> result = service.resolve(List.of(publication)).resolutionsByPublication();

        assertThat(result.get(publication.id()).get(0).status()).isEqualTo(ResolutionStatus.UNRESOLVED);
    }

    @Test
    void deduplicatesLookups_andTracesSharedGrantBackToBothPublications() {
        var fake = new FakeGrantClient(Map.of("083611",
                List.of(new GristRecord("083611", "Wellcome Trust", null, "A grant", null, null))));
        var service = new GrantResolutionService(fake, properties(4));

        Publication p1 = publicationWith("p1", FundingReference.of("083611", "Wellcome Trust"));
        Publication p2 = publicationWith("p2", FundingReference.of("083611", "Wellcome Trust"));

        Map<PublicationId, List<GrantResolution>> result = service.resolve(List.of(p1, p2)).resolutionsByPublication();

        assertThat(result.get(p1.id()).get(0).status()).isEqualTo(ResolutionStatus.RESOLVED);
        assertThat(result.get(p2.id()).get(0).status()).isEqualTo(ResolutionStatus.RESOLVED);
        assertThat(fake.callCount("083611")).isEqualTo(1);
    }

    // A Grants API failure must degrade one reference, not the request: the failed ids come back
    // UNRESOLVED with a reason saying so, unaffected references still resolve, and an aggregate
    // count is reported so a caller can tell the response was degraded without walking every
    // publication's funding references (it becomes a top-level warning).
    @Test
    void treatsUpstreamFailureAsUnresolved_withoutFailingOtherReferences_andCountsIt() {
        var fake = new FakeGrantClient(Map.of("083611",
                List.of(new GristRecord("083611", "Wellcome Trust", null, "A grant", null, null))));
        fake.failFor("999999");
        fake.failFor("888888");
        var service = new GrantResolutionService(fake, properties(4));

        Publication publication = publicationWith("p1",
                FundingReference.of("999999", "Some Agency"),
                FundingReference.of("888888", "Some Other Agency"),
                FundingReference.of("083611", "Wellcome Trust"));

        var outcome = service.resolve(List.of(publication));
        List<GrantResolution> resolutions = outcome.resolutionsByPublication().get(publication.id());

        assertThat(resolutions.get(0).status()).isEqualTo(ResolutionStatus.UNRESOLVED);
        assertThat(resolutions.get(0).reason()).contains("failed");
        assertThat(resolutions.get(1).status()).isEqualTo(ResolutionStatus.UNRESOLVED);
        assertThat(resolutions.get(2).status()).isEqualTo(ResolutionStatus.RESOLVED);
        assertThat(outcome.failedLookupCount()).isEqualTo(2);
    }

    private static final class FakeGrantClient implements EuropePmcGrantClient {
        private final Map<String, List<GristRecord>> byId;
        private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        private final java.util.Set<String> failingIds = ConcurrentHashMap.newKeySet();

        FakeGrantClient(Map<String, List<GristRecord>> byId) {
            this.byId = byId;
        }

        void failFor(String grantId) {
            failingIds.add(grantId);
        }

        int callCount(String grantId) {
            return calls.getOrDefault(grantId, new AtomicInteger(0)).get();
        }

        @Override
        public List<GristRecord> findByGrantId(String grantId) {
            calls.computeIfAbsent(grantId, k -> new AtomicInteger()).incrementAndGet();
            if (failingIds.contains(grantId)) {
                throw new EuropePmcUpstreamException("simulated failure for " + grantId);
            }
            return byId.getOrDefault(grantId, List.of());
        }
    }
}
