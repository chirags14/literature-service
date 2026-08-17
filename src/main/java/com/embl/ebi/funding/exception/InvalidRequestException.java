package com.embl.ebi.funding.exception;

/** Thrown for invalid input to our own API (blank query, malformed/out-of-range limit). */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
