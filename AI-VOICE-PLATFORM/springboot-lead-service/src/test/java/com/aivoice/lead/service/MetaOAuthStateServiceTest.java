package com.aivoice.lead.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aivoice.lead.entity.MetaOAuthState;
import com.aivoice.lead.repository.MetaOAuthStateRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class MetaOAuthStateServiceTest {

    @Test
    void stateIsHighEntropyHashedAndConsumedOnce() {
        MetaOAuthStateRepository repository = mock(MetaOAuthStateRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        MetaOAuthStateService service = new MetaOAuthStateService(repository);

        String state = service.create("operator@example.test");
        var captor = org.mockito.ArgumentCaptor.forClass(MetaOAuthState.class);
        verify(repository).save(captor.capture());
        MetaOAuthState stored = captor.getValue();
        assertNotEquals(state, stored.getStateHash());
        assertEquals(43, state.length());

        when(repository.lockByStateHash(stored.getStateHash())).thenReturn(Optional.of(stored));
        assertEquals("operator@example.test", service.consume(state));
        verify(repository).delete(stored);

        when(repository.lockByStateHash(stored.getStateHash())).thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class, () -> service.consume(state));
    }

    @Test
    void expiredStateCannotBeConsumed() {
        MetaOAuthStateRepository repository = mock(MetaOAuthStateRepository.class);
        MetaOAuthStateService service = new MetaOAuthStateService(repository);
        String state = service.create("operator@example.test");
        var captor = org.mockito.ArgumentCaptor.forClass(MetaOAuthState.class);
        verify(repository).save(captor.capture());
        MetaOAuthState stored = captor.getValue();
        stored.setExpiresAt(Instant.now().minusSeconds(1));
        when(repository.lockByStateHash(stored.getStateHash())).thenReturn(Optional.of(stored));

        assertThrows(ResponseStatusException.class, () -> service.consume(state));
        verify(repository).delete(stored);
    }
}
