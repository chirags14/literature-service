package com.embl.ebi.funding.web;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.embl.ebi.funding.exception.EuropePmcUpstreamException;
import com.embl.ebi.funding.service.LiteratureSearchService;
import com.embl.ebi.funding.web.dto.PublicationSearchResponse;
import com.embl.ebi.funding.web.dto.SummaryDto;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Input validation and error-mapping behaviour of the public endpoint.
 *
 * <p>Built with a standalone {@link MockMvc} (controller + advice wired directly, no Spring
 * context) rather than a {@code @WebMvcTest} slice, since this Spring Boot version does not ship
 * the web MVC test-slice auto-configuration. The service dependency is a plain Mockito mock, so no
 * Europe PMC dependency is ever involved.
 */
class PublicationControllerTest {

    private LiteratureSearchService literatureSearchService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        literatureSearchService = mock(LiteratureSearchService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PublicationController(literatureSearchService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void returnsOk_andPassesTheRequestedLimitThrough() throws Exception {
        PublicationSearchResponse response = new PublicationSearchResponse("malaria", 10, List.of(),
                new SummaryDto(0, 0, 0, 0, 0, 0, List.of(), List.of()), List.of());
        when(literatureSearchService.search(eq("malaria"), anyInt())).thenReturn(response);

        mockMvc.perform(get("/publications").param("query", "malaria").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("malaria"));

        verify(literatureSearchService).search("malaria", 10);

        // The default applies when no limit is supplied.
        mockMvc.perform(get("/publications").param("query", "malaria")).andExpect(status().isOk());
        verify(literatureSearchService).search("malaria", 25);
    }

    // Every way a request can be invalid must come back as a 400 with the JSON error shape, and
    // must never reach the search service — an invalid request should cost zero Europe PMC calls.
    @Test
    void rejectsInvalidRequests_withoutCallingTheSearchService() throws Exception {
        mockMvc.perform(get("/publications"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));

        for (String blankQuery : List.of("", "   ")) {
            mockMvc.perform(get("/publications").param("query", blankQuery))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("bad_request"));
        }

        // Non-numeric, zero/negative, and above the self-imposed maximum.
        for (String badLimit : List.of("abc", "0", "-1", "10000")) {
            mockMvc.perform(get("/publications").param("query", "malaria").param("limit", badLimit))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("bad_request"));
        }

        verifyNoInteractions(literatureSearchService);
    }

    @Test
    void mapsUpstreamFailureToBadGateway() throws Exception {
        when(literatureSearchService.search(eq("malaria"), anyInt()))
                .thenThrow(new EuropePmcUpstreamException("Europe PMC is unreachable"));

        mockMvc.perform(get("/publications").param("query", "malaria"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("upstream_unavailable"));
    }
}
