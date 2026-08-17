package com.embl.ebi.funding.service;

import com.embl.ebi.funding.client.grist.EuropePmcGrantClient;
import com.embl.ebi.funding.client.grist.GristRecord;
import com.embl.ebi.funding.config.EuropePmcProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.embl.ebi.funding.domain.FundingReference;
import com.embl.ebi.funding.domain.GrantResolution;
import com.embl.ebi.funding.domain.Publication;
import com.embl.ebi.funding.domain.PublicationId;
import com.embl.ebi.funding.domain.ResolvedGrant;
import com.embl.ebi.funding.exception.EuropePmcUpstreamException;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;

/**
 * Resolves the funding references reported on a set of publications against the Europe PMC
 * Grants (GRIST) API, applying the deterministic matching strategy documented in the API
 * investigation report:
 *
 * <ol>
 *   <li>A reference with no grant id cannot be looked up by id, and free-text agency-only
 *       resolution is deliberately not attempted (no evidence justified fuzzy matching) — always
 *       {@code UNRESOLVED}.</li>
 *   <li>A grant id resolves to exactly one GRIST record only if the reported agency also
 *       corresponds to that record's funder (verified live: the same numeric id can belong to
 *       unrelated grants from different funders, e.g. id {@code 336677} is both a real Wellcome
 *       Trust award in GRIST and a distinct, GRIST-uncovered Academy of Finland award reported on
 *       a real publication) — {@code RESOLVED}, otherwise treated as {@code UNRESOLVED} rather
 *       than a false-positive match.</li>
 *   <li>If no agency was reported, a single-candidate id match is accepted with a lower-confidence
 *       reason recorded, since there is nothing to cross-check against.</li>
 *   <li>Multiple GRIST records sharing one id are disambiguated by funder where possible;
 *       otherwise the reference is {@code AMBIGUOUS} and all candidates are preserved rather than
 *       guessing.</li>
 * </ol>
 *
 * <p>Distinct grant ids are resolved once per request — the same grant legitimately appears on many
 * publications, so deduplicating upfront avoids redundant GRIST calls and is more considerate of
 * the upstream service. The deduplicated set is fetched with a small bounded-concurrency fan-out;
 * neither Europe PMC API publishes a rate limit, so unbounded concurrency would be an unjustified
 * assumption.
 */
@Service
public class GrantResolutionService {

    private static final Logger log = LoggerFactory.getLogger(GrantResolutionService.class);

    private final EuropePmcGrantClient grantClient;
    private final ExecutorService executor;
    private final int maxConcurrency;

    public GrantResolutionService(EuropePmcGrantClient grantClient, EuropePmcProperties properties) {
        this.grantClient = grantClient;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.maxConcurrency = Math.max(1, properties.grantResolutionMaxConcurrency());
    }

    public GrantResolutionOutcome resolve(List<Publication> publications) {
        Set<String> distinctGrantIds = publications.stream()
                .flatMap(p -> p.reportedFunding().stream())
                .map(reference -> reference.grantId())
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        AtomicInteger failedLookupCount = new AtomicInteger();
        Map<String, LookupOutcome> lookupCache = resolveAll(distinctGrantIds, failedLookupCount);

        Map<PublicationId, List<GrantResolution>> resolutionsByPublication = new LinkedHashMap<>();
        for (Publication publication : publications) {
            List<GrantResolution> resolutions = new ArrayList<>();
            for (FundingReference reference : publication.reportedFunding()) {
                resolutions.add(classify(reference, lookupCache));
            }
            resolutionsByPublication.put(publication.id(), resolutions);
        }
        return new GrantResolutionOutcome(resolutionsByPublication, failedLookupCount.get());
    }

