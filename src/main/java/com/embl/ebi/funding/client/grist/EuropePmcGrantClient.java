package com.embl.ebi.funding.client.grist;

import java.util.List;

/**
 * Client for the Europe PMC Grants (GRIST) API.
 *
 * <p>Kept as an interface so tests never depend on the live GRIST service.
 */
public interface EuropePmcGrantClient {

    /**
     * Looks up all GRIST grant records matching the given grant identifier exactly
     * (GRIST {@code gid:} field search). Returns an empty list if there is no match — GRIST
     * represents "no match" as {@code HitCount: 0} with an empty record list, verified live,
     * never as an HTTP error.
     */
    List<GristRecord> findByGrantId(String grantId);
}
