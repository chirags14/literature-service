package com.embl.ebi.funding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.embl.ebi.funding.client.articles.ArticleSearchResult;
import com.embl.ebi.funding.client.articles.EuropePmcArticleClient;
import com.embl.ebi.funding.domain.Publication;
import com.embl.ebi.funding.domain.PublicationId;
import com.embl.ebi.funding.service.GrantResolutionService.GrantResolutionOutcome;
import com.embl.ebi.funding.web.dto.PublicationSearchResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link LiteratureSearchService} is thin, but it owns one piece of behaviour that isn't covered
 * anywhere else: aggregating the two independent degraded-response signals — a partial article
 * search and failed grant lookups — into the single top-level {@code warnings} list a caller
 * actually sees. {@link GrantResolutionServiceTest} and {@link ResponseAssemblerTest} each verify
 * their own piece in isolation, against fakes/mocks so no Europe PMC dependency is ever involved
 * here either.
 */
class LiteratureSearchServiceTest {

    private EuropePmcArticleClient articleClient;
    private GrantResolutionService grantResolutionService;
    private ResponseAssembler responseAssembler;
    private LiteratureSearchService service;

    @BeforeEach
    void setUp() {
        articleClient = mock(EuropePmcArticleClient.class);
        grantResolutionService = mock(GrantResolutionService.class);
        responseAssembler = mock(ResponseAssembler.class);
        service = new LiteratureSearchService(articleClient, grantResolutionService, responseAssembler);

        when(responseAssembler.assemble(anyString(), anyInt(), anyLong(), anyList(), anyMap(), anyList()))
                .thenReturn(new PublicationSearchResponse("query", 25, List.of(), null, List.of()));
    }

    @Test
    void passesNoWarnings_whenSearchAndResolutionBothSucceedCleanly() {
        Publication publication = publicationWith("p1");
        when(articleClient.search("malaria", 25))
                .thenReturn(new ArticleSearchResult(List.of(publication), 1, false, null));
        when(grantResolutionService.resolve(List.of(publication)))
                .thenReturn(new GrantResolutionOutcome(Map.of(), 0));

        service.search("malaria", 25);

        assertThat(capturedWarnings()).isEmpty();
    }

    // Both degraded-response signals are independent, so the interesting case is both firing at
    // once: each must survive into the caller-visible warnings with its own detail intact.
    @Test
    void surfacesBothDegradedSignalsAsWarnings_whenSearchIsPartialAndLookupsFailed() {
        Publication publication = publicationWith("p1");
        when(articleClient.search("malaria", 25)).thenReturn(new ArticleSearchResult(
                List.of(publication), 1, true, "Europe PMC Articles API became unavailable after 1 result"));
        when(grantResolutionService.resolve(List.of(publication)))
                .thenReturn(new GrantResolutionOutcome(Map.of(), 3));

        service.search("malaria", 25);

        assertThat(capturedWarnings()).hasSize(2);
        assertThat(capturedWarnings().get(0))
                .isEqualTo("Europe PMC Articles API became unavailable after 1 result");
        assertThat(capturedWarnings().get(1))
                .startsWith("3 grant id lookup(s)")
                .contains("treated as unresolved");
    }

    private Publication publicationWith(String id) {
        return new Publication(new PublicationId("MED", id), "Title " + id, List.of(), null, null, null,
                null, null, null, null, null, List.of());
    }

    @SuppressWarnings("unchecked")
    private List<String> capturedWarnings() {
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(responseAssembler).assemble(anyString(), anyInt(), anyLong(), anyList(), anyMap(), captor.capture());
        return captor.getValue();
    }
}
