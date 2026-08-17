package com.embl.ebi.funding.client.articles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.embl.ebi.funding.config.EuropePmcProperties;
import com.embl.ebi.funding.domain.Publication;
import com.embl.ebi.funding.exception.EuropePmcQueryException;
import com.embl.ebi.funding.exception.EuropePmcUpstreamException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Verifies pagination and defensive JSON mapping against response shapes captured live from
 * Europe PMC during API investigation, never against the live service itself.
 */
class EuropePmcArticleClientImplTest {

    private static final String BASE_URL = "https://www.ebi.ac.uk/europepmc/webservices/rest";

    // No retry, no delay: keeps the majority of tests below to exactly one request per page.
    // Retry behaviour itself is exercised separately, with its own properties, further down.
    private static EuropePmcProperties noRetry() {
        return new EuropePmcProperties(
                new EuropePmcProperties.Articles(BASE_URL),
                new EuropePmcProperties.Grist("https://example.invalid"),
                3000, 8000, 8, 1, 0);
    }

    private static EuropePmcProperties withRetry() {
        return new EuropePmcProperties(
                new EuropePmcProperties.Articles(BASE_URL),
                new EuropePmcProperties.Grist("https://example.invalid"),
                3000, 8000, 8, 2, 0);
    }

    // Trimmed but structurally faithful to a real `resultType=core` page 1 response for
    // query=GRANT_ID:083611&pageSize=3 (verified live 2026-08-17): hitCount=6, one publication
    // with a clean single grantsList entry, nextCursorMark present.
    private static final String PAGE_1 = """
            {
              "hitCount": 6,
              "nextCursorMark": "AoIIPwJNzSgyODkzNjEyMg==",
              "resultList": {
                "result": [
                  {
                    "id": "24408437",
                    "source": "MED",
                    "pmid": "24408437",
                    "doi": "10.1000/example",
                    "title": "Cell behaviour during neurogenesis.",
                    "authorList": { "author": [ { "fullName": "Storey KG" } ] },
                    "journalInfo": { "dateOfPublication": "2014 Jan", "yearOfPublication": 2014,
                      "journal": { "title": "Dev Biol" } },
                    "abstractText": "An abstract.",
                    "citedByCount": 12,
                    "grantsList": { "grant": [ { "grantId": "083611", "agency": "Wellcome Trust" } ] }
                  },
                  {
                    "id": "22525126",
                    "source": "MED",
                    "title": "A publication with missing optional fields."
                  }
                ]
              }
            }
            """;

    // Page 2 for the same query, using the cursorMark returned by page 1. No nextCursorMark:
    // this is the verified live end-of-pagination signal (absent, not repeated).
    private static final String PAGE_2 = """
            {
              "hitCount": 6,
              "resultList": {
                "result": [
                  {
                    "id": "21807879",
                    "source": "MED",
                    "title": "Another publication citing the same grant.",
                    "grantsList": { "grant": [
                      { "grantId": "82525064, 82273876", "agency": "NSFC" },
                      { "agency": "Agency-only reference with no id" }
                    ] }
                  }
                ]
              }
            }
            """;

