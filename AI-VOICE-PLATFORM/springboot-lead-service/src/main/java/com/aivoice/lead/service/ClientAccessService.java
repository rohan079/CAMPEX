package com.aivoice.lead.service;

import com.aivoice.lead.config.AccessProperties;
import com.aivoice.lead.config.AppProperties;
import com.aivoice.lead.entity.ClientMember;
import com.aivoice.lead.repository.ClientMemberRepository;
import com.aivoice.lead.repository.ClientRepository;
import com.aivoice.lead.service.AdminSessionService.AdminIdentity;
import com.aivoice.lead.service.Exceptions.ForbiddenException;
import com.aivoice.lead.service.Exceptions.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Tenant isolation for Phase 1 resources. Every client-scoped read or write calls {@link #require} with the
 * client that owns the resource (looked up server-side), never a client id taken on trust from the browser.
 */
@Service
public class ClientAccessService {

    /** What the caller wants to do. Each role grants a fixed set. */
    public enum Permission { READ, OPERATE, MANAGE, OWN }

    public record Actor(String email, boolean platformAdmin) {
    }

    static final String DEV_ACTOR = "development@localhost";

    private final DashboardAuthService auth;
    private final AccessProperties accessProperties;
    private final AppProperties appProperties;
    private final ClientMemberRepository members;
    private final ClientRepository clients;

    public ClientAccessService(DashboardAuthService auth, AccessProperties accessProperties,
                               AppProperties appProperties, ClientMemberRepository members,
                               ClientRepository clients) {
        this.auth = auth;
        this.accessProperties = accessProperties;
        this.appProperties = appProperties;
        this.members = members;
        this.clients = clients;
    }

    /** The signed-in dashboard user. In development without Firebase configured, a local admin actor. */
    public Actor currentActor() {
        HttpServletRequest request = currentRequest();
        if (!auth.isConfigured()) {
            if (appProperties.isDevelopment()) {
                return new Actor(DEV_ACTOR, true);
            }
            throw new AdminSessionService.AuthException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Firebase authentication is not configured");
        }
        AdminIdentity identity = auth.currentIdentity(request);
        String email = identity.email() == null ? "" : identity.email().trim().toLowerCase(Locale.ROOT);
        if (email.isEmpty()) {
            throw new ForbiddenException("Your account has no verified email address");
        }
        return new Actor(email, accessProperties.adminSet().contains(email));
    }

    /** Checks the current user may perform {@code permission} on {@code clientId}; 404 when the client is unknown. */
    @Transactional(readOnly = true)
    public Actor require(UUID clientId, Permission permission) {
        if (clientId == null || !clients.existsById(clientId)) {
            throw new NotFoundException("Client not found");
        }
        Actor actor = currentActor();
        if (actor.platformAdmin()) {
            return actor;
        }
        Optional<ClientMember> member = members.findByClientIdAndUserEmail(clientId, actor.email());
        if (member.isEmpty()) {
            // Same answer as "unknown client", so ids of other tenants cannot be probed.
            throw new NotFoundException("Client not found");
        }
        if (!roleAllows(member.get().getRole(), permission)) {
            throw new ForbiddenException("Your role on this client does not allow this action");
        }
        return actor;
    }

    public Actor read(UUID clientId) {
        return require(clientId, Permission.READ);
    }

    static boolean roleAllows(String role, Permission permission) {
        Set<Permission> granted = switch (role == null ? "" : role) {
            case "owner" -> Set.of(Permission.READ, Permission.OPERATE, Permission.MANAGE, Permission.OWN);
            case "manager" -> Set.of(Permission.READ, Permission.OPERATE, Permission.MANAGE);
            case "agent" -> Set.of(Permission.READ, Permission.OPERATE);
            case "viewer" -> Set.of(Permission.READ);
            default -> Set.of();
        };
        return granted.contains(permission);
    }

    /** Client ids the current user may read; empty optional means "all clients" (platform admin). */
    @Transactional(readOnly = true)
    public Optional<List<UUID>> readableClients() {
        Actor actor = currentActor();
        if (actor.platformAdmin()) {
            return Optional.empty();
        }
        return Optional.of(members.findByUserEmail(actor.email()).stream().map(ClientMember::getClientId).toList());
    }

    /** Makes {@code email} the owner of a client it just created (no-op for development/admin actors). */
    @Transactional
    public void grantOwner(UUID clientId, Actor actor) {
        if (actor == null || DEV_ACTOR.equals(actor.email()) || members.existsByClientIdAndUserEmail(clientId, actor.email())) {
            return;
        }
        ClientMember member = new ClientMember();
        member.setClientId(clientId);
        member.setUserEmail(actor.email());
        member.setRole("owner");
        member.setCreatedBy(actor.email());
        members.save(member);
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        throw new ForbiddenException("No request context");
    }
}
