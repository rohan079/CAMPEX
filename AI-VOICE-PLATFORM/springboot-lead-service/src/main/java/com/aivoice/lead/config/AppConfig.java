package com.aivoice.lead.config;

import com.aivoice.lead.service.FollowUpPolicy;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AppConfig implements WebMvcConfigurer {

    private final AppProperties props;

    public AppConfig(AppProperties props) {
        this.props = props;
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public FollowUpPolicy followUpPolicy() {
        return new FollowUpPolicy(props.zone(), props.followUpCallDays());
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = java.util.Arrays.stream(props.corsOrigins().split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toArray(String[]::new);
        // The website SDK runs on client domains. Origins are checked against each pixel's allowed domains in
        // TrackingIngestionService; no cookies or credentials are accepted on these routes.
        registry.addMapping("/api/tracking/events")
                .allowedOriginPatterns("*")
                .allowedMethods("POST", "OPTIONS")
                .allowedHeaders("Content-Type")
                .allowCredentials(false)
                .maxAge(3600);
        registry.addMapping("/api/tracking/consent")
                .allowedOriginPatterns("*")
                .allowedMethods("POST", "OPTIONS")
                .allowedHeaders("Content-Type")
                .allowCredentials(false)
                .maxAge(3600);
        registry.addMapping("/api/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", "X-Ad-Popup-Token");
        registry.addMapping("/health")
                .allowedOrigins(origins)
                .allowedMethods("GET", "OPTIONS")
                .allowedHeaders("Content-Type");
    }
}
