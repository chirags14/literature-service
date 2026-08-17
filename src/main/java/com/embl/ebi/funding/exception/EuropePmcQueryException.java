package com.embl.ebi.funding.exception;

/**
 * Thrown when Europe PMC responds with {@code HTTP 200} but an embedded
 * {@code {"errCode": ..., "errMsg": ...}} body — verified live behaviour of the Articles API for
 * malformed queries (e.g. empty query, oversized query, invalid pageSize). Europe PMC does not
 * signal these with a non-2xx HTTP status, so this must be detected by inspecting the JSON body.
 * Treated as a client-input problem (mapped to HTTP 400) rather than an upstream failure, since it
 * stems from the query/limit supplied to our own API.
 */
public class EuropePmcQueryException extends RuntimeException {

    public EuropePmcQueryException(String message) {
        super(message);
    }
}
