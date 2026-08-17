package com.embl.ebi.funding.domain;

/**
 * Identity of a publication as returned by Europe PMC.
 *
 * <p>Europe PMC aggregates content from multiple sources (MED, PMC, PPR, ...). The bare
 * {@code id} field is only guaranteed unique <em>within</em> a source, so publication identity
 * must be the {@code (source, id)} pair, not the {@code id} alone. This was confirmed against
 * live Europe PMC Articles API responses during API investigation.
 */
public record PublicationId(String source, String id) {

    public String asString() {
        return source + ":" + id;
    }
}