    private Map<String, LookupOutcome> resolveAll(Set<String> grantIds, AtomicInteger failedLookupCount) {
        if (grantIds.isEmpty()) {
            return Map.of();
        }
        Semaphore semaphore = new Semaphore(maxConcurrency);
        Map<String, LookupOutcome> cache = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (String grantId : grantIds) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    semaphore.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    cache.put(grantId, LookupOutcome.ok(grantClient.findByGrantId(grantId)));
                } catch (EuropePmcUpstreamException e) {
                    log.warn("Grant lookup failed for id '{}' after all retries — treating as unresolved: {}",
                            grantId, e.getMessage());
                    cache.put(grantId, LookupOutcome.failedLookup());
                    failedLookupCount.incrementAndGet();
                } finally {
                    semaphore.release();
                }
            }, executor));
        }
        futures.forEach(future -> future.join());
        return cache;
    }

    private GrantResolution classify(FundingReference reference, Map<String, LookupOutcome> lookupCache) {
        if (reference.grantId() == null) {
            return GrantResolution.unresolved(reference,
                    "no grant identifier was reported; free-text agency-only resolution was not attempted");
        }

        LookupOutcome outcome = lookupCache.get(reference.grantId());
        if (outcome == null || outcome.failed()) {
            return GrantResolution.unresolved(reference,
                    "the Grants API lookup for this grant id failed or timed out and was treated as unresolved");
        }

        List<GristRecord> candidates = outcome.records();
        if (candidates.isEmpty()) {
            return GrantResolution.unresolved(reference,
                    "grant id not found in the Grants API (the funder may not be part of Europe PMC's Grants coverage)");
        }
        if (candidates.size() == 1) {
            return classifySingleCandidate(reference, candidates.get(0));
        }
        return classifyMultipleCandidates(reference, candidates);
    }

    /** Exactly one Grants API record shares this grant id — the funder is the only thing left to cross-check. */
    private GrantResolution classifySingleCandidate(FundingReference reference, GristRecord candidate) {
        if (reference.agency() == null) {
            return GrantResolution.resolved(reference, toResolvedGrant(candidate),
                    "resolved by grant id; no agency was reported by the publication so no cross-check was possible");
        }
        if (FunderNameMatcher.matches(reference.agency(), candidate.funderName())) {
            return GrantResolution.resolved(reference, toResolvedGrant(candidate),
                    "grant id and funder both matched a single Grants API record");
        }
        return GrantResolution.unresolved(reference,
                "grant id exists in the Grants API under a different funder ('" + candidate.funderName()
                        + "'); reported agency ('" + reference.agency() + "') does not match, so this is not treated as a reliable match");
    }

    /**
     * Multiple Grants API records share this grant id (verified live: the same numeric id can be
     * reused across unrelated funders). The reported agency is the only signal available to
     * disambiguate; without it, or if it doesn't narrow the field to exactly one, all candidates
     * are preserved as {@code AMBIGUOUS} rather than guessing.
     */
    private GrantResolution classifyMultipleCandidates(FundingReference reference, List<GristRecord> candidates) {
        if (reference.agency() != null) {
            List<GristRecord> matchingFunder = candidates.stream()
                    .filter(c -> FunderNameMatcher.matches(reference.agency(), c.funderName()))
                    .toList();
            if (matchingFunder.size() == 1) {
                return GrantResolution.resolved(reference, toResolvedGrant(matchingFunder.get(0)),
                        "disambiguated among multiple Grants API records sharing this id using the reported funder");
            }
            if (!matchingFunder.isEmpty()) {
                return GrantResolution.ambiguous(reference, toResolvedGrants(matchingFunder),
                        "multiple Grants API records share this grant id and more than one matches the reported funder");
            }
        }
        return GrantResolution.ambiguous(reference, toResolvedGrants(candidates),
                "multiple Grants API records share this grant id and could not be disambiguated by funder");
    }

    private ResolvedGrant toResolvedGrant(GristRecord record) {
        return new ResolvedGrant(record.grantId(), record.funderName(), record.fundRefId(), record.title(),
                record.startDate(), record.endDate());
    }

    private List<ResolvedGrant> toResolvedGrants(List<GristRecord> records) {
        return records.stream().map(this::toResolvedGrant).toList();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    private record LookupOutcome(List<GristRecord> records, boolean failed) {
        static LookupOutcome ok(List<GristRecord> records) {
            return new LookupOutcome(records, false);
        }

        static LookupOutcome failedLookup() {
            return new LookupOutcome(List.of(), true);
        }
    }

    /**
     * Per-publication resolution outcomes, plus the number of distinct grant ids whose Grants API
     * lookup failed even after retries — surfaced to the caller so it can add a top-level warning
     * rather than the failure being visible only as scattered per-reference reasons.
     */
    public record GrantResolutionOutcome(Map<PublicationId, List<GrantResolution>> resolutionsByPublication,
                                          int failedLookupCount) {
    }
}
