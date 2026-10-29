package tech.personalos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import tech.personalos.strava.StravaSettings;

@SpringBootApplication
@EnableConfigurationProperties(StravaSettings.class)
public class PersonalOsApplication {
    public static void main(String[] args) {
        var context = SpringApplication.run(PersonalOsApplication.class, args);
        if (context.getEnvironment().matchesProfiles("strava-sync")) context.close();
    }
}