package com.embl.ebi.funding.client.grist;

import com.embl.ebi.funding.client.RetrySupport;
import com.embl.ebi.funding.config.EuropePmcProperties;
import com.embl.ebi.funding.exception.EuropePmcUpstreamException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriUtils;

/**
 * Live implementation of {@link EuropePmcGrantClient} against the Europe PMC Grants (GRIST) API.
 *
 * <p>Two quirks verified live during API investigation drive the shape of this class:
 * <ul>
 *   <li>The GRIST URL syntax is {@code .../get/query=<value>&param=...} with <b>no leading '?'</b>
 *   — a standard {@code ?query=} query string returns HTTP 404. The URI is therefore built as a
 *   raw string rather than via Spring's query-parameter builder, which would otherwise re-encode
 *   the request into the non-working form.</li>
 *   <li>The JSON response is XML-derived: {@code RecordList.Record} is a single JSON object when
 *   exactly one grant matches, and a JSON array when more than one matches. This is parsed
 *   manually via a {@link JsonNode} tree rather than a fixed-shape POJO.</li>
 * </ul>
 *
 * <p><b>Fault tolerance:</b> a transient transport failure gets a small bounded retry (see
 * {@link RetrySupport}). If it still fails, the failure propagates to
 * {@code GrantResolutionService}, which treats that one grant id as unresolved with an honest
 * reason rather than failing the whole publication search — see that class for why a single grant
 * lookup being down must never take down an otherwise-successful request.
 */
@Component
public class EuropePmcGrantClientImpl implements EuropePmcGrantClient {

    private static final Logger log = LoggerFactory.getLogger(EuropePmcGrantClientImpl.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final int retryAttempts;
    private final long retryDelayMs;

    public EuropePmcGrantClientImpl(@Qualifier("gristRestClient") RestClient restClient,
                                     ObjectMapper objectMapper,
                                     EuropePmcProperties properties) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.baseUrl = properties.grist().baseUrl();
        this.retryAttempts = Math.max(1, properties.retryAttempts());
        this.retryDelayMs = Math.max(0, properties.retryDelayMs());
    }

    @Override
    public List<GristRecord> findByGrantId(String grantId) {
        return RetrySupport.withRetry(retryAttempts, retryDelayMs,
                () -> findByGrantIdOnce(grantId),
                (attempt, e) -> log.warn("Europe PMC Grants API transient failure for grant '{}' (attempt {}), retrying: {}",
                        grantId, attempt, e.getMessage()));
    }

    private List<GristRecord> findByGrantIdOnce(String grantId) {
        URI uri = buildLookupUri(grantId);
        String rawBody;
        try {
            rawBody = restClient.get().uri(uri).retrieve().body(String.class);
        } catch (RestClientException e) {
            throw new EuropePmcUpstreamException("Failed to reach Europe PMC Grants API: " + e.getMessage(), e);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            throw new EuropePmcUpstreamException("Europe PMC Grants API returned an unparseable response", e);
        }

        return extractRecords(root);
    }

    private URI buildLookupUri(String grantId) {
        String encodedTerm = UriUtils.encode("gid:" + grantId, StandardCharsets.UTF_8);
        String url = baseUrl + "/query=" + encodedTerm + "&format=json&resultType=core";
        return URI.create(url);
    }

    private List<GristRecord> extractRecords(JsonNode root) {
        JsonNode recordNode = root.path("RecordList").path("Record");
        if (recordNode.isMissingNode() || recordNode.isNull()) {
            return List.of();
        }
        List<GristRecord> records = new ArrayList<>();
        if (recordNode.isArray()) {
            for (JsonNode node : recordNode) {
                records.add(toGristRecord(node));
            }
        } else if (recordNode.isObject() && recordNode.size() > 0) {
            records.add(toGristRecord(recordNode));
        }
        return records;
    }

    private GristRecord toGristRecord(JsonNode node) {
        JsonNode grant = node.path("Grant");
        JsonNode funder = grant.path("Funder");
        return new GristRecord(
                textOrNull(grant, "Id"),
                textOrNull(funder, "Name"),
                textOrNull(funder, "FundRefID"),
                textOrNull(grant, "Title"),
                textOrNull(grant, "StartDate"),
                textOrNull(grant, "EndDate")
        );
    }

    private String textOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asString() : null;
    }
}
