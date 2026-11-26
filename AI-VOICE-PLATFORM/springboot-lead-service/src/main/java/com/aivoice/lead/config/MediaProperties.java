package com.aivoice.lead.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Creative media storage ({@code campex.media.*}). */
@ConfigurationProperties(prefix = "campex.media")
public record MediaProperties(
        /** Directory for video files (a persistent volume in production). Blank disables video upload. */
        @DefaultValue("") String dir,
        @DefaultValue("200") int maxVideoMb,
        @DefaultValue("60") int maxVideoSeconds) {

    public boolean videoEnabled() {
        return dir != null && !dir.isBlank();
    }
}
