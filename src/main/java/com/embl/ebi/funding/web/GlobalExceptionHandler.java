package com.embl.ebi.funding.web;

import com.embl.ebi.funding.exception.EuropePmcQueryException;
import com.embl.ebi.funding.exception.EuropePmcUpstreamException;
import com.embl.ebi.funding.exception.InvalidRequestException;
import com.embl.ebi.funding.web.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Central mapping from exceptions to HTTP responses, so the "invalid input should result in an
 * appropriate HTTP response" requirement is satisfied in one place.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({InvalidRequestException.class, EuropePmcQueryException.class,
            MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception e) {
        return ResponseEntity.badRequest().body(new ErrorResponse("bad_request", e.getMessage()));
    }

    @ExceptionHandler(EuropePmcUpstreamException.class)
    public ResponseEntity<ErrorResponse> handleUpstreamFailure(EuropePmcUpstreamException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorResponse("upstream_unavailable",
                        "A Europe PMC dependency could not be reached: " + e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        // Anything reaching here is, by definition, a case none of the specific handlers above
        // anticipated — the client only gets a generic message, so the actual cause must be
        // logged here or it is lost entirely.
        log.error("Unexpected error handling request", e);
        return ResponseEntity.internalServerError()
                .body(new ErrorResponse("internal_error", "An unexpected error occurred"));
    }
}
