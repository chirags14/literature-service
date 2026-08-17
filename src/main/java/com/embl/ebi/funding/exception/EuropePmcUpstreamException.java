package com.embl.ebi.funding.exception;

/**
 * Thrown when a Europe PMC dependency (Articles or Grants API) cannot be reached or fails at the
 * transport level (timeout, connection error, 5xx). Distinguished from
 * {@link EuropePmcQueryException} because it reflects an upstream/operational problem rather than
 * a problem with the caller's input.
 */
public class EuropePmcUpstreamException extends RuntimeException {

    public EuropePmcUpstreamException(String message, Throwable cause) {
        super(message, cause);
    }

    public EuropePmcUpstreamException(String message) {
        super(message);
    }
}
