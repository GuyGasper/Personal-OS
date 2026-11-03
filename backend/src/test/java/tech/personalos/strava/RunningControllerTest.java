package tech.personalos.strava;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RunningControllerTest {
    @Test
    void exposesOnly365DailyTotalsWithoutRequiringAPrivateReadToken() {
        var repository = mock(StravaRepository.class);
        when(repository.runningDistances(any(), any())).thenReturn(List.of());
        var response = new RunningController(repository).mileage("America/Chicago");
        var mileage = response.getBody();
        assertEquals("no-store", response.getHeaders().getFirst("Cache-Control"));
        assertEquals(365, mileage.days().size());
        assertEquals(mileage.endDate().minusDays(364), mileage.startDate());
        assertTrue(mileage.days().stream().allMatch(day -> day.distanceMeters() == 0));
        var zone = ZoneId.of("America/Chicago");
        verify(repository).runningDistances(mileage.startDate().atStartOfDay(zone).toInstant(),
                mileage.endDate().plusDays(1).atStartOfDay(zone).toInstant());
    }

    @Test
    void rejectsInvalidTimezonesBeforeReadingData() {
        var repository = mock(StravaRepository.class);
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> new RunningController(repository).mileage("invalid/timezone")).getStatusCode().value());
        verifyNoInteractions(repository);
    }

    @Test
    void sumsLocalDaysAcrossDaylightSavingAndKeepsLeapYearWindowsAt365Days() {
        var zone = ZoneId.of("America/Chicago");
        var end = LocalDate.of(2024, 3, 10);
        var runs = List.of(
                new StravaRepository.RunDistance(Instant.parse("2024-03-10T07:30:00Z"), 1609.344),
                new StravaRepository.RunDistance(Instant.parse("2024-03-10T08:30:00Z"), 3218.688),
                new StravaRepository.RunDistance(Instant.parse("2023-03-12T05:59:59Z"), 99999),
                new StravaRepository.RunDistance(Instant.parse("2024-03-11T05:00:00Z"), 99999));
        var mileage = RunningController.summarize(runs, zone, end);
        assertEquals(LocalDate.of(2023, 3, 12), mileage.startDate());
        assertEquals(365, mileage.days().size());
        assertEquals(365, mileage.days().stream().map(RunningController.Day::date).distinct().count());
        assertEquals(4828.032, mileage.days().getLast().distanceMeters(), 0.0001);
        assertTrue(mileage.days().subList(0, 364).stream().allMatch(day -> day.distanceMeters() == 0));
        assertTrue(mileage.days().stream().anyMatch(day -> day.date().equals(LocalDate.of(2024, 2, 29))));
    }
}
