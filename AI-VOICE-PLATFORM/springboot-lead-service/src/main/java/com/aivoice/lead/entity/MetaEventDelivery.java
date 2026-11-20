package com.aivoice.lead.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One Conversions API attempt for a conversion event and what Meta answered. */
@Entity
@Table(name = "meta_event_deliveries")
@Getter
@Setter
@NoArgsConstructor
public class MetaEventDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "conversion_event_id")
    private UUID conversionEventId;

    @Column(name = "client_id")
    private UUID clientId;

    @Column(name = "attempt_number")
    private int attemptNumber;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "events_received")
    private Integer eventsReceived;

    @Column(name = "fbtrace_id")
    private String fbtraceId;

    @Column(name = "error_code")
    private Integer errorCode;

    @Column(name = "error_subcode")
    private Integer errorSubcode;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "retryable")
    private boolean retryable;

    @Column(name = "outcome")
    private String outcome;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_metadata", columnDefinition = "jsonb")
    private Map<String, Object> responseMetadata;

    @Column(name = "attempted_at")
    private Instant attemptedAt;
}
