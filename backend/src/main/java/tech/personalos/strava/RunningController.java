package tech.personalos.strava;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Public graph data only. Detailed activities and sync state retain their private API. */
@RestController
@Profile("!strava-sync")
class RunningController {
    private final StravaRepository repository;

    RunningController(StravaRepository repository) { this.repository = repository; }

    @GetMapping("/api/running/mileage")
    ResponseEntity<Mileage> mileage(@RequestParam(defaultValue="UTC") String timezone) {
        final ZoneId zone;
        try { zone = ZoneId.of(timezone); }
        catch (DateTimeException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid timezone");
        }
        var end = LocalDate.now(zone);
        var start = end.minusDays(364);
        var runs = repository.runningDistances(start.atStartOfDay(zone).toInstant(),
                end.plusDays(1).atStartOfDay(zone).toInstant());
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(summarize(runs, zone, end));
    }

    static Mileage summarize(List<StravaRepository.RunDistance> runs, ZoneId zone, LocalDate end) {
        var start = end.minusDays(364);
        var distances = new HashMap<LocalDate, Double>();
        for (var run : runs) {
            var date = run.startDate().atZone(zone).toLocalDate();
            if (!date.isBefore(start) && !date.isAfter(end))
                distances.merge(date, run.distanceMeters(), Double::sum);
        }
        var days = new ArrayList<Day>(365);
        for (int index = 0; index < 365; index++) {
            var date = start.plusDays(index);
            days.add(new Day(date, distances.getOrDefault(date, 0.0)));
        }
        return new Mileage(zone.getId(), start, end, List.copyOf(days));
    }

    record Day(LocalDate date, double distanceMeters) {}
    record Mileage(String timezone, LocalDate startDate, LocalDate endDate, List<Day> days) {}
}
