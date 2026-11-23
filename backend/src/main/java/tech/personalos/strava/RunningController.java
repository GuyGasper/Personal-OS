package tech.personalos.strava;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
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
    private static final long MAX_DAYS = 36_600;
    private final StravaRepository repository;

    RunningController(StravaRepository repository) { this.repository = repository; }

    @GetMapping("/api/running/mileage")
    ResponseEntity<Mileage> mileage(@RequestParam(defaultValue="UTC") String timezone,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate endDate) {
        final ZoneId zone;
        try { zone = ZoneId.of(timezone); }
        catch (DateTimeException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid timezone");
        }
        var today = LocalDate.now(zone);
        if ((startDate == null) != (endDate == null))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Supply both startDate and endDate");
        var end = endDate == null ? today : endDate;
        var start = startDate == null ? end.minusDays(364) : startDate;
        long dayCount = ChronoUnit.DAYS.between(start, end) + 1;
        if (dayCount < 1 || dayCount > MAX_DAYS || end.isAfter(today))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date window");
        var runs = repository.runningDistances(start.atStartOfDay(zone).toInstant(),
                end.plusDays(1).atStartOfDay(zone).toInstant());
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(summarize(runs, zone, start, end));
    }

    static Mileage summarize(List<StravaRepository.RunDistance> runs, ZoneId zone, LocalDate start, LocalDate end) {
        var distances = new HashMap<LocalDate, Double>();
        for (var run : runs) {
            var date = run.startDate().atZone(zone).toLocalDate();
            if (!date.isBefore(start) && !date.isAfter(end))
                distances.merge(date, run.distanceMeters(), Double::sum);
        }
        int dayCount = Math.toIntExact(ChronoUnit.DAYS.between(start, end) + 1);
        var days = new ArrayList<Day>(dayCount);
        for (int index = 0; index < dayCount; index++) {
            var date = start.plusDays(index);
            days.add(new Day(date, distances.getOrDefault(date, 0.0)));
        }
        return new Mileage(zone.getId(), start, end, List.copyOf(days));
    }

    record Day(LocalDate date, double distanceMeters) {}
    record Mileage(String timezone, LocalDate startDate, LocalDate endDate, List<Day> days) {}
}
