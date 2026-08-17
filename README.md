# Europe PMC Literature Funding Service

A Spring Boot (Java 21) REST service that searches [Europe PMC](https://europepmc.org) for
publications matching a query, extracts the funding/grant information reported on each
publication, and attempts to resolve it against the Europe PMC Grants (GRIST) API.

## Contents

- [Build, run, and test](#build-run-and-test)
- [Example request](#example-request)
- [Design decisions](#design-decisions)
- [Assumptions about Europe PMC data](#assumptions-about-europe-pmc-data)
- [Grant-resolution strategy and its limitations](#grant-resolution-strategy-and-its-limitations)
- [Operational concerns](#operational-concerns)
  - [Future scope](#future-scope)
  - [Fault tolerance](#fault-tolerance-what-happens-when-a-europe-pmc-dependency-is-down)
- [Review of my solution](#review-of-my-solution)

## Build, run, and test

Requires Java 21 (the project targets `java.version=21`).

```bash
# Run the test suite (no network access required — everything is stubbed)
./mvnw test

# Build the jar
./mvnw -DskipTests package

# Run it directly
java -jar target/literature-task-0.0.1-SNAPSHOT.jar
```

### Docker

```bash
docker build -t literature-task .
docker run --rm -p 8080:8080 literature-task
```

The container starts the REST API automatically on port 8080; no other setup is required (no
database, no GUI). Docker Compose was not used — a single stateless container with no dependent
services doesn't need it.

> Verified directly: both commands above work end-to-end. The multi-stage build (`eclipse-temurin:21-jdk`
> to compile, `eclipse-temurin:21-jre` + non-root `appuser` to run) succeeds, and the running
> container serves real requests against live Europe PMC (`200` with real data) and rejects invalid
> input (`400 bad_request`), exactly as documented below.

Once running, the service listens on `http://localhost:8080`.

## Example request

A plain topic search — exactly what a real client sends. Note that a client never needs to know a
grant id up front; grant/funding information is something the *response* surfaces, not something
the request depends on.

```bash
curl "http://localhost:8080/publications?query=COVID-19%20AND%20mental%20health&limit=25"
```

```json
{
  "query": "COVID-19 AND mental health",
  "requestedLimit": 25,
  "warnings": [],
  "summary": {
    "totalPublicationsMatchedByEuropePmc": 241124,
    "publicationsReturned": 25,
    "totalFundingReferencesReported": 15,
    "resolvedReferenceCount": 2,
    "unresolvedReferenceCount": 13,
    "ambiguousReferenceCount": 0,
    "topReportedFunders": [
      { "funderName": "World Health Organization", "fundRefId": "https://doi.org/10.13039/100004423", "publicationCount": 2 },
      { "funderName": "Ministry of Science and ICT", "fundRefId": null, "publicationCount": 1 },
      { "funderName": "Ministry of Health and Welfare", "fundRefId": null, "publicationCount": 1 },
      { "funderName": "National Research Foundation of Korea", "fundRefId": null, "publicationCount": 1 },
      { "funderName": "Korea Health Industry Development Institute", "fundRefId": null, "publicationCount": 1 }
    ],
    "resolvedGrants": [
      {
        "grantId": "001",
        "funderName": "World Health Organization",
        "fundRefId": "https://doi.org/10.13039/100004423",
        "title": "WHO generic grant number for open-access policy",
        "publicationIds": ["MED:38293595", "MED:36229400"]
      }
    ]
  },
  "publications": [
    {
      "id": "MED:40610010",
      "source": "MED",
      "pmid": "40610010",
      "pmcid": "PMC12425857",
      "doi": "10.4178/epih.e2025033",
      "title": "Cohort profile: Cardiovascular Metabolic Etiological Research Center COVID-19 Mental Health Survey (CC-MHS).",
      "authors": ["Jung SJ", "Lee D", "Yang JS", "Kang S", "Kim H", "..."],
      "journal": "Epidemiology and health",
      "publicationDate": "2025",
      "pubYear": 2025,
      "abstractText": "A comprehensive longitudinal cohort study on COVID-19 and mental health...",
      "citedByCount": 0,
      "fundingReferences": [
        {
          "reportedGrantId": null,
          "reportedAgency": "Ministry of Science and ICT",
          "status": "UNRESOLVED",
          "reason": "no grant identifier was reported; free-text agency-only resolution was not attempted",
          "resolvedGrant": null
        },
        {
          "reportedGrantId": "HI22C0505",
          "reportedAgency": "Ministry of Health and Welfare",
          "status": "UNRESOLVED",
          "reason": "grant id not found in the Grants API (the funder may not be part of Europe PMC's Grants coverage)",
          "resolvedGrant": null
        }
      ]
    },
    {
      "id": "MED:38293595",
      "source": "MED",
      "pmid": "38293595",
      "pmcid": "PMC10825874",
      "doi": "10.3389/fpsyt.2023.1320156",
      "title": "Students' mental health during the pandemic: results of the observational cross-sectional COVID-19 MEntal health inTernational for university Students (COMET-S) study.",
      "authors": ["Fountoulakis KN", "Alias NA", "Bjedov S", "..."],
      "journal": "Frontiers in psychiatry",
      "publicationDate": "2023",
      "pubYear": 2023,
      "abstractText": "Rates of depression and mental health in university students during the COVID-19 pandemic...",
      "citedByCount": 0,
      "fundingReferences": [
        {
          "reportedGrantId": "001",
          "reportedAgency": "World Health Organization",
          "status": "RESOLVED",
          "reason": "grant id and funder both matched a single Grants API record",
          "resolvedGrant": {
            "grantId": "001",
            "funderName": "World Health Organization",
            "fundRefId": "https://doi.org/10.13039/100004423",
            "title": "WHO generic grant number for open-access policy",
            "startDate": null,
            "endDate": null
          }
        }
      ]
    }
  ]
}
```

(Trimmed to 2 of 25 publications for brevity; `topReportedFunders` is shown in full — real output
from a live run, not constructed data. Most funding references here are `UNRESOLVED` because
GRIST's coverage centres on the "Europe PMC Funders' Group" — see
[limitations](#grant-resolution-strategy-and-its-limitations) — so Korean/US funders legitimately
don't resolve.)

`warnings` and `summary` come before `publications` deliberately: with a default `limit` of 25 and
full abstracts, the publication list runs to well over a thousand lines, and the two blocks that tell
you whether the answer can be trusted and what the answer is shouldn't be buried underneath it.

`warnings` is always present (possibly empty). It's populated when the request degrades but still
returns something useful — e.g. Europe PMC becoming unavailable partway through pagination, or a
grant lookup failing even after a retry — see
[Fault tolerance](#fault-tolerance-what-happens-when-a-europe-pmc-dependency-is-down).

**`topReportedFunders` vs. `resolvedGrants`:** `resolvedGrants` is the *grant-level, verified* view —
only `RESOLVED` references, deduplicated by GRIST's canonical grant identity, with a stable
`fundRefId` and full publication traceability. `topReportedFunders` answers a different question:
*which funders occur most frequently in the returned publications*. So:

- **The unit of counting is the publication, not the grant.** A publication reporting three grants
  from one funder counts once for that funder.
- **References count regardless of resolution status**, because GRIST's coverage makes
  `resolvedReferenceCount` legitimately `0` for many real queries — a `RESOLVED`-only tally would be
  empty far too often to be useful.
- **A funder GRIST verified is credited under its canonical name and `fundRefId`**, so two
  publishers spelling the same funder differently collapse into one entry. Unresolved references
  fall back to the raw `agency` text, so an entry with no `fundRefId` is unverified text rather than
  a confirmed identity — and two unresolved spellings of one funder do stay separate (no fuzzy
  matching, same reasoning as during resolution).
- **Explicit "no funding" declarations are excluded from the ranking** — some publishers put
  statements like `"no funding associated with the work featured in this article"` in the `agency`
  field, and ranking that as a funder would be misleading. This is a ranking filter only, not an
  inference: the reference is still reported in full on the publication, still counts in
  `totalFundingReferencesReported` and `unresolvedReferenceCount`, and nothing in the response
  concludes that the publication was unfunded. Europe PMC provides no structured "no funding"
  indicator, so treating that free text as evidence either way would be a guess.
- Sorted by publication count descending, capped to the top 5
  (`ResponseAssembler.TOP_FUNDERS_LIMIT`) — it's a ranking, not an inventory of every agency named.

**Parameters**

| Parameter | Required | Default | Notes |
|---|---|---|---|
| `query` | yes | — | Passed through to the Europe PMC Articles API almost verbatim; must not be blank |
| `limit` | no | 25 | Maximum publications to return; must be between 1 and 200 |

`200` is a self-imposed protective bound, not an Europe PMC constraint: every additional publication
can add further grant-resolution work downstream, and an unbounded `limit` would let a single
request trigger an unbounded number of GRIST lookups. It's a config-level choice
(`PublicationController.MAX_LIMIT`), not a load-bearing architectural decision — I'd happily raise
it or make it configurable given a real traffic profile.

**Errors** are always JSON, e.g. `{"error": "bad_request", "message": "'query' must not be blank"}`,
with `400` for invalid input and `502` if Europe PMC itself cannot be reached.

## Design decisions

Full requirements/architecture analysis and live API investigation (with request/response
evidence) were done before any code was written; the headline decisions that came out of that
process and drove the implementation:

- **`resultType=core`** — `lite` (the default) does not return `grantsList` or `abstractText`
  at all; `core` does, at the cost of a larger payload. Confirmed by comparing live `lite` vs
  `core` responses for the same query.
- **`cursorMark` pagination**, looping until either the requested `limit` is reached or Europe PMC
  stops returning a `nextCursorMark` (confirmed live: the field is *absent*, not repeated, once
  results are exhausted — a different signal than classic Solr cursor semantics).
- **Publication identity = `(source, id)`**, not `id` alone, since `id` is only unique within a
  source (e.g. `MED`, `PMC`, `PPR`).
- **Deterministic, id-first grant matching** (see below) rather than fuzzy/heuristic text matching
  — no evidence from the live APIs justified approximate string matching, and an id+funder check is
  explainable and testable.
- **Distinct grant ids are resolved once per request**, not once per funding reference — the same
  grant is legitimately expected to appear on many publications, and GRIST doesn't publish a rate
  limit, so avoiding redundant calls is both faster and more considerate of the upstream service.
  Lookups for the deduplicated set fan out with a small bounded-concurrency pool (virtual threads
  behind a semaphore, default 8 concurrent) rather than serially or unbounded.
- **A plain `GET /publications?query=&limit=` endpoint** — idiomatic REST for a read-only search.
  There is no mutation, no distinct resource being created, and no long-running/async processing
  that would justify a different shape.
- **HTTP/1.1 forced on the outbound `java.net.http.HttpClient`** — during development, HTTP/2
  connections to Europe PMC intermittently received a mid-response `GOAWAY` in this environment;
  HTTP/1.1 did not exhibit it. Documented as a defensive choice, not a Europe PMC-documented
  requirement.
- **A single hand-rolled retry, not a resilience library** — see
  [Fault tolerance](#fault-tolerance-what-happens-when-a-europe-pmc-dependency-is-down) for the
  full reasoning; in short, neither Europe PMC API publishes an SLA or rate limit to tune a circuit
  breaker against, so any configuration values would be invented rather than evidence-based.

## Assumptions about Europe PMC data

These were tested against live responses, not assumed from documentation alone (see the API
investigation notes I produced before writing code):

- Every field on a publication other than `id`/`source` may be absent (no abstract, no
  `authorList`, no `journalInfo`, no `grantsList`) — the mapping layer never assumes otherwise.
- A single `grantsList` entry's `grantId` can itself contain **multiple comma-separated
  identifiers** (e.g. `"82525064, 82273876"`); these are split into independent funding references
  before resolution, while the original raw string is preserved for traceability.
- A `grantsList` entry can have an `agency` with no `grantId` (free-text-only funding
  acknowledgements) — these are always `UNRESOLVED` by design (see below).
- Europe PMC returns **HTTP 200 with an embedded `{"errCode": ..., "errMsg": ...}` body** for
  malformed queries, not a 4xx/5xx status — the client inspects every response body for this shape
  before treating it as a successful page.
- GRIST's `RecordList.Record` is a **JSON object when exactly one grant matches and a JSON array
  when more than one matches** — an XML-derived quirk that must be parsed defensively rather than
  bound to a fixed-shape class.
- **Grant ids are not globally unique across funders.** Live evidence: id `336677` is a real
  Wellcome Trust award in GRIST, while a real publication independently reports the same numeric id
  against "Academy of Finland" — an unrelated award GRIST doesn't cover. Id-only matching would
  silently produce a false positive here.
- GRIST coverage is centred on the "Europe PMC Funders' Group"; large non-member funders (observed:
  China's NSFC, China Postdoctoral Science Foundation) are legitimately absent, so a meaningful
  fraction of `UNRESOLVED` outcomes reflect coverage gaps, not matching failures.
- Some publisher-reported grant ids include a funder-specific formatting suffix that GRIST doesn't
  store (observed live: a publication reports `"083611/Z/07/Z"` for the same underlying award GRIST
  records as plain `"083611"`) — see [limitations](#grant-resolution-strategy-and-its-limitations).

## Grant-resolution strategy and its limitations

For each funding reference reported on a publication:

1. **No grant id reported** → always `UNRESOLVED`. Free-text agency-only resolution was
   deliberately not attempted — nothing in the live data or documentation justified approximate
   name matching, and a wrong "resolution" is worse than an honest unresolved.
2. **Grant id resolves to exactly one GRIST record**:
   - If the reported agency corresponds to that record's funder (case/whitespace-insensitive,
     either-direction containment — handles observed variants like `"NSFC"` vs `"NSFC (National
     Natural Science Foundation of China)"`) → `RESOLVED`.
   - If the agency clearly points to a *different* funder → `UNRESOLVED` (the id match is not
     treated as reliable evidence on its own — this is exactly the `336677` collision above).
   - If no agency was reported at all → `RESOLVED`, but with a reason string noting no cross-check
     was possible, so a consumer can distinguish confidence levels if it cares to.
3. **Grant id resolves to multiple GRIST records** → disambiguated by funder if possible; otherwise
   `AMBIGUOUS`, with every candidate preserved (never guessed).
4. Every outcome carries a **human-readable `reason`**, and nothing is ever dropped: unresolved and
   ambiguous references appear in the response exactly like resolved ones.

**Known limitations, by design or by time constraint:**

- **No grant-id suffix normalisation.** The `"083611/Z/07/Z"` vs `"083611"` case above is not
  handled — I chose not to guess at funder-specific suffix conventions (Wellcome-style
  `/type/year/institution` suffixes) without more evidence of how consistently they're used across
  funders, since a wrong stripping rule would produce false positives silently. This is the single
  biggest source of `UNRESOLVED` results I observed against real data and is the first thing I'd
  investigate further (see "Review of my solution" below).
- **No fuzzy/approximate matching** of agency names or free-text-only funding acknowledgements — a
  deliberate choice, not an oversight; see point 1 above.
- **Funder-name matching is a normalised containment check**, not a canonical identifier match
  (GRIST doesn't expose a stable funder id queryable from an arbitrary reported agency string). If
  two unrelated funders happened to have one name contained in the other, this could theoretically
  over-match; I did not observe this in practice.
- **GRIST coverage gaps are indistinguishable from "wrong data"** in the response — an `UNRESOLVED`
  entry doesn't currently say *why* coverage might be missing versus the id simply being wrong,
  beyond the generic reason text.
- **`topReportedFunders` only groups spelling variants that GRIST could verify.** Resolved
  references are credited under GRIST's canonical funder name, so those do group; unresolved ones
  are counted under the publisher's raw text, and two variants of one funder stay separate (observed
  live on a single publication: `"Fundamental Research Funds for the Central Universities"` vs
  `"Fundamental Research Fund for the Central Universities"`). Same "no fuzzy matching" choice as
  above, for the same reason: no evidence justified guessing which raw strings mean the same funder.

## Operational concerns

- **No documented rate limits from either Europe PMC API** were found (checked the official docs
  and FAQ). Concurrency for grant lookups is therefore self-imposed and conservative (bounded to 8
  concurrent requests, configurable via `europepmc.grant-resolution-max-concurrency`), rather than
  tuned against a published number.
- **Connect/read timeouts are explicit** (3s / 8s by default, configurable) so a slow or hanging
  upstream call cannot block a request indefinitely.
- **No caching beyond per-request deduplication.** Every request re-fetches from both Europe PMC
  APIs; only identical grant ids *within the same request* are deduplicated (see
  `GrantResolutionService`). See [Future scope](#future-scope) below.
- **No persistence, so nothing survives a restart** — the service is fully stateless; worth stating
  explicitly as a deployment characteristic even though it's intentional.
- **Stateless and horizontally scalable as-is** — nothing in the request path depends on
  process-local state beyond the per-request resolution cache, so running multiple replicas behind
  a load balancer requires no code change.

### Future scope

Two things I'd deliberately hold off on until there's real usage data, rather than guess at now:

- **A cross-request cache in front of both Europe PMC APIs.** Grant ids are already deduplicated
  *within* one request, but the same grant ids (and plausibly the same popular queries) are likely
  to recur *across* requests too — that's the more valuable place to cache, but I'd want to review
  actual query/grant-id repetition patterns from real traffic first, rather than pick a TTL and
  eviction policy out of thin air. GRIST lookups are the more obvious first candidate (grant records
  change rarely, so a longer TTL is low-risk); caching Articles search results is less obviously
  worthwhile and depends entirely on how much the same queries actually repeat.
- **A circuit breaker on top of the current retry** (see
  [Fault tolerance](#fault-tolerance-what-happens-when-a-europe-pmc-dependency-is-down)) to stop
  hammering a *sustained* outage instead of retrying every incoming request — again, best tuned
  against observed failure-rate data rather than invented thresholds.

### Fault tolerance: what happens when a Europe PMC dependency is down

I deliberately did **not** reach for a resilience library (e.g. Resilience4j): a circuit breaker
needs failure-rate thresholds and window sizes to configure, and neither Europe PMC API publishes
anything to base those numbers on — invented values would be worse than none. Instead, the actual
observable failure modes are covered with small, hand-rolled, dependency-free logic:

| Failure scenario | Behaviour |
|---|---|
| A single GRIST grant-id lookup fails (timeout/5xx), one time | Retried once automatically (`RetrySupport`, ~300ms fixed delay) before being treated as a failure. |
| A single GRIST grant-id lookup still fails after the retry | Only *that* grant id is affected: every funding reference referencing it is reported `UNRESOLVED` with an explicit reason ("the Grants API lookup for this grant id failed... and was treated as unresolved"). All other publications and grant ids in the same request resolve normally — one bad grant id can never fail the whole search. The response also carries a top-level `warnings` entry so the degraded state is visible without inspecting every reference individually (e.g. `"2 grant id lookup(s) against the Grants API failed even after retrying..."`). |
| The Articles API fails on the *first* page of a search | There is nothing to return, so the request fails with `502 Bad Gateway` and a clear JSON error body (after the same one retry). |
| The Articles API fails on a *later* page, after earlier pages already succeeded | The publications already fetched are still returned (HTTP 200), flagged `partial` internally and surfaced as a `warnings` entry (e.g. `"Europe PMC Articles API became unavailable after 25 of the requested 100 publications were fetched..."`) — a client gets "fewer results than requested, with an honest explanation" instead of "nothing at all". |
| A malformed query (Europe PMC's embedded `errCode` response) | Never retried — retrying a bad query cannot make it succeed. Returned as `400 Bad Request` immediately. |

This retry (1 initial attempt + 1 retry, ~300ms apart, configurable via
`europepmc.retry-attempts`/`retry-delay-ms`) only targets transport-level failures, never a request
Europe PMC actively rejected. It's validated against a real transient `503` from `www.ebi.ac.uk`
during a live smoke test, and covered by dedicated tests (`recoversFromTransientFailure_onRetry` and
others) rather than only described here.

If this were headed to production, a circuit breaker would be the next addition here — see
[Future scope](#future-scope) — paired with metrics/alerting on the `warnings` rate.

## Review of my solution

**What worked well:** the up-front live API investigation (real requests, not documentation alone)
surfaced the grant-id collision, the HTTP-200-with-embedded-error pattern, and the GRIST
object/array quirk before any code was written, so matching and parsing were designed around real
behaviour rather than assumptions I'd have had to walk back.

**What I'd change with more time:** sample `grantsList.grantId` formatting across a much larger,
more diverse set of publications before finalising the matching strategy — the `"083611/Z/07/Z"`
suffix case only surfaced late, and a broader sample might have justified an evidence-based
suffix-normalisation rule instead of leaving it as an open limitation.

**One assumption I'd want to validate before production:** that an agency mismatch should mean
`UNRESOLVED` rather than "resolved with low confidence" — some consumers might prefer the latter;
I'd want real usage data before committing to one default.

**One piece of technical debt I deliberately accepted:** no cross-request caching in front of
either Europe PMC API — see [Future scope](#future-scope).
