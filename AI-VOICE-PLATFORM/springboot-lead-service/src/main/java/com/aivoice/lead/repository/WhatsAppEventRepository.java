package com.aivoice.lead.repository;

import com.aivoice.lead.entity.WhatsAppEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WhatsAppEventRepository extends JpaRepository<WhatsAppEvent, Long> {

    boolean existsByMessageId(String messageId);
}
