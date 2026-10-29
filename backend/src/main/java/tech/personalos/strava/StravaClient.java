package tech.personalos.strava;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
@Profile("strava-sync")
class StravaClient {
    private final StravaSettings settings;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final JsonMapper json = JsonMapper.builder().build();
    private boolean budgetExhausted;

    StravaClient(StravaSettings settings) { this.settings = settings; }
    JsonNode refresh(String refreshToken) {
        budgetExhausted = false;
        return send(HttpRequest.newBuilder(URI.create(settings.tokenUrl()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=refresh_token&client_id="
                        + encode(settings.clientId()) + "&client_secret=" + encode(settings.clientSecret())
                        + "&refresh_token=" + encode(refreshToken))), true);
    }
    JsonNode athlete(String accessToken) { return get("/athlete", accessToken); }
    JsonNode activities(String accessToken, Instant after, Instant before, int page) {
        return get("/athlete/activities?per_page=200&page=" + page + "&before=" + before.getEpochSecond()
                + (after == null ? "" : "&after=" + after.getEpochSecond()), accessToken);
    }
    private JsonNode get(String path, String token) {
        if (budgetExhausted) throw new StravaFailure("RATE_LIMIT_NEAR_EXHAUSTION");
        return send(HttpRequest.newBuilder(URI.create(settings.apiBaseUrl() + path))
                .header("Authorization", "Bearer " + token).GET(), false);
    }
    private JsonNode send(HttpRequest.Builder request, boolean refreshing) {
        try {
            var response = client.send(request.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 429) throw new StravaFailure("RATE_LIMITED");
            if (status == 401 || (refreshing && status == 400)) throw new StravaFailure("AUTHORIZATION_REQUIRED");
            if (status != 200) throw new StravaFailure("STRAVA_HTTP_" + status);
            for (String prefix : new String[]{"X-RateLimit", "X-ReadRateLimit"}) {
                var limits = response.headers().firstValue(prefix + "-Limit");
                var usage = response.headers().firstValue(prefix + "-Usage");
                if (limits.isPresent() && usage.isPresent()) {
                    String[] l = limits.get().split(","), u = usage.get().split(",");
                    if (l.length == 2 && u.length == 2)
                        try {
                            for (int i = 0; i < 2; i++)
                                if (Integer.parseInt(u[i].trim()) >= Integer.parseInt(l[i].trim()) - 5) budgetExhausted = true;
                        } catch (NumberFormatException ignored) {
                            budgetExhausted = true;
                        }
                }
            }
            return json.readTree(response.body());
        } catch (StravaFailure e) { throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StravaFailure("STRAVA_REQUEST_INTERRUPTED");
        } catch (Exception e) { throw new StravaFailure("STRAVA_RESPONSE_FAILED"); }
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}