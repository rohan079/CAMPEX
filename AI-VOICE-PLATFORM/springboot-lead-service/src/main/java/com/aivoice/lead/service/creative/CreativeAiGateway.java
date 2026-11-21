package com.aivoice.lead.service.creative;

import com.aivoice.lead.service.VoiceServiceClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Creative AI calls to FastAPI. Never receives Meta tokens or client data beyond the brief and verified facts. */
@Component
public class CreativeAiGateway {

    public record GeneratedImage(String mimeType, String dataBase64, String prompt, String model) {
    }

    private final VoiceServiceClient voice;

    public CreativeAiGateway(VoiceServiceClient voice) {
        this.voice = voice;
    }

    public Map<String, Object> copy(Map<String, Object> brief, List<String> facts) {
        return voice.creativeCall("/api/creative/copy", Map.of("brief", brief, "facts", facts));
    }

    public Map<String, Object> check(List<String> texts, List<String> facts) {
        return voice.creativeCall("/api/creative/check", Map.of("texts", texts, "facts", facts));
    }

    public GeneratedImage image(String prompt, String referenceBase64, String referenceMime) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prompt", prompt);
        if (referenceBase64 != null) {
            body.put("reference_base64", referenceBase64);
            body.put("reference_mime", referenceMime);
        }
        Map<String, Object> r = voice.creativeCall("/api/creative/image", body);
        return new GeneratedImage(String.valueOf(r.get("mime_type")), String.valueOf(r.get("data_base64")),
                String.valueOf(r.get("prompt")), String.valueOf(r.get("model")));
    }
}