    @Test
    void paginatesUntilLimitReached_andMapsFieldsDefensively() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo(startsWith(BASE_URL + "/search")))
                .andExpect(queryParam("cursorMark", "*"))
                .andExpect(queryParam("resultType", "core"))
                .andExpect(queryParam("pageSize", "3"))
                .andRespond(withSuccess(PAGE_1, MediaType.APPLICATION_JSON));

        server.expect(requestTo(startsWith(BASE_URL + "/search")))
                .andExpect(queryParam("cursorMark", "AoIIPwJNzSgyODkzNjEyMg%3D%3D"))
                .andRespond(withSuccess(PAGE_2, MediaType.APPLICATION_JSON));

        EuropePmcArticleClientImpl client =
                new EuropePmcArticleClientImpl(builder.build(), new ObjectMapper(), noRetry());

        ArticleSearchResult result = client.search("GRANT_ID:083611", 3);

        server.verify();
        assertThat(result.totalHitCount()).isEqualTo(6);
        assertThat(result.publications()).hasSize(3);

        Publication first = result.publications().get(0);
        assertThat(first.title()).isEqualTo("Cell behaviour during neurogenesis.");
        assertThat(first.authors()).containsExactly("Storey KG");
        assertThat(first.journalTitle()).isEqualTo("Dev Biol");
        assertThat(first.reportedFunding()).hasSize(1);
        assertThat(first.reportedFunding().get(0).grantId()).isEqualTo("083611");

        Publication sparse = result.publications().get(1);
        assertThat(sparse.abstractText()).isNull();
        assertThat(sparse.authors()).isEmpty();
        assertThat(sparse.reportedFunding()).isEmpty();

        Publication third = result.publications().get(2);
        assertThat(third.reportedFunding()).hasSize(3);
        assertThat(third.reportedFunding().get(0).grantId()).isEqualTo("82525064");
        assertThat(third.reportedFunding().get(0).rawGrantIdField()).isEqualTo("82525064, 82273876");
        assertThat(third.reportedFunding().get(1).grantId()).isEqualTo("82273876");
        assertThat(third.reportedFunding().get(2).grantId()).isNull();
        assertThat(third.reportedFunding().get(2).agency()).isEqualTo("Agency-only reference with no id");
    }

    // A second-page fixture with no nextCursorMark, used to prove termination is driven by the
    // absent field rather than by coincidentally reaching the requested limit.
    private static final String PAGE_2_FINAL = """
            {
              "hitCount": 3,
              "resultList": {
                "result": [
                  { "id": "21807879", "source": "MED", "title": "Third result, from page two." }
                ]
              }
            }
            """;

    // Explicitly proves the multi-page chain end-to-end: page 1 (2 results + nextCursorMark) ->
    // page 2, requested with that exact cursorMark (1 result, no nextCursorMark) -> pagination
    // stops because nextCursorMark is absent, NOT because the limit (10, deliberately higher than
    // the 3 total available results) was reached. Both pages' results must appear in the output,
    // and no third request may be made (MockRestServiceServer.verify() below fails otherwise).
    @Test
    void fetchesSecondPage_usingCursorMarkFromFirstPage_andStopsWhenNextCursorMarkIsAbsent() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo(startsWith(BASE_URL + "/search")))
                .andExpect(queryParam("cursorMark", "*"))
                .andRespond(withSuccess(PAGE_1, MediaType.APPLICATION_JSON));

        server.expect(requestTo(startsWith(BASE_URL + "/search")))
                .andExpect(queryParam("cursorMark", "AoIIPwJNzSgyODkzNjEyMg%3D%3D"))
                .andRespond(withSuccess(PAGE_2_FINAL, MediaType.APPLICATION_JSON));

        EuropePmcArticleClientImpl client =
                new EuropePmcArticleClientImpl(builder.build(), new ObjectMapper(), noRetry());

        ArticleSearchResult result = client.search("GRANT_ID:083611", 10);

        server.verify();
        assertThat(result.totalHitCount()).isEqualTo(6);
        assertThat(result.publications()).hasSize(3);
        assertThat(result.publications())
                .extracting(p -> p.id().id())
                .containsExactly("24408437", "22525126", "21807879");
    }

    // Verified live: Europe PMC returns HTTP 200 with an embedded {"errCode":...} body for
    // malformed queries, rather than a non-2xx status — so a successful-looking response has to be
    // inspected for this shape. Such an error must also never be retried, since retrying a
    // malformed query cannot make it succeed; the client is built here with retries *enabled*, and
    // MockRestServiceServer.verify() fails if a second request is made.
    @Test
    void throwsQueryException_withoutRetrying_whenEuropePmcRespondsWithEmbeddedErrorCode() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo(startsWith(BASE_URL + "/search")))
                .andRespond(withSuccess(
                        "{\"errCode\":404,\"errMsg\":\"No search criteria provided.\"}",
                        MediaType.APPLICATION_JSON));

        EuropePmcArticleClientImpl client =
                new EuropePmcArticleClientImpl(builder.build(), new ObjectMapper(), withRetry());

        assertThatThrownBy(() -> client.search("", 10))
                .isInstanceOf(EuropePmcQueryException.class)
                .hasMessageContaining("No search criteria provided");
        server.verify();
    }

    @Test
    void throwsUpstreamException_onTransportFailure_afterExhaustingRetries() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        // withRetry() allows 2 attempts; both must fail for the exception to surface, and both
        // requests are asserted by server.verify() below.
        server.expect(requestTo(startsWith(BASE_URL + "/search"))).andRespond(withServerError());
        server.expect(requestTo(startsWith(BASE_URL + "/search"))).andRespond(withServerError());

        EuropePmcArticleClientImpl client =
                new EuropePmcArticleClientImpl(builder.build(), new ObjectMapper(), withRetry());

        assertThatThrownBy(() -> client.search("malaria", 10))
                .isInstanceOf(EuropePmcUpstreamException.class);
        server.verify();
    }

    // A single page with no nextCursorMark at all: Europe PMC's verified signal that all results
    // have been exhausted, even though fewer publications were returned than the caller's limit.
    private static final String SINGLE_EXHAUSTED_PAGE = """
            {
              "hitCount": 2,
              "resultList": {
                "result": [
                  { "id": "1", "source": "MED", "title": "Only result one." },
                  { "id": "2", "source": "MED", "title": "Only result two." }
                ]
              }
            }
            """;

    // Proves the retry actually recovers a transient failure rather than only being able to fail:
    // the first attempt for page 1 errors, the retry against the identical request succeeds.
    @Test
    void recoversFromTransientFailure_onRetry() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo(startsWith(BASE_URL + "/search")))
                .andExpect(queryParam("cursorMark", "*"))
                .andRespond(withServerError());
        server.expect(requestTo(startsWith(BASE_URL + "/search")))
                .andExpect(queryParam("cursorMark", "*"))
                .andRespond(withSuccess(SINGLE_EXHAUSTED_PAGE, MediaType.APPLICATION_JSON));

        EuropePmcArticleClientImpl client =
                new EuropePmcArticleClientImpl(builder.build(), new ObjectMapper(), withRetry());

        ArticleSearchResult result = client.search("GRANT_ID:083611", 25);

        server.verify();
        assertThat(result.partial()).isFalse();
        assertThat(result.publications()).hasSize(2);
    }

    // If Europe PMC becomes unavailable only after at least one page has already been fetched
    // successfully, previously-fetched publications must still be returned (marked partial) rather
    // than the whole request failing and discarding them.
    @Test
    void returnsPartialResults_whenAPageFailsAfterEarlierPagesSucceeded() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo(startsWith(BASE_URL + "/search")))
                .andExpect(queryParam("cursorMark", "*"))
                .andRespond(withSuccess(PAGE_1, MediaType.APPLICATION_JSON));

        // Both retry attempts for page 2 fail, exhausting the retry budget.
        server.expect(requestTo(startsWith(BASE_URL + "/search")))
                .andExpect(queryParam("cursorMark", "AoIIPwJNzSgyODkzNjEyMg%3D%3D"))
                .andRespond(withServerError());
        server.expect(requestTo(startsWith(BASE_URL + "/search")))
                .andExpect(queryParam("cursorMark", "AoIIPwJNzSgyODkzNjEyMg%3D%3D"))
                .andRespond(withServerError());

        EuropePmcArticleClientImpl client =
                new EuropePmcArticleClientImpl(builder.build(), new ObjectMapper(), withRetry());

        ArticleSearchResult result = client.search("GRANT_ID:083611", 10);

        server.verify();
        assertThat(result.partial()).isTrue();
        assertThat(result.partialReason()).contains("2 of the requested 10");
        assertThat(result.totalHitCount()).isEqualTo(6);
        assertThat(result.publications()).hasSize(2);
        assertThat(result.publications())
                .extracting(p -> p.id().id())
                .containsExactly("24408437", "22525126");
    }
}
