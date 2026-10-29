package tech.personalos.strava;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StravaControllerTest {
    @Test
    void privateEndpointsFailClosedWithoutConfiguredAuthentication() {
        var repository = mock(StravaRepository.class);
        var settings = new StravaSettings(0, "", "", "", "", "", "", "", 30, 80, 24);
        var controller = new StravaController(settings, repository);
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> controller.status(null)).getStatusCode().value());
        verifyNoInteractions(repository);
    }
    @Test
    void validatesCredentialsAndPaginationBeforeReadingDatabase() {
        var repository = mock(StravaRepository.class);
        var controller = new StravaController(StravaSyncTest.settings(80), repository);
        String bearer = "Bearer " + "r".repeat(32);
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> controller.status(null)).getStatusCode().value());
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> controller.status("Bearer invalid")).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> controller.activities(bearer, 201, 0)).getStatusCode().value());
        verifyNoInteractions(repository);
        var response = controller.status(bearer);
        assertEquals("no-store", response.getHeaders().getFirst("Cache-Control"));
        assertFalse(response.getBody().configured());
    }
}