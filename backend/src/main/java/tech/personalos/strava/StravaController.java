package tech.personalos.strava;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("!strava-sync")
@RequestMapping("/api/strava")
class StravaController {
    private final StravaSettings settings;
    private final StravaRepository repository;
    StravaController(StravaSettings settings, StravaRepository repository) {
        this.settings = settings; this.repository = repository;
    }

    @GetMapping("/activities")
    ResponseEntity<Activities> activities(@RequestHeader(value="Authorization", required=false) String authorization,
            @RequestParam(defaultValue="50") int limit, @RequestParam(defaultValue="0") int offset) {
        authorize(authorization);
        if (limit < 1 || limit > 200 || offset < 0 || offset > 1000000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid pagination");
        return privateResponse(new Activities(repository.activities(limit, offset), repository.count(), limit, offset));
    }

    @GetMapping("/sync")
    ResponseEntity<Status> status(@RequestHeader(value="Authorization", required=false) String authorization) {
        authorize(authorization);
        var state = repository.state();
        return privateResponse(state == null ? new Status(false, false, null, null, null, null, 0)
                : new Status(true, state.authorizationRequired(), state.lastAttemptAt(), state.lastSuccessAt(),
                        state.lastFullSyncAt(), state.lastError(), repository.count()));
    }

    private void authorize(String authorization) {
        if (settings.readToken().length() < 32) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (authorization == null || !MessageDigest.isEqual(
                ("Bearer " + settings.readToken()).getBytes(StandardCharsets.UTF_8),
                authorization.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    private static <T> ResponseEntity<T> privateResponse(T body) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(body);
    }
    record Activities(List<StravaRepository.Activity> activities, long total, int limit, int offset) {}
    record Status(boolean configured, boolean authorizationRequired, Instant lastAttemptAt,
                  Instant lastSuccessAt, Instant lastFullSyncAt, String lastError, long activityCount) {}
}