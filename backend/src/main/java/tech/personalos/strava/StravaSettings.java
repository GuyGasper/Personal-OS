package tech.personalos.strava;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("strava")
public record StravaSettings(
        @DefaultValue("0") long athleteId,
        @DefaultValue("") String clientId,
        @DefaultValue("") String clientSecret,
        @DefaultValue("") String refreshToken,
        @DefaultValue("") String tokenEncryptionKey,
        @DefaultValue("") String readToken,
        @DefaultValue("https://www.strava.com/api/v3") String apiBaseUrl,
        @DefaultValue("https://www.strava.com/oauth/token") String tokenUrl,
        @DefaultValue("30") int lookbackDays,
        @DefaultValue("80") int maxPages,
        @DefaultValue("24") int fullSyncHours) {
    void validateSync() {
        if (athleteId <= 0 || clientId.isBlank() || clientSecret.isBlank()
                || lookbackDays < 1 || maxPages < 1 || maxPages > 90 || fullSyncHours < 24)
            throw new IllegalArgumentException("Invalid Strava sync configuration; see docs/strava.md");
    }
}