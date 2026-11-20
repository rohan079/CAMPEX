package com.aivoice.lead.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aivoice.lead.config.AccessProperties;
import com.aivoice.lead.config.AppProperties;
import com.aivoice.lead.entity.ClientMember;
import com.aivoice.lead.repository.ClientMemberRepository;
import com.aivoice.lead.repository.ClientRepository;
import com.aivoice.lead.service.AdminSessionService.AdminIdentity;
import com.aivoice.lead.service.ClientAccessService.Permission;
import com.aivoice.lead.service.Exceptions.ForbiddenException;
import com.aivoice.lead.service.Exceptions.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class ClientAccessServiceTest {

    private final UUID client = UUID.randomUUID();
    private final ClientMemberRepository members = mock(ClientMemberRepository.class);
    private final ClientRepository clients = mock(ClientRepository.class);
    private final DashboardAuthService auth = mock(DashboardAuthService.class);

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    private ClientAccessService service(String env, boolean firebase, String signedIn, List<String> admins) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        when(auth.isConfigured()).thenReturn(firebase);
        when(auth.currentIdentity(any())).thenReturn(new AdminIdentity(signedIn, Instant.MAX));
        when(clients.existsById(client)).thenReturn(true);
        AppProperties app = new AppProperties(env, "A", "", "Asia/Kolkata", 7, 3, "", "", "", null, "http://v", 5, 1);
        return new ClientAccessService(auth, new AccessProperties(admins), app, members, clients);
    }

    private void member(String email, String role) {
        ClientMember m = new ClientMember();
        m.setClientId(client);
        m.setUserEmail(email);
        m.setRole(role);
        when(members.findByClientIdAndUserEmail(client, email)).thenReturn(Optional.of(m));
    }

    @Test
    void platformAdminsReachEveryClient() {
        var s = service("production", true, "Boss@Example.com", List.of("boss@example.com"));
        assertTrue(s.require(client, Permission.OWN).platformAdmin());
        assertTrue(s.readableClients().isEmpty());
    }

    @Test
    void nonMembersGetNotFoundSoTenantsCannotBeProbed() {
        var s = service("production", true, "stranger@example.com", List.of());
        when(members.findByClientIdAndUserEmail(any(), any())).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> s.read(client));
        assertThrows(NotFoundException.class, () -> s.read(UUID.randomUUID()));
    }

    @Test
    void rolesLimitWhatMembersCanDo() {
        var s = service("production", true, "viewer@example.com", List.of());
        member("viewer@example.com", "viewer");
        assertEquals("viewer@example.com", s.read(client).email());
        assertThrows(ForbiddenException.class, () -> s.require(client, Permission.OPERATE));
        assertTrue(ClientAccessService.roleAllows("agent", Permission.OPERATE));
        assertFalse(ClientAccessService.roleAllows("agent", Permission.MANAGE));
        assertTrue(ClientAccessService.roleAllows("manager", Permission.MANAGE));
        assertFalse(ClientAccessService.roleAllows("manager", Permission.OWN));
        assertFalse(ClientAccessService.roleAllows("unknown", Permission.READ));
    }

    @Test
    void developmentWithoutFirebaseIsAnAdminButProductionIsNot() {
        assertTrue(service("development", false, "x@y.z", List.of()).read(client).platformAdmin());
        assertThrows(AdminSessionService.AuthException.class,
                () -> service("production", false, "x@y.z", List.of()).read(client));
    }
}
