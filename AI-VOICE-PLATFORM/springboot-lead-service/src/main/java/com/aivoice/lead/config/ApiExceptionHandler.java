package com.aivoice.lead.config;

import com.aivoice.lead.service.Exceptions.InvalidPhoneException;
import com.aivoice.lead.service.Exceptions.MessageDeliveryException;
import com.aivoice.lead.service.Exceptions.NotFoundException;
import com.aivoice.lead.service.AdminSessionService.AuthException;
import com.aivoice.lead.service.MetaGraphClient.MetaGraphException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Errors are {"detail": "..."} so the dashboard shows the same text the old API returned. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({InvalidPhoneException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(NotFoundException e) {
        return body(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<Map<String, String>> authentication(AuthException e) {
        return body(e.status(), e.getMessage());
    }

    @ExceptionHandler(MetaGraphException.class)
    public ResponseEntity<Map<String, String>> metaGraph(MetaGraphException e) {
        log.warn("Meta Graph request failed during a user action (HTTP {}, code {})", e.httpStatus(), e.graphCode());
        String detail = e.providerMessage() != null && !e.providerMessage().isBlank()
                ? e.providerMessage()
                : "Meta could not complete the requested account operation";
        return body(HttpStatus.BAD_GATEWAY, detail);
    }

    @ExceptionHandler(MessageDeliveryException.class)
    public ResponseEntity<Map<String, String>> badGateway(MessageDeliveryException e) {
        log.warn("WhatsApp delivery failed", e);
        return body(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, String>> invalidBody(Exception e) {
        return body(HttpStatus.BAD_REQUEST, "Invalid request body");
    }

    private ResponseEntity<Map<String, String>> body(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(Map.of("detail", detail == null ? status.getReasonPhrase() : detail));
    }
}
