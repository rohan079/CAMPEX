package com.aivoice.lead.service;

import java.util.UUID;

/** App credentials used for one Meta Business Login / token exchange. */
public record MetaAppCredentials(
        String appId,
        String appSecret,
        String loginConfigId,
        String redirectUri,
        String graphApiVersion,
        UUID clientId,
        boolean fromClient) {

    public boolean hasLoginConfigId() {
        return loginConfigId != null && !loginConfigId.isBlank();
    }
}
