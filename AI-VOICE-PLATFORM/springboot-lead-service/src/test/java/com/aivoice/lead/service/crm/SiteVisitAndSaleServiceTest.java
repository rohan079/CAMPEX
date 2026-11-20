package com.aivoice.lead.service.crm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aivoice.lead.dto.SaleDtos.CancelSale;
import com.aivoice.lead.dto.SaleDtos.ConfirmSale;
import com.aivoice.lead.dto.SaleDtos.CreateSale;
import com.aivoice.lead.dto.SaleDtos.UpdateSale;
import com.aivoice.lead.dto.SiteVisitDtos.CreateSiteVisit;
import com.aivoice.lead.dto.SiteVisitDtos.RescheduleRequest;
import com.aivoice.lead.dto.SiteVisitDtos.SiteVisitStatusUpdate;
import com.aivoice.lead.entity.LeadAttribution;
import com.aivoice.lead.entity.LeadAttributionTouch;
import com.aivoice.lead.entity.Sale;
import com.aivoice.lead.entity.SiteVisit;
import com.aivoice.lead.repository.ClientProjectRepository;
import com.aivoice.lead.repository.LeadAttributionRepository;
import com.aivoice.lead.repository.LeadAttributionTouchRepository;
import com.aivoice.lead.repository.SaleRepository;
import com.aivoice.lead.repository.SaleStatusHistoryRepository;
import com.aivoice.lead.repository.SiteVisitRepository;
import com.aivoice.lead.service.ClientAccessService.Permission;
import com.aivoice.lead.service.Exceptions.ConflictException;
import com.aivoice.lead.service.Exceptions.ForbiddenException;
import com.aivoice.lead.service.Exceptions.NotFoundException;
import com.aivoice.lead.service.tracking.TrackingEventCatalog.EventName;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SiteVisitAndSaleServiceTest {

    private final CrmFixture f = new CrmFixture();
    private final Map<UUID, SiteVisit> visitStore = new HashMap<>();
    private final Map<UUID, Sale> saleStore = new HashMap<>();
    private SiteVisitService visits;
    private SaleLifecycleService sales;
    private SaleStatusHistoryRepository saleHistory;

    @BeforeEach
    void setUp() {
        SiteVisitRepository visitRepo = mock(SiteVisitRepository.class);
        when(visitRepo.save(any())).thenAnswer(i -> {
            SiteVisit v = i.getArgument(0);
            if (v.getId() == null) v.setId(UUID.randomUUID());
            visitStore.put(v.getId(), v);
            return v;
        });
        when(visitRepo.findById(any())).thenAnswer(i -> Optional.ofNullable(visitStore.get(i.getArgument(0))));
        visits = new SiteVisitService(visitRepo, f.leads, f.agents, mock(ClientProjectRepository.class), f.followUps,
                f.crm, f.setup, f.conversions, f.access, f.clock);

        SaleRepository saleRepo = mock(SaleRepository.class);
        when(saleRepo.saveAndFlush(any())).thenAnswer(i -> {
            Sale s = i.getArgument(0);
            s.setId(UUID.randomUUID());
            saleStore.put(s.getId(), s);
            return s;
        });
        when(saleRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(saleRepo.findByIdAndDeletedAtIsNull(any())).thenAnswer(i -> Optional.ofNullable(saleStore.get(i.getArgument(0))));
        saleHistory = mock(SaleStatusHistoryRepository.class);
        LeadAttributionRepository attributions = mock(LeadAttributionRepository.class);
        LeadAttributionTouchRepository touches = mock(LeadAttributionTouchRepository.class);
        LeadAttributionTouch first = new LeadAttributionTouch();
        first.setId(UUID.randomUUID());
        first.setCampaignId("120200001");
        first.setAdId("120200009");
        LeadAttribution a = new LeadAttribution();
        a.setFirstTouchId(first.getId());
        when(attributions.findByLeadId(f.leadA.getId())).thenReturn(Optional.of(a));
        when(touches.findById(first.getId())).thenReturn(Optional.of(first));
        sales = new SaleLifecycleService(saleRepo, saleHistory, f.leads, attributions, touches, f.agents,
                mock(ClientProjectRepository.class), f.setup, f.crm, f.conversions, f.access, f.clock, "INR", f.props);
    }

    @Test
    void siteVisitLifecycleAdvancesPipelineAndEmitsEvents() {
        var created = visits.create(f.leadA.getId(), new CreateSiteVisit(CrmFixture.NOW.plusSeconds(7200), null, null, null, null, "Bring plans"));
        assertEquals("SCHEDULED", created.status());
        assertEquals(CrmFixture.NOW.plusSeconds(7200 - 7200), created.reminderAt());
        assertEquals("SITE_VISIT_SCHEDULED", f.leadA.getPipelineStatus());
        verify(f.conversions).recordForLead(eq(f.leadA), eq(EventName.Schedule), eq("visit-" + created.id() + "-scheduled"), any(), anyMap());

        UUID visitId = UUID.fromString(created.id());
        assertThrows(IllegalArgumentException.class,
                () -> visits.updateStatus(visitId, new SiteVisitStatusUpdate("CANCELLED", null, null, null)));
        assertThrows(ConflictException.class,
                () -> visits.updateStatus(visitId, new SiteVisitStatusUpdate("COMPLETED", "interested", null, null)));
        visitStore.get(visitId).setScheduledAt(CrmFixture.NOW.minusSeconds(600));
        visits.updateStatus(visitId, new SiteVisitStatusUpdate("CONFIRMED", null, null, null));
        assertTrue(visitStore.get(visitId).isCustomerConfirmed());
        var done = visits.updateStatus(visitId, new SiteVisitStatusUpdate("COMPLETED", "negotiation", "Liked it", null));
        assertEquals("COMPLETED", done.status());
        assertNotNull(done.completedAt());
        assertEquals("NEGOTIATION", f.leadA.getPipelineStatus());
        verify(f.conversions).recordForLead(eq(f.leadA), eq(EventName.SiteVisitCompleted), any(), any(), anyMap());
        assertThrows(ConflictException.class,
                () -> visits.updateStatus(visitId, new SiteVisitStatusUpdate("NO_SHOW", null, null, null)));
    }

    @Test
    void rescheduleKeepsTheOldVisitAndLinksTheNewOne() {
        var created = visits.create(f.leadA.getId(), new CreateSiteVisit(CrmFixture.NOW.plusSeconds(7200), null, null, 45, 30, null));
        var moved = visits.reschedule(UUID.fromString(created.id()), new RescheduleRequest(CrmFixture.NOW.plusSeconds(86400), "Rain", null));
        assertEquals("RESCHEDULED", visitStore.get(UUID.fromString(created.id())).getStatus());
        assertEquals(created.id(), moved.rescheduledFromId());
        assertEquals(45, moved.durationMinutes());
        assertEquals(CrmFixture.NOW.plusSeconds(86400 - 1800), moved.reminderAt());
    }

    @Test
    void anotherTenantCannotScheduleVisits() {
        assertThrows(NotFoundException.class, () -> visits.create(f.leadB.getId(),
                new CreateSiteVisit(CrmFixture.NOW.plusSeconds(3600), null, null, null, null, null)));
    }

    @Test
    void saleInheritsFirstTouchCampaignAndRevenueNeedsConfirmation() {
        var sale = sales.create(f.clientA, new CreateSale(f.leadA.getId().toString(), null, null, null, null, null, null,
                new BigDecimal("7500000"), new BigDecimal("500000"), "BOOKED", null, null));
        assertEquals("120200001", sale.campaignId());
        assertEquals("120200009", sale.adId());
        assertEquals(0, sale.confirmedRevenue().signum());
        assertEquals(new BigDecimal("7000000"), sale.outstandingAmount());

        UUID id = UUID.fromString(sale.id());
        assertThrows(IllegalArgumentException.class, () -> sales.update(id, new UpdateSale(null, null, null, null, null,
                null, null, null, null, "FULLY_PAID", null, null, null)));
        var confirmed = sales.confirm(id, new ConfirmSale(new BigDecimal("7500000"), null));
        assertEquals("FULLY_PAID", confirmed.status());
        assertEquals(new BigDecimal("7500000"), confirmed.confirmedRevenue());
        assertEquals("WON", f.leadA.getPipelineStatus());
        verify(f.conversions).recordForLead(eq(f.leadA), eq(EventName.Purchase), eq("sale-" + id + "-purchase"), any(), anyMap());

        var cancelled = sales.cancel(id, new CancelSale("Loan rejected"));
        assertEquals("CANCELLED", cancelled.status());
        assertEquals(0, cancelled.confirmedRevenue().signum());
        assertThrows(ConflictException.class, () -> sales.cancel(id, new CancelSale("again")));
        verify(saleHistory, org.mockito.Mockito.atLeast(3)).save(any());
    }

    @Test
    void confirmationNeedsABookingCollectedMoneyAndManagerRole() {
        var draft = sales.create(f.clientA, new CreateSale(null, "999", null, null, null, null, "walk_in",
                new BigDecimal("100"), null, null, null, null));
        assertEquals("NEW", draft.status());
        assertThrows(ConflictException.class, () -> sales.confirm(UUID.fromString(draft.id()), null));
        var booked = sales.create(f.clientA, new CreateSale(null, null, null, null, null, null, null,
                new BigDecimal("100"), null, "BOOKED", null, null));
        assertThrows(ConflictException.class, () -> sales.confirm(UUID.fromString(booked.id()), null));
        when(f.access.require(f.clientA, Permission.MANAGE)).thenThrow(new ForbiddenException("role"));
        assertThrows(ForbiddenException.class,
                () -> sales.confirm(UUID.fromString(booked.id()), new ConfirmSale(new BigDecimal("50"), null)));
        verify(f.conversions, never()).recordForLead(any(), eq(EventName.Purchase), any(), any(), anyMap());
    }

    @Test
    void salesForAnotherTenantsLeadAreRejected() {
        assertThrows(NotFoundException.class, () -> sales.create(f.clientA, new CreateSale(f.leadB.getId().toString(),
                null, null, null, null, null, null, new BigDecimal("1"), null, null, null, null)));
        assertThrows(NotFoundException.class, () -> sales.create(f.clientB, new CreateSale(null,
                null, null, null, null, null, null, new BigDecimal("1"), null, null, null, null)));
    }
}
