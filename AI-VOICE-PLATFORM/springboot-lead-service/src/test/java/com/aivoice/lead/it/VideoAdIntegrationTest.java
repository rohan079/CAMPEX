package com.aivoice.lead.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aivoice.lead.service.FirebaseTokenVerifier;
import com.aivoice.lead.service.FirebaseTokenVerifier.VerifiedFirebaseUser;
import com.aivoice.lead.service.creative.CreativeAiGateway;
import com.aivoice.lead.service.execution.MetaExecutionGateway;
import com.aivoice.lead.service.execution.MetaExecutionGateway.CampaignState;
import com.aivoice.lead.service.execution.MetaExecutionGateway.CreativeSpec;
import com.aivoice.lead.service.execution.MetaExecutionGateway.GatewayException;
import com.aivoice.lead.service.execution.MetaExecutionGateway.PublishResult;
import com.aivoice.lead.service.media.Mp4InspectorTest;
import com.aivoice.lead.service.performance.MetaAccessResolver;
import com.aivoice.lead.service.performance.MetaAccessResolver.MetaAccess;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Video creatives and Meta ad creation over HTTP + PostgreSQL. Meta and AI are mocks. Gated on CAMPEX_IT_DATABASE_URL. */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "CAMPEX_IT_DATABASE_URL", matches = "jdbc:postgresql:.+")
class VideoAdIntegrationTest {

