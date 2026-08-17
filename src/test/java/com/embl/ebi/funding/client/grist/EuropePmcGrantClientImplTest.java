package com.embl.ebi.funding.client.grist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.embl.ebi.funding.config.EuropePmcProperties;
import com.embl.ebi.funding.exception.EuropePmcUpstreamException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Verifies GRIST response parsing against response shapes captured live during API investigation:
 * a single match returns {@code RecordList.Record} as a JSON object, multiple matches return it
 * as a JSON array, and no match returns an empty {@code RecordList} — all under HTTP 200.
 */
class EuropePmcGrantClientImplTest {

    private static final String BASE_URL = "https://www.ebi.ac.uk/europepmc/GristAPI/rest/get";

    // Real shape verified live for query=gid:083611&format=json&resultType=core.
    private static final String SINGLE_RECORD = """
            {
              "HitCount": "1",
              "RecordList": {
                "Record": {
                  "Grant": {
                    "Funder": { "Name": "Wellcome Trust", "FundRefID": "https://doi.org/10.13039/100010269" },
                    "Id": "083611",
                    "Title": "Cell behaviour and signalling dynamics during vertebrate neurogenesis.",
                    "StartDate": "2007-11-01",
                    "EndDate": "2013-10-31"
                  }
                }
              }
            }
            """;

    // Real shape verified live for query=gid:083611 OR gid:336677&format=json.
    private static final String MULTIPLE_RECORDS = """
            {
              "HitCount": "2",
              "RecordList": {
                "Record": [
                  { "Grant": { "Funder": { "Name": "Wellcome Trust" }, "Id": "336677", "Title": "DREAMS" } },
                  { "Grant": { "Funder": { "Name": "Academy of Finland" }, "Id": "336677", "Title": "Other award" } }
                ]
              }
            }
            """;

    private static final String NO_MATCH = """
            { "HitCount": "0", "RecordList": {} }
            """;

    @Test
    void parsesSingleRecord_whenExactlyOneMatch() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/query=gid%3A083611&format=json&resultType=core"))
                .andRespond(withSuccess(SINGLE_RECORD, MediaType.APPLICATION_JSON));

        EuropePmcGrantClientImpl client = new EuropePmcGrantClientImpl(
                builder.build(), new ObjectMapper(), propertiesWith(BASE_URL));

        List<GristRecord> records = client.findByGrantId("083611");

        server.verify();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).funderName()).isEqualTo("Wellcome Trust");
        assertThat(records.get(0).fundRefId()).isEqualTo("https://doi.org/10.13039/100010269");
    }

    @Test
    void parsesMultipleRecords_whenIdCollidesAcrossFunders() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/query=gid%3A336677&format=json&resultType=core"))
                .andRespond(withSuccess(MULTIPLE_RECORDS, MediaType.APPLICATION_JSON));

        EuropePmcGrantClientImpl client = new EuropePmcGrantClientImpl(
                builder.build(), new ObjectMapper(), propertiesWith(BASE_URL));

        List<GristRecord> records = client.findByGrantId("336677");

        server.verify();
        assertThat(records).hasSize(2);
        assertThat(records).extracting(GristRecord::funderName)
                .containsExactlyInAnyOrder("Wellcome Trust", "Academy of Finland");
    }

    @Test
    void returnsEmptyList_whenNoMatch() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/query=gid%3ANOPE&format=json&resultType=core"))
                .andRespond(withSuccess(NO_MATCH, MediaType.APPLICATION_JSON));

        EuropePmcGrantClientImpl client = new EuropePmcGrantClientImpl(
                builder.build(), new ObjectMapper(), propertiesWith(BASE_URL));

        List<GristRecord> records = client.findByGrantId("NOPE");

        server.verify();
        assertThat(records).isEmpty();
    }

    // A grant lookup is retried once on a transient 5xx before being given up on. Exhausting the
    // retries and surfacing an EuropePmcUpstreamException is the shared RetrySupport contract,
    // covered in EuropePmcArticleClientImplTest; what matters here is that this client is actually
    // wired into it, and that a recovered attempt parses normally.
    @Test
    void recoversFromTransientFailure_onRetry() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/query=gid%3A083611&format=json&resultType=core"))
                .andRespond(withServerError());
        server.expect(requestTo(BASE_URL + "/query=gid%3A083611&format=json&resultType=core"))
                .andRespond(withSuccess(SINGLE_RECORD, MediaType.APPLICATION_JSON));

        EuropePmcGrantClientImpl client = new EuropePmcGrantClientImpl(
                builder.build(), new ObjectMapper(), propertiesWith(BASE_URL, 2));

        List<GristRecord> records = client.findByGrantId("083611");

        server.verify();
        assertThat(records).hasSize(1);
    }

    private EuropePmcProperties propertiesWith(String gristBaseUrl) {
        return propertiesWith(gristBaseUrl, 1);
    }

    private EuropePmcProperties propertiesWith(String gristBaseUrl, int retryAttempts) {
        return new EuropePmcProperties(
                new EuropePmcProperties.Articles("https://example.invalid"),
                new EuropePmcProperties.Grist(gristBaseUrl),
                3000, 8000, 8, retryAttempts, 0);
    }
}
