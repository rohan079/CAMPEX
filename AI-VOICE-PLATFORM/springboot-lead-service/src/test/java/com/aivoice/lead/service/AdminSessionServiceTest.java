package com.aivoice.lead.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aivoice.lead.config.AdminAuthProperties;
import com.aivoice.lead.entity.CampexAdminSession;
import com.aivoice.lead.repository.CampexAdminSessionRepository;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class AdminSessionServiceTest {

    private static final String EMAIL = "operator@example.test";
    private static final String PASSWORD = "Long-test-password-123!";

    @Test
    void loginStoresOnlySessionHashAndCookieAuthenticatesAdmin() throws Exception {
        CampexAdminSessionRepository repository = mock(CampexAdminSessionRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        AdminSessionService service = new AdminSessionService(
                new AdminAuthProperties(EMAIL, passwordHash(PASSWORD), 3600), repository);

        AdminSessionService.IssuedSession issued = service.login(EMAIL, PASSWORD);
        var captor = org.mockito.ArgumentCaptor.forClass(CampexAdminSession.class);
        verify(repository).save(captor.capture());
        CampexAdminSession stored = captor.getValue();
        assertNotEquals(issued.token(), stored.getTokenHash());

        when(repository.findByTokenHashAndExpiresAtAfter(anyString(), any())).thenReturn(Optional.of(stored));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AdminSessionService.COOKIE_NAME, issued.token()));
        assertEquals(EMAIL, service.requireOwnerEmail(request));

        service.logout(request);
        verify(repository).deleteByTokenHash(stored.getTokenHash());
    }

    @Test
    void rejectsWrongPasswordAndMissingProductionConfiguration() {
        CampexAdminSessionRepository repository = mock(CampexAdminSessionRepository.class);
        AdminSessionService service = new AdminSessionService(
                new AdminAuthProperties(EMAIL, passwordHash(PASSWORD), 3600), repository);
        AdminSessionService.AuthException invalid = assertThrows(
                AdminSessionService.AuthException.class, () -> service.login(EMAIL, "wrong-password"));
        assertEquals(HttpStatus.UNAUTHORIZED, invalid.status());

        AdminSessionService unconfigured = new AdminSessionService(
                new AdminAuthProperties("", "", 3600), repository);
        AdminSessionService.AuthException missing = assertThrows(
                AdminSessionService.AuthException.class, () -> unconfigured.login(EMAIL, PASSWORD));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, missing.status());
    }

    private static String passwordHash(String password) {
        try {
            byte[] salt = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
            int iterations = 310_000;
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
            byte[] digest = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            spec.clearPassword();
            return "pbkdf2-sha256$" + iterations + "$" + encode(salt) + "$" + encode(digest);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
