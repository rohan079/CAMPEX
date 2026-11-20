package com.aivoice.lead.service.crm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.aivoice.lead.dto.CrmDtos.ActivityRequest;
import com.aivoice.lead.dto.CrmDtos.AssignRequest;
import com.aivoice.lead.dto.CrmDtos.FollowUpRequest;
import com.aivoice.lead.dto.CrmDtos.StatusChangeRequest;
import com.aivoice.lead.entity.LeadAssignment;
import com.aivoice.lead.entity.LeadStatusHistory;
import com.aivoice.lead.entity.PipelineStatus;
import com.aivoice.lead.service.Exceptions.ConflictException;
import com.aivoice.lead.service.Exceptions.NotFoundException;
import com.aivoice.lead.service.tracking.TrackingEventCatalog.EventName;
import com.aivoice.lead.service.tracking.TrackingEventCatalog.Source;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CrmServiceTest {

    private final CrmFixture f = new CrmFixture();

    @Test
    void assignmentRecordsAgentHistoryAndReassignment() {
        f.crm.assign(f.leadA.getId(), new AssignRequest(f.agent.getId().toString(), "Territory"));
        assertEquals(f.agent.getId(), f.leadA.getAssignedAgentId());
        ArgumentCaptor<LeadAssignment> saved = ArgumentCaptor.forClass(LeadAssignment.class);
        verify(f.assignments).save(saved.capture());
        assertNull(saved.getValue().getPreviousAgentId());
        assertEquals("agent@a.example", saved.getValue().getAssignedBy());
    }

    @Test
    void statusChangesFollowTheStateMachineAndWriteHistory() {
        f.crm.changeStatus(f.leadA.getId(), new StatusChangeRequest("CONTACTED", "Called", null));
        assertEquals("CONTACTED", f.leadA.getPipelineStatus());
        assertEquals(CrmFixture.NOW, f.leadA.getFirstContactedAt());
        ArgumentCaptor<LeadStatusHistory> h = ArgumentCaptor.forClass(LeadStatusHistory.class);
        verify(f.history).save(h.capture());
        assertEquals("NEW", h.getValue().getFromStatus());
        assertEquals("CONTACTED", h.getValue().getToStatus());
        verify(f.conversions).recordForLead(eq(f.leadA), eq(EventName.Contact), eq("lead-" + f.leadA.getId() + "-contact"),
                eq(Source.manual_crm), anyMap());

        assertThrows(ConflictException.class,
                () -> f.crm.changeStatus(f.leadA.getId(), new StatusChangeRequest("WON", null, null)));
        assertThrows(IllegalArgumentException.class,
                () -> f.crm.changeStatus(f.leadA.getId(), new StatusChangeRequest("LOST", null, " ")));

        f.crm.changeStatus(f.leadA.getId(), new StatusChangeRequest("QUALIFIED", null, null));
        verify(f.conversions).recordForLead(eq(f.leadA), eq(EventName.QualifiedLead), any(), any(), anyMap());
        f.crm.changeStatus(f.leadA.getId(), new StatusChangeRequest("LOST", null, "Bought elsewhere"));
        assertEquals("Bought elsewhere", f.leadA.getLostReason());
    }

    @Test
    void automaticMovesOnlyGoForward() {
        f.leadA.setPipelineStatus(PipelineStatus.NEGOTIATION.name());
        f.crm.transition(f.leadA, PipelineStatus.SITE_VISIT_SCHEDULED, "visit", null, "x", false);
        assertEquals("NEGOTIATION", f.leadA.getPipelineStatus());
        f.crm.transition(f.leadA, PipelineStatus.WON, "sale", null, "x", false);
        assertEquals("WON", f.leadA.getPipelineStatus());
    }

    @Test
    void loggingACallCountsAsFirstContact() {
        f.crm.addActivity(f.leadA.getId(), new ActivityRequest("call", "Spoke about 3BHK", null));
        assertEquals("CONTACTED", f.leadA.getPipelineStatus());
        f.crm.addActivity(f.leadA.getId(), new ActivityRequest("note", "Prefers evenings", null));
        assertEquals("CONTACTED", f.leadA.getPipelineStatus());
    }

    @Test
    void followUpsMustBeInTheFuture() {
        assertThrows(IllegalArgumentException.class, () -> f.crm.addFollowUp(f.leadA.getId(),
                new FollowUpRequest(CrmFixture.NOW.minusSeconds(3600), "call", null, null)));
        var dto = f.crm.addFollowUp(f.leadA.getId(), new FollowUpRequest(CrmFixture.NOW.plusSeconds(3600), "whatsapp", "Send brochure", null));
        assertEquals("pending", dto.status());
    }

    @Test
    void anotherTenantsLeadIsInvisible() {
        assertThrows(NotFoundException.class, () -> f.crm.detail(f.leadB.getId()));
        assertThrows(NotFoundException.class, () -> f.crm.changeStatus(f.leadB.getId(), new StatusChangeRequest("CONTACTED", null, null)));
        assertThrows(NotFoundException.class, () -> f.crm.assign(f.leadB.getId(), new AssignRequest(f.agent.getId().toString(), null)));
        assertThrows(NotFoundException.class, () -> f.crm.activities(f.leadB.getId()));
        assertThrows(NotFoundException.class, () -> f.crm.detail(UUID.randomUUID()));
        verify(f.history, never()).save(any());
    }

    @Test
    void agentsFromAnotherClientCannotBeAssigned() {
        org.mockito.Mockito.when(f.setup.requireAgent(eq(f.clientA), any())).thenThrow(new NotFoundException("Agent not found"));
        assertThrows(NotFoundException.class,
                () -> f.crm.assign(f.leadA.getId(), new AssignRequest(UUID.randomUUID().toString(), null)));
    }
}
