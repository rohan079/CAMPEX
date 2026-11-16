package com.aivoice.lead.config;

import com.aivoice.lead.service.Exceptions.InvalidPhoneException;
import com.aivoice.lead.service.Exceptions.MessageDeliveryException;
import com.aivoice.lead.service.Exceptions.NotFoundException;
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