    static final Path MEDIA_DIR;
    static {
        try { MEDIA_DIR = Files.createTempDirectory("campex-media-it"); } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> System.getenv("CAMPEX_IT_DATABASE_URL"));
        r.add("spring.datasource.username", () -> System.getenv().getOrDefault("CAMPEX_IT_DATABASE_USER", "campex"));
        r.add("spring.datasource.password", () -> System.getenv().getOrDefault("CAMPEX_IT_DATABASE_PASSWORD", ""));
        r.add("app.environment", () -> "production");
        r.add("app.business-name", () -> "IT");
        r.add("app.business-description", () -> "IT");
        r.add("app.call-poll-seconds", () -> "3600");
        r.add("app.ad-popup-token", () -> "popup-token");
        r.add("app.internal-service-key", () -> "service-key");
        r.add("app.whatsapp.verify-token", () -> "x");
        r.add("app.whatsapp.app-secret", () -> "x");
        r.add("app.whatsapp.access-token", () -> "x");
        r.add("app.whatsapp.phone-number-id", () -> "x");
        r.add("app.whatsapp.graph-api-version", () -> "v27.0");
        r.add("campex.firebase.project-id", () -> "it-project");
        r.add("campex.access.platform-admin-emails", () -> "admin@it.test");
        r.add("campex.tracking.capi-enabled", () -> "false");
        r.add("campex.media.dir", () -> MEDIA_DIR.toString());
        r.add("campex.media.max-video-seconds", () -> "60");
        r.add("campex.ads.initial-delay-ms", () -> "3600000");
        r.add("meta.token-encryption-key", () -> java.util.Base64.getEncoder().encodeToString(new byte[32]));
    }

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
    static final String ACCOUNT = "act_5550006";
    static final String RUN = String.valueOf(System.nanoTime() % 1_000_000_000L);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean FirebaseTokenVerifier firebase;
    @MockitoBean CreativeAiGateway ai;
    @MockitoBean MetaExecutionGateway meta;
    @MockitoBean MetaAccessResolver metaAccess;

    String client;
    static final java.util.concurrent.atomic.AtomicInteger SEQ = new java.util.concurrent.atomic.AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        when(firebase.verifyIdToken(anyString())).thenAnswer(i -> new VerifiedFirebaseUser(i.getArgument(0), "uid", true));
        when(metaAccess.resolve(any(), any())).thenReturn(new MetaAccess(true, null, List.of(ACCOUNT), "tok"));
        when(ai.check(any(), any())).thenReturn(Map.of("unsupported_numbers", List.of(), "flagged_phrases", List.of()));
        when(meta.status(anyString(), anyString(), anyString())).thenReturn(new CampaignState("PAUSED", "PAUSED"));
        client = ok(as("admin@it.test", post("/api/clients"), "{\"name\":\"Video " + UUID.randomUUID().toString().substring(0, 8)
                + "\",\"voice_agent_enabled\":false}")).get("id").asText();
        as("admin@it.test", post("/api/clients/" + client + "/members"), "{\"user_email\":\"m@v.test\",\"role\":\"manager\"}").andExpect(status().isOk());
        jdbc.update("INSERT INTO client_ad_accounts (id, client_id, ad_account_id, created_by, created_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), UUID.fromString(client), ACCOUNT, "m@v.test", Timestamp.from(Instant.now()));
    }

    ResultActions as(String email, MockHttpServletRequestBuilder req, String body) throws Exception {
        if (email != null) req.header("Authorization", "Bearer " + email);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(req);
    }

    JsonNode ok(ResultActions r) throws Exception {
        return json.readTree(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    ResultActions upload(byte[] video, String name) throws Exception {
        return mvc.perform(multipart("/api/clients/" + client + "/creative/videos")
                .file(new MockMultipartFile("video", "tour.mp4", "video/mp4", video))
                .file(new MockMultipartFile("thumbnail", "thumb.png", "image/png", PNG))
                .param("name", name).param("ad_type", "villa").param("facts", "3 BHK villas")
                .header("Authorization", "Bearer m@v.test"));
    }

    String approvedVideo() throws Exception {
        String id = ok(upload(Mp4InspectorTest.mp4("isom", 1000, 30_000, false), "Villa tour")).at("/asset/id").asText();
        ok(as("m@v.test", put("/api/creative/assets/" + id), "{\"headline\":\"Villas near Guntur\",\"primary_text\":\"Watch the tour.\",\"cta\":\"LEARN_MORE\"}"));
        ok(as("m@v.test", post("/api/creative/assets/" + id + "/submit"), null));
        ok(as("m@v.test", post("/api/creative/assets/" + id + "/approve"), "{}"));
        return id;
    }

    String publishedWith(String creativeId, int version, boolean withUrl) throws Exception {
        String c = RUN + SEQ.incrementAndGet(), s = RUN + SEQ.incrementAndGet();
        when(meta.publish(eq("tok"), any())).thenReturn(new PublishResult(c, s, null, "PAUSED", null));
        String body = "{\"ad_account_id\":\"" + ACCOUNT + "\",\"name\":\"Tour\",\"objective\":\"leads\",\"platforms\":[\"facebook\"],"
                + "\"location\":\"Guntur\",\"age_min\":25,\"age_max\":55,\"daily_budget_minor\":50000,\"headline\":\"Villas near Guntur\","
                + "\"primary_text\":\"Watch the tour.\",\"cta\":\"LEARN_MORE\",\"page_id\":\"42\""
                + (withUrl ? ",\"destination_url\":\"https://greenfields.example/villas\"" : "")
                + (creativeId == null ? "" : ",\"creative_asset_id\":\"" + creativeId + "\",\"creative_version\":" + version) + "}";
        String id = ok(as("m@v.test", post("/api/clients/" + client + "/campaign-executions"), body)).at("/execution/id").asText();
        ok(as("m@v.test", post("/api/campaign-executions/" + id + "/validate"), null));
        ok(as("m@v.test", post("/api/campaign-executions/" + id + "/submit"), null));
        ok(as("m@v.test", post("/api/campaign-executions/" + id + "/approve"), "{}"));
        return id;
    }

    @Test
    void videosAreValidatedStoredAndStreamedToMembers() throws Exception {
        JsonNode up = ok(upload(Mp4InspectorTest.mp4("isom", 1000, 45_000, false), "Clubhouse"));
        assertThat(up.at("/asset/media_type").asText()).isEqualTo("video");
        assertThat(up.at("/asset/video_duration_ms").asInt()).isEqualTo(45_000);
        String videoId = up.at("/asset/video_id").asText();
        byte[] served = as("m@v.test", get("/api/creative/videos/" + videoId), null).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(served).isEqualTo(Mp4InspectorTest.mp4("isom", 1000, 45_000, false));
        as("m@v.test", get("/api/creative/videos/" + videoId).header("Range", "bytes=0-3"), null).andExpect(status().isPartialContent());
        as("stranger@x.test", get("/api/creative/videos/" + videoId), null).andExpect(status().isNotFound());

        upload(Mp4InspectorTest.mp4("isom", 1000, 90_000, false), "Too long").andExpect(status().isBadRequest());
        upload("not a video at all, just text".getBytes(), "Fake").andExpect(status().isBadRequest());
    }

    @Test
    void publishingAVideoCreativeCreatesThePausedAdAfterMetaProcessesIt() throws Exception {
        String creative = approvedVideo();
        int version = ok(as("m@v.test", get("/api/creative/assets/" + creative), null)).at("/asset/approved_version").asInt();
        String exec = publishedWith(creative, version, true);
        when(meta.uploadImage(eq("tok"), eq(ACCOUNT), anyString(), any())).thenReturn("hash1");
        when(meta.uploadVideo(eq("tok"), eq(ACCOUNT), anyString(), any(Path.class), eq("video/mp4"))).thenReturn("vid1");
        when(meta.videoStatus("tok", "vid1")).thenReturn("processing");
        JsonNode published = ok(as("m@v.test", post("/api/campaign-executions/" + exec + "/publish"), "{\"confirm\":true}"));
        assertThat(published.get("ad_status").asText()).isEqualTo("pending");

        JsonNode processing = ok(as("m@v.test", post("/api/campaign-executions/" + exec + "/ad/check"), null));
        assertThat(processing.get("ad_status").asText()).isEqualTo("processing");
        assertThat(processing.get("meta_video_id").asText()).isEqualTo("vid1");

        when(meta.videoStatus("tok", "vid1")).thenReturn("ready");
        when(meta.createCreative(eq("tok"), any())).thenReturn("cr1");
        when(meta.findAds(eq("tok"), anyString(), anyString())).thenReturn(List.of());
        when(meta.createAd(eq("tok"), eq(ACCOUNT), anyString(), eq("cr1"), anyString())).thenReturn("ad1");
        JsonNode created = ok(as("m@v.test", post("/api/campaign-executions/" + exec + "/ad/check"), null));
        assertThat(created.get("ad_status").asText()).isEqualTo("created");
        assertThat(created.get("meta_ad_id").asText()).isEqualTo("ad1");

        ArgumentCaptor<CreativeSpec> spec = ArgumentCaptor.forClass(CreativeSpec.class);
        verify(meta).createCreative(eq("tok"), spec.capture());
        assertThat(spec.getValue().kind()).isEqualTo("video");
        assertThat(spec.getValue().videoId()).isEqualTo("vid1");
        assertThat(spec.getValue().imageHash()).isEqualTo("hash1");
        assertThat(spec.getValue().linkUrl()).isEqualTo("https://greenfields.example/villas");
        verify(meta, times(1)).uploadVideo(anyString(), anyString(), anyString(), any(Path.class), anyString());
        verify(meta, times(1)).createAd(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void failedStepsRetryWithoutUploadingTwiceAndExistingAdsAreLinked() throws Exception {
        String creative = approvedVideo();
        int version = ok(as("m@v.test", get("/api/creative/assets/" + creative), null)).at("/asset/approved_version").asInt();
        String exec = publishedWith(creative, version, true);
        when(meta.uploadImage(anyString(), anyString(), anyString(), any())).thenReturn("hash2");
        when(meta.uploadVideo(anyString(), anyString(), anyString(), any(Path.class), anyString())).thenReturn("vid2");
        when(meta.videoStatus("tok", "vid2")).thenReturn("ready");
        when(meta.createCreative(anyString(), any())).thenThrow(new GatewayException("Meta rejected the request (code 100)", null));
        ok(as("m@v.test", post("/api/campaign-executions/" + exec + "/publish"), "{\"confirm\":true}"));
        JsonNode failed = ok(as("m@v.test", post("/api/campaign-executions/" + exec + "/ad/check"), null));
        assertThat(failed.get("ad_status").asText()).isEqualTo("failed");
        assertThat(failed.get("ad_error").asText()).contains("code 100");
        assertThat(failed.get("allowed_actions").toString()).contains("ad_retry");

        org.mockito.Mockito.doReturn("cr2").when(meta).createCreative(anyString(), any());
        when(meta.findAds(anyString(), anyString(), anyString())).thenReturn(List.of("ad-existing"));
        JsonNode retried = ok(as("m@v.test", post("/api/campaign-executions/" + exec + "/ad/retry"), null));
        assertThat(retried.get("ad_status").asText()).isEqualTo("created");
        assertThat(retried.get("meta_ad_id").asText()).isEqualTo("ad-existing");
        verify(meta, times(1)).uploadVideo(anyString(), anyString(), anyString(), any(Path.class), anyString());
        verify(meta, never()).createAd(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    String approvedImage(String headline) throws Exception {
        String id = ok(as("m@v.test", post("/api/clients/" + client + "/creative/assets"), "{\"name\":\"Lawn photo\",\"ad_type\":\"villa\","
                + "\"image_base64\":\"" + java.util.Base64.getEncoder().encodeToString(PNG) + "\",\"mime_type\":\"image/png\"}")).at("/asset/id").asText();
        ok(as("m@v.test", put("/api/creative/assets/" + id), "{\"headline\":\"" + headline + "\",\"primary_text\":\"Walk the lawns this weekend.\",\"cta\":\"BOOK_NOW\"}"));
        ok(as("m@v.test", post("/api/creative/assets/" + id + "/submit"), null));
        ok(as("m@v.test", post("/api/creative/assets/" + id + "/approve"), "{}"));
        return id;
    }

    @Test
    void severalCreativesBecomeSeparatePausedAdsInOneAdSetAndRetryOnlyTheFailedOne() throws Exception {
        String video = approvedVideo();
        String image = approvedImage("Green lawns near Guntur");
        String c = RUN + SEQ.incrementAndGet(), adset = RUN + SEQ.incrementAndGet();
        when(meta.publish(eq("tok"), any())).thenReturn(new PublishResult(c, adset, null, "PAUSED", null));
        String body = "{\"ad_account_id\":\"" + ACCOUNT + "\",\"name\":\"Tour\",\"objective\":\"leads\",\"platforms\":[\"facebook\"],"
                + "\"location\":\"Guntur\",\"age_min\":25,\"age_max\":55,\"daily_budget_minor\":50000,\"headline\":\"Villas near Guntur\","
                + "\"primary_text\":\"Watch the tour.\",\"cta\":\"LEARN_MORE\",\"page_id\":\"42\",\"destination_url\":\"https://greenfields.example/villas\","
                + "\"creative_asset_id\":\"" + video + "\",\"creative_version\":2,\"extra_creatives\":[{\"asset_id\":\"" + image + "\",\"version\":2}]}";
        String exec = ok(as("m@v.test", post("/api/clients/" + client + "/campaign-executions"), body)).at("/execution/id").asText();
        JsonNode report = ok(as("m@v.test", post("/api/campaign-executions/" + exec + "/validate"), null));
        assertThat(report.get("valid").asBoolean()).as(report.toString()).isTrue();
        ok(as("m@v.test", post("/api/campaign-executions/" + exec + "/submit"), null));
        ok(as("m@v.test", post("/api/campaign-executions/" + exec + "/approve"), "{}"));

        when(meta.uploadImage(anyString(), anyString(), anyString(), any())).thenReturn("h1", "h2");
        when(meta.uploadVideo(anyString(), anyString(), anyString(), any(Path.class), anyString())).thenReturn("vid1");
        when(meta.videoStatus("tok", "vid1")).thenReturn("ready");
        when(meta.createCreative(anyString(), any())).thenReturn("cr1", "cr2");
        when(meta.findAds(anyString(), anyString(), anyString())).thenReturn(List.of());
        when(meta.createAd(anyString(), anyString(), anyString(), eq("cr1"), anyString())).thenReturn("ad1");
        when(meta.createAd(anyString(), anyString(), anyString(), eq("cr2"), anyString()))
                .thenThrow(new GatewayException("Meta rejected the request (code 100)", null));
        JsonNode published = ok(as("m@v.test", post("/api/campaign-executions/" + exec + "/publish"), "{\"confirm\":true}"));
        assertThat(published.get("ads")).hasSize(2);
        JsonNode partial = ok(as("m@v.test", post("/api/campaign-executions/" + exec + "/ad/check"), null));
        assertThat(partial.get("ad_status").asText()).isEqualTo("failed");
        assertThat(partial.at("/ads/0/status").asText()).isEqualTo("created");
        assertThat(partial.at("/ads/0/meta_ad_id").asText()).isEqualTo("ad1");
        assertThat(partial.at("/ads/1/status").asText()).isEqualTo("failed");
        assertThat(partial.get("ad_error").asText()).startsWith("Ad 2:").contains("code 100");

        ArgumentCaptor<MetaExecutionGateway.CreativeSpec> specs = ArgumentCaptor.forClass(MetaExecutionGateway.CreativeSpec.class);
        verify(meta, times(2)).createCreative(anyString(), specs.capture());
        assertThat(specs.getAllValues().get(0).kind()).isEqualTo("video");
        assertThat(specs.getAllValues().get(1).kind()).isEqualTo("image");
        assertThat(specs.getAllValues().get(1).headline()).isEqualTo("Green lawns near Guntur");
        assertThat(specs.getAllValues().get(1).cta()).isEqualTo("BOOK_NOW");
        String tag = partial.get("publish_tag").asText();
        verify(meta).findAds(anyString(), eq(adset), eq("[" + tag + "]"));
        verify(meta).findAds(anyString(), eq(adset), eq("[" + tag + "-2]"));

        org.mockito.Mockito.doReturn("ad2").when(meta).createAd(anyString(), anyString(), anyString(), eq("cr2"), anyString());
        JsonNode retried = ok(as("m@v.test", post("/api/campaign-executions/" + exec + "/ad/retry"), null));
        assertThat(retried.get("ad_status").asText()).isEqualTo("created");
        assertThat(retried.at("/ads/1/meta_ad_id").asText()).isEqualTo("ad2");
        verify(meta, times(1)).createAd(anyString(), anyString(), anyString(), eq("cr1"), anyString());
        verify(meta, times(2)).uploadImage(anyString(), anyString(), anyString(), any());
        verify(meta, times(2)).createCreative(anyString(), any());
    }

    @Test
    void extraCreativesMustBeApprovedAndDistinct() throws Exception {
        String video = approvedVideo();
        String body = "{\"ad_account_id\":\"" + ACCOUNT + "\",\"name\":\"Tour\",\"objective\":\"leads\",\"platforms\":[\"facebook\"],"
                + "\"location\":\"Guntur\",\"age_min\":25,\"age_max\":55,\"daily_budget_minor\":50000,\"headline\":\"Villas near Guntur\","
                + "\"primary_text\":\"Watch the tour.\",\"cta\":\"LEARN_MORE\",\"page_id\":\"42\",\"creative_asset_id\":\"" + video
                + "\",\"creative_version\":2,\"extra_creatives\":[{\"asset_id\":\"" + video + "\",\"version\":2},"
                + "{\"asset_id\":\"" + UUID.randomUUID() + "\",\"version\":1}]}";
        String exec = ok(as("m@v.test", post("/api/clients/" + client + "/campaign-executions"), body)).at("/execution/id").asText();
        JsonNode report = ok(as("m@v.test", post("/api/campaign-executions/" + exec + "/validate"), null));
        assertThat(report.get("valid").asBoolean()).isFalse();
        assertThat(report.get("errors").findValuesAsText("code")).contains("duplicate", "not_approved");
    }

    @Test
    void unexpectedErrorsFailVisiblyInsteadOfStayingStuck() throws Exception {
        String creative = approvedVideo();
        int version = ok(as("m@v.test", get("/api/creative/assets/" + creative), null)).at("/asset/approved_version").asInt();
        String exec = publishedWith(creative, version, true);
        when(meta.uploadImage(anyString(), anyString(), anyString(), any())).thenThrow(new IllegalStateException("boom"));
        ok(as("m@v.test", post("/api/campaign-executions/" + exec + "/publish"), "{\"confirm\":true}"));
        JsonNode failed = ok(as("m@v.test", post("/api/campaign-executions/" + exec + "/ad/check"), null));
        assertThat(failed.get("ad_status").asText()).isEqualTo("failed");
        assertThat(failed.get("ad_error").asText()).contains("unexpected error").doesNotContain("boom");
        assertThat(failed.get("allowed_actions").toString()).contains("ad_retry");
    }

    @Test
    void campaignsWithoutCreativeOrUrlSkipAdCreationWithAReason() throws Exception {
        String exec = publishedWith(null, 0, true);
        JsonNode published = ok(as("m@v.test", post("/api/campaign-executions/" + exec + "/publish"), "{\"confirm\":true}"));
        assertThat(published.get("ad_status").asText()).isEqualTo("skipped");
        assertThat(published.get("ad_error").asText()).contains("No approved creative");
        verify(meta, never()).uploadImage(anyString(), anyString(), anyString(), any());
    }
}
