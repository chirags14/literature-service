package com.embl.ebi.funding.client.articles;

import com.embl.ebi.funding.client.articles.dto.ArticleResultDto;
import com.embl.ebi.funding.domain.FundingReference;
import com.embl.ebi.funding.domain.Publication;
import com.embl.ebi.funding.domain.PublicationId;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps raw Europe PMC Articles API results onto our internal {@link Publication} domain model.
 *
 * <p>Defensive by construction: every accessor tolerates a missing nested object (Europe PMC does
 * not guarantee {@code authorList}, {@code journalInfo}, {@code abstractText} or
 * {@code grantsList} to be present for every publication).
 */
public final class ArticleMapper {

    private ArticleMapper() {
    }

    public static Publication toDomain(ArticleResultDto dto) {
        return new Publication(
                new PublicationId(dto.source(), dto.id()),
                dto.title(),
                extractAuthors(dto),
                extractJournalTitle(dto),
                extractPublicationDate(dto),
                extractPubYear(dto),
                dto.abstractText(),
                dto.citedByCount(),
                dto.doi(),
                dto.pmid(),
                dto.pmcid(),
                extractFundingReferences(dto)
        );
    }

    private static List<String> extractAuthors(ArticleResultDto dto) {
        if (dto.authorList() != null && dto.authorList().author() != null) {
            List<String> names = new ArrayList<>();
            for (var author : dto.authorList().author()) {
                if (author.fullName() != null && !author.fullName().isBlank()) {
                    names.add(author.fullName());
                }
            }
            if (!names.isEmpty()) {
                return names;
            }
        }
        if (dto.authorString() != null && !dto.authorString().isBlank()) {
            return List.of(dto.authorString().split(",\\s*"));
        }
        return List.of();
    }

    private static String extractJournalTitle(ArticleResultDto dto) {
        if (dto.journalInfo() != null && dto.journalInfo().journal() != null) {
            return dto.journalInfo().journal().title();
        }
        return null;
    }

    private static String extractPublicationDate(ArticleResultDto dto) {
        if (dto.journalInfo() != null && dto.journalInfo().dateOfPublication() != null) {
            return dto.journalInfo().dateOfPublication();
        }
        return dto.firstPublicationDate();
    }

    private static Integer extractPubYear(ArticleResultDto dto) {
        if (dto.journalInfo() != null && dto.journalInfo().yearOfPublication() != null) {
            return dto.journalInfo().yearOfPublication();
        }
        return dto.pubYear();
    }

    /**
     * Real Europe PMC data observed during investigation can pack more than one grant identifier
     * into a single {@code grantId} string, comma-separated (e.g. {@code "82525064, 82273876"}).
     * Each identifier is split into its own {@link FundingReference} so that resolution treats
     * them as independent candidates, while {@code rawGrantIdField} preserves the original text
     * for traceability back to the source data.
     */
    private static List<FundingReference> extractFundingReferences(ArticleResultDto dto) {
        if (dto.grantsList() == null || dto.grantsList().grant() == null) {
            return List.of();
        }
        List<FundingReference> references = new ArrayList<>();
        for (var grant : dto.grantsList().grant()) {
            String rawGrantId = grant.grantId();
            String agency = grant.agency();
            if (rawGrantId == null || rawGrantId.isBlank()) {
                references.add(FundingReference.of(null, agency));
                continue;
            }
            String[] parts = rawGrantId.split(",");
            if (parts.length <= 1) {
                references.add(FundingReference.of(rawGrantId, agency));
            } else {
                for (String part : parts) {
                    String trimmed = part.trim();
                    if (!trimmed.isEmpty()) {
                        references.add(new FundingReference(trimmed, agency, rawGrantId));
                    }
                }
            }
        }
        return references;
    }
}
