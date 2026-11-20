package com.aivoice.lead.service.crm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aivoice.lead.entity.PipelineStatus;
import com.aivoice.lead.entity.Sale;
import com.aivoice.lead.entity.SaleStatus;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class StatusMachinesTest {

    @Test
    void pipelineTransitions() {
        assertTrue(PipelineStatus.NEW.canMoveTo(PipelineStatus.CONTACTED));
        assertTrue(PipelineStatus.SITE_VISIT_COMPLETED.canMoveTo(PipelineStatus.WON));
        assertFalse(PipelineStatus.NEW.canMoveTo(PipelineStatus.WON));
        assertFalse(PipelineStatus.WON.canMoveTo(PipelineStatus.NEW));
        assertTrue(PipelineStatus.LOST.canMoveTo(PipelineStatus.CONTACTED));
        assertTrue(PipelineStatus.CONTACTED.isBefore(PipelineStatus.QUALIFIED));
        assertFalse(PipelineStatus.NEGOTIATION.isBefore(PipelineStatus.SITE_VISIT_SCHEDULED));
        assertThrows(IllegalArgumentException.class, () -> PipelineStatus.parse("BOGUS"));
    }

    @Test
    void saleTransitions() {
        assertTrue(SaleStatus.NEW.canMoveTo(SaleStatus.BOOKED));
        assertTrue(SaleStatus.BOOKED.canMoveTo(SaleStatus.PARTIALLY_PAID));
        assertFalse(SaleStatus.NEW.canMoveTo(SaleStatus.FULLY_PAID));
        assertFalse(SaleStatus.REFUNDED.canMoveTo(SaleStatus.BOOKED));
        assertFalse(SaleStatus.CANCELLED.canMoveTo(SaleStatus.BOOKED));
        assertTrue(SaleStatus.CANCELLED.canMoveTo(SaleStatus.REFUNDED));
        assertFalse(SaleStatus.BOOKING_PENDING.isBooked());
    }

    private static Sale sale(String status, String booking, String collected, String refunded, boolean confirmed) {
        Sale s = new Sale();
        s.setStatus(status);
        s.setBookingValue(new BigDecimal(booking));
        s.setAmountCollected(new BigDecimal(collected));
        s.setRefundedAmount(new BigDecimal(refunded));
        s.setSaleConfirmedAt(confirmed ? Instant.now() : null);
        return s;
    }

    @Test
    void bookingIsNeverRevenueUntilConfirmed() {
        Sale booked = sale("BOOKED", "1000", "200", "0", false);
        SaleLifecycleService.recalculate(booked);
        assertEquals(0, booked.getConfirmedRevenue().signum());
        assertEquals(new BigDecimal("800"), booked.getOutstandingAmount());
        Sale confirmed = sale("PARTIALLY_PAID", "1000", "200", "50", true);
        SaleLifecycleService.recalculate(confirmed);
        assertEquals(new BigDecimal("150"), confirmed.getConfirmedRevenue());
        Sale cancelled = sale("CANCELLED", "1000", "200", "0", true);
        SaleLifecycleService.recalculate(cancelled);
        assertEquals(0, cancelled.getConfirmedRevenue().signum());
        assertEquals(0, cancelled.getOutstandingAmount().signum());
    }

    @Test
    void amountsMustMatchTheStatus() {
        assertThrows(IllegalArgumentException.class, () -> SaleLifecycleService.validateAmounts(sale("FULLY_PAID", "1000", "500", "0", false)));
        assertThrows(IllegalArgumentException.class, () -> SaleLifecycleService.validateAmounts(sale("PARTIALLY_PAID", "1000", "0", "0", false)));
        assertThrows(IllegalArgumentException.class, () -> SaleLifecycleService.validateAmounts(sale("BOOKED", "1000", "1200", "0", false)));
        assertThrows(IllegalArgumentException.class, () -> SaleLifecycleService.validateAmounts(sale("BOOKED", "1000", "100", "200", false)));
        assertThrows(IllegalArgumentException.class, () -> SaleLifecycleService.validateAmounts(sale("REFUNDED", "1000", "100", "0", false)));
        SaleLifecycleService.validateAmounts(sale("FULLY_PAID", "1000", "1000", "0", false));
    }
}
