package tech.personalos.strava;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@RestController
@Profile("!strava-sync")
@RequestMapping("/api/strava/webhook")
class StravaWebhookController {
    private final StravaRepository repository;
    private final String secret;
    private final long subscriptionId;

    StravaWebhookController(StravaRepository repository,
            @Value("${STRAVA_WEBHOOK_TOKEN:}") String secret,
            @Value("${STRAVA_WEBHOOK_SUBSCRIPTION_ID:0}") long subscriptionId) {
        this.repository = repository; this.secret = secret; this.subscriptionId = subscriptionId;
    }

    // A high-entropy callback path authenticates POSTs: Strava does not sign events.
    @GetMapping("/{token}")
    ResponseEntity<Map<String, String>> verify(@PathVariable String token,
            @RequestParam("hub.mode") String mode, @RequestParam("hub.verify_token") String verifyToken,
            @RequestParam("hub.challenge") String challenge) {
        authorize(token);
        if (!"subscribe".equals(mode) || !equal(secret, verifyToken)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(Map.of("hub.challenge", challenge));
    }

    @PostMapping("/{token}")
    ResponseEntity<Void> event(@PathVariable String token, @RequestBody JsonNode event) {
        authorize(token);
        var state = repository.state();
        if (subscriptionId <= 0 || event.path("subscription_id").asLong() != subscriptionId)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if (state != null && event.path("owner_id").asLong() == state.athleteId()) {
            if ("athlete".equals(event.path("object_type").asText())
                    && "false".equals(event.path("updates").path("authorized").asText())) {
                repository.revoke();
            }
            // Creates, edits and deletes are reconciled by the scheduled worker.
        }
        return ResponseEntity.ok().header("Cache-Control", "no-store").build();
    }

    private void authorize(String token) {
        if (secret.length() < 32 || !equal(secret, token)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    private static boolean equal(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}