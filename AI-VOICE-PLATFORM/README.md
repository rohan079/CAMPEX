# AI Voice Platform

A WhatsApp-to-phone lead agent, split into two services:

- **`springboot-lead-service`** (Java 21, Spring Boot 3) — knows *who* to contact and *when*. Owns the WhatsApp
  webhook, leads, consent, the 7-day ad follow-up schedule, call state, the dashboard API and the database.
- **`fastapi-voice-service`** (Python, FastAPI) — owns the Edesy Calls API, Edesy media WebSocket adapter,
  English/Telugu call prompts, opt-out detection, and Spring status/summary callbacks. The legacy OpenAI/SIP
  provider remains selectable with `CALL_PROVIDER=openai_sip`.
- **Speech engine** — independently selectable for Edesy calls: local Hugging Face (`SPEECH_ENGINE=hf`, default)
  or OpenAI Realtime (`SPEECH_ENGINE=openai_realtime`). The OpenAI speech engine does not replace Edesy call
  control and does not use OpenAI SIP.
- **`dashboard`** — the Campex React UI: per-client marketing graphs (leads, ad sales, calling-agent sales, ad reach by
  location), plus the campaign studio, lead-review queue and channel setup. It only talks to the lead
  service; the lead service proxies AI copy requests to FastAPI.

```
 Meta WhatsApp ──webhook──▶ ┌─────────────────────────┐  POST /api/calls          ┌─────────────────────────┐
 Ad popup ──POST /api/ad-leads▶ │ Spring Boot lead service │ ────────────────────────▶ │ FastAPI voice service   │──▶ Edesy Calls API ──▶ PSTN
 Dashboard ──/api/*──────▶ │ leads · consent · calls │ ◀── POST /internal/        │ Edesy media adapter     │◀── 16 kHz L16 WebSocket
                           │ scheduler · WhatsApp   │      call-events/summary  │ selected speech engine  │
                           └───────────┬────────────┘                          └───────────┬─────────────┘
                                       │                                              private or WSS
                                  ┌────▼────┐                                  ┌──────────▼───────────┐
                                  │Postgres │                                  │HF S2S or OpenAI      │
                                  └─────────┘                                  │Realtime speech       │
                                                                               └──────────────────────┘
```

## Layout

```
AI-VOICE-PLATFORM/
├── springboot-lead-service/
│   └── src/main/java/com/aivoice/lead/
│       ├── controller/   WhatsAppController, LeadController, CallController, HealthController,
│       │                 ClientController, SalesController, AnalyticsController
│       ├── service/      WhatsAppService, LeadService, ConsentService, CallOrchestratorService,
│       │                 ClientService, SalesService, AnalyticsService
│       │                 (+ LanguageService, FollowUpPolicy, PhoneNumbers, VoiceServiceClient, LeadMapper)
│       ├── repository/   Spring Data repositories + AnalyticsRepository (SQL aggregations)
│       ├── entity/       Lead, LeadConsent, VoiceCall, WhatsAppEvent, Client, Ad, Sale
│       ├── config/       AppProperties, AppConfig (CORS), ApiKeyFilter, ApiExceptionHandler
│       └── dto/          JSON shapes (snake_case)
│   └── src/main/resources/db/migration/    V1__init.sql, V2__clients_sales_reach.sql  ← the schema (Flyway)
├── fastapi-voice-service/
│   ├── api/              call_controller.py, edesy_webhook.py, media_edesy.py
│   ├── services/         hf_realtime.py, openai_realtime_speech.py, telephony_service.py,
│   │                     conversation_service.py, lead_client.py
│   ├── prompts/          English and Telugu agent/greeting templates (.txt)
│   └── main.py
├── dashboard/            React + Vite + Recharts UI
├── infrastructure/       docker-compose.yml, postgres/, hf-speech-to-speech/, indic-tts/
└── README.md
```

Spring data schema extensions are applied with Flyway. V5 adds the single-admin/Meta OAuth tables; V6 converts their
fixed-width hash columns to `VARCHAR(64)` to match Hibernate's entity mappings.

## Who owns what

| Concern | Owner | Why |
| --- | --- | --- |
| WhatsApp webhook, Meta secrets, sending messages | lead service | Meta secrets stay out of the voice service |
| Leads, consent, "not interested" deletion | lead service | One owner for personal data |
| Deciding to call: consent, ad follow-up window, one call per day | lead service | Business rules, not telephony |
| Database and schema | lead service | The voice service never touches Postgres |
| Edesy API/media credentials and HF endpoint | voice service | Telephony/model secrets stay out of the lead service |
| What the agent says (prompts, greeting), Telugu/English | voice service | Changes with the model, not the business rules |
| Listening to the live call, detecting "not interested" | voice service | It is the only one on the line |

## How a call happens

1. **Lead arrives** — from the ad popup (`POST /api/ad-leads`) or a WhatsApp message. The lead service sends the ad
   details and asks for explicit AI-assisted marketing-call permission. Ad leads get a follow-up window **from
   tomorrow, for 7 days** (business timezone); that window is not consent.
2. **Scheduler** (`CallOrchestratorService`, every few seconds) queues one call per day only for leads with active
   recorded call consent, then claims queued calls (`FOR UPDATE SKIP LOCKED`) and marks them `initiating` *before*
   dialling. No response to the WhatsApp ad message means no call.
3. **Dispatch** — it `POST`s `/api/calls` to the voice service with the number, language, name and ad details.
   With `CALL_PROVIDER=edesy`, FastAPI calls Edesy's `/v1/calls` with a stream URL and returns Edesy's outbound SID.
4. **Audio** — Edesy opens `/media/edesy`, sends a JSON metadata frame, then bidirectional binary L16 PCM.
   FastAPI bridges audio to the selected HF or OpenAI Realtime speech engine and streams generated audio back.
5. **Events and summary** — Edesy's signed callback reports ringing/answer/end/failure; FastAPI forwards normalized
   statuses to `POST /internal/call-events` and a transcript-grounded summary from the selected speech engine to
   `POST /internal/call-summaries`.
6. **Opt-out on the call** — if the caller says they are not interested, the voice service hangs up, reports
   `not_interested`, and the lead service **deletes the lead**. The same happens if they say so on WhatsApp
   (the lead service then asks the voice service to hang up any live call).
7. **Reply on WhatsApp** — cancels queued calls; the daily calls stop.

### Contract between the services

Lead → voice, `POST /api/calls` (header `X-Service-Key`):

```json
{ "call_id": "uuid", "lead_id": "uuid", "destination": "+919800000001", "language": "te",
  "first_name": "Ravi", "business_name": "Acme", "business_description": "…",
  "ad_headline": "Summer sale", "ad_body": "20% off", "follow_up": true }
```

Response `200 {"session_id": "…", "status": "initiated"}`. Failures: `422` bad request, `503` telephony not
configured, `502` provider definitely refused, `504` provider may have dialled anyway.

Voice → lead, `POST /internal/call-events` (header `X-Service-Key`):

```json
{ "event_id": "uuid", "call_id": "uuid", "session_id": "…", "type": "ringing|answered|failed|closed|not_interested", "detail": null }
```

Events are idempotent and may arrive out of order; the lead service never moves a call backwards.

**Never auto-retry a call start that might have dialled.** A `504`, a timeout, or a crash after `initiating` marks
the call `initiation_unknown` and blocks further queuing for that lead until a person checks.

## Clients, sales and reach (the marketing dashboard)

The React dashboard is organized into **Campaign studio**, **Performance**, **Lead review**, **Channels**, and
**Clients**. Campaign drafts and image previews are browser-local in this first slice; they are not uploaded or
published. The Publish button remains disabled until real provider OAuth/API connectors, media hosting, spend
limits, and publish-status callbacks are implemented and configured. The Channels page lists the credentials and
permissions each connector will require; it deliberately does not claim a connected/published state.

The AI copy bar calls `POST /api/ai/campaign-assist`. Spring authenticates the dashboard request and proxies it to
FastAPI. Configure `AI_PROVIDER=openai` with `AI_API_KEY`, or select `openai_compatible` for a private compatible
model such as Ollama/vLLM. The prompt uses only the submitted copy and asks the model not to invent property facts.
Review generated copy before use.

Lead review decisions are stored separately as `pending`, `accepted`, or `rejected` via Flyway migration V3.
Rejecting cancels queued calls and prevents future scheduled follow-up; accepting is an operational quality
decision, **not** customer call consent. The existing stored explicit consent remains mandatory for calling.

Each client has `voice_agent_enabled` and a curated, plain-text `knowledge_base` (up to 12,000 characters) via
Flyway migration V4. Existing clients remain voice-enabled during migration; newly created clients default to
ad-only. Disabling voice cancels queued calls and prevents subsequent calls for that client while leaving
campaign/lead reporting available. Enabling voice does not bypass explicit call consent. The current knowledge
base is manually maintained text inserted into that client’s prompt as reference data; document upload, embeddings,
and retrieval are not implemented in this phase.

One deployment serves several marketing **clients**. Every lead belongs to one client, and the dashboard has a
client switcher (plus "All clients") and a 7 / 30 / 90 day period. Sections:

| Section | What it shows |
| --- | --- |
| **Overview** | Leads vs sales per day, revenue split by channel, lead→sale and call-answer rates; with "All clients", a per-client comparison |
| **Ad sales** | Sales from ad leads: revenue by day, revenue by ad, a table of every ad (reach, leads, lead rate, sales, revenue) |
| **Calling agent** | Calls placed vs answered per day, agent revenue by day, answer and sale rates, recent sales |
| **Ad reach** | People each ad reached, by location: bar chart, ad × location chart, top locations per ad, and an importer |
| **Clients** | Add clients, register each client's Meta ad ids |

**Which client does a lead belong to?** The popup may send `client` (the slug shown on the Clients page). Otherwise
the ad's owner is looked up from the registered ad (`ad_id` or `campaign_id`); a click-to-WhatsApp lead is matched
the same way from the ad referral. Anything else goes to the default client, which speaks as `BUSINESS_NAME`.
Other clients use their own name and description in WhatsApp messages and on calls. An unknown `client` slug is
rejected with `400` rather than guessed.

**Where the numbers come from — read this before trusting them:**

- **Sales are entered by hand** (lead drawer → *Record sale*, or the forms in the Ad sales / Calling agent sections).
  The platform sees leads and calls, not purchases, so nothing is detected automatically. A sale recorded on a lead
  takes that lead's client and ad. *Channel* is chosen by whoever records it: `ad` (came through the ad) or
  `calling_agent` (closed after the voice agent's call).
- **Reach is imported, not pulled from Meta.** Paste rows from an Ads Manager export (region breakdown):
  `ad_id, ad_name, location, reach, impressions, date`. A row for the same ad, location and day replaces the old one
  (an empty impressions cell clears it). Reach is people per ad, location and day, so adding days counts a person
  once per day they were reached; the dashboard labels it as such.
- **Deleting a lead (opt-out) removes it from lead counts**, so past lead graphs shrink when people opt out. Sales are
  kept (`lead_id` becomes null) so revenue does not change.
- Money is one currency per deployment (`APP_CURRENCY`, an ISO code, default `INR`).
- There is still **one WhatsApp number and one voice caller ID** for all clients; per-client WhatsApp credentials are not
  built.

## Run it

### Docker

```bash
./run-local.sh
```

For local application development without Docker, the same command starts FastAPI, Spring, and the dashboard
natively. Configure PostgreSQL via `springboot-lead-service/local.properties` or `DATABASE_*` environment values.
The native mode waits for the Spring `/health` endpoint before starting Vite, avoiding its `/api` proxy connection
refused error. Press Ctrl+C to stop all three services; logs are written under `.run-local/`.

Native mode does not start a local speech model. Edesy calls need `HF_SPEECH_WS_URL` in the FastAPI `.env` to point
to a reachable HF Realtime service. To run the full Docker stack including local HF models, use:

```bash
./run-local.sh up --docker
```

Docker mode creates ignored environment files from the examples if missing. Full HF mode requires Docker Compose v2,
Linux/NVIDIA Container Toolkit, and an accepted gated-model token. `./run-local.sh up --docker --core-only` runs the
Compose core without model containers. Docker management commands are `./run-local.sh status --docker`,
`./run-local.sh logs --docker`, and `./run-local.sh down --docker`; `down` keeps database/model volumes. The
launcher does not delete or prune the upstream `speech-to-speech/` checkout.

- Dashboard http://localhost:5173 · Lead service http://localhost:8080 · Voice service http://localhost:8000
- Point Meta's webhook at `https://<public-host>/webhooks/whatsapp` (same path as before) on the **lead service**.
- Put `EDESY_API_KEY`, the provisioned `EDESY_FROM_NUMBER`, a long random `EDESY_STREAM_TOKEN`, and the Edesy
  status-signing `EDESY_WEBHOOK_SECRET` in `voice-service.env`. Set `EDESY_MEDIA_WS_URL` to a **public WSS URL
  owned by this deployment**, such as `wss://voice.example.com/media/edesy`; `voice-api.edesy.in` is Edesy's API
  host, not the agent media endpoint. Configure the HTTPS status callback at
  `https://voice.example.com/webhooks/edesy/status` and terminate TLS at a reverse proxy that supports WebSockets.
- To use OpenAI speech-to-speech while retaining Edesy for call placement, set `SPEECH_ENGINE=openai_realtime`,
  `OPENAI_API_KEY`, `OPENAI_REALTIME_MODEL` (default `gpt-realtime-2.1`), and `OPENAI_REALTIME_VOICE` (default
  `marin`). The HF engine remains the default. The API key stays server-side; the health endpoint reports the
  selected speech engine and whether its configuration is present. Calls are rejected before dialing if the
  selected engine is not configured.
- Request/accept access to `ai4bharat/indic-parler-tts` and review its current model terms before setting `HF_TOKEN`
  in `hf-model.env`. The TTS service and HF server are not published on host ports. This gated model is used because
  Qwen3-TTS does not list Telugu among its supported languages.
- This initial Compose configuration has one speech pipeline and caps lead dispatch at one active call
  (`VOICE_MAX_CONCURRENT_CALLS=1`, `MAX_CONCURRENT_CALLS=1`). Raise both only after increasing HF `--num_pipelines`
  and measuring the target GPU; five concurrent calls have not been validated.
- Indic Parler does not produce incremental model audio in this adapter: each TTS text segment is generated fully,
  then streamed/paced to Edesy. First-audio latency must be measured on the deployment GPU.
- The HF server installs `speech-to-speech` **1.1.0** from the inspected Apache-2.0 source commit
  `d1dc31deab44510188ffcd7edfc87203ed9077fb`; it uses Silero VAD, Faster Whisper `large-v3-turbo`, and local
  Transformers Qwen. Telugu TTS uses the separate Indic Parler service because the HF S2S Qwen3-TTS backend does
  not list Telugu. The Parler adapter is pinned to source commit `d108732cd57788ec86bc857d99a6cabd66663d68`; its
  model is gated, and the HF model-card metadata lists Apache-2.0. Verify current gated terms and access before use.
- Edesy's outbound Calls API requires an authorized account and registered caller number. No external mobile call
  route is claimed or tested until those provider credentials and account settings are validated.

### Media and interruption contract

- **Edesy → FastAPI:** first WebSocket message is JSON call metadata; audio is binary L16/PCM, signed 16-bit
  little-endian, mono, 16 kHz. Edesy documents frames of about 640 bytes (20 ms).
- **FastAPI → Hugging Face:** with `SPEECH_ENGINE=hf`, the same PCM bytes are base64-encoded into HF's
  `input_audio_buffer.append` event. No sample-rate conversion is involved.
- **FastAPI → OpenAI Realtime:** with `SPEECH_ENGINE=openai_realtime`, stateful SoXR resamplers convert input PCM16LE
  from 16 kHz mono to 24 kHz mono and output PCM16LE from 24 kHz mono back to 16 kHz mono. Each side is base64
  encoded in Realtime WebSocket events. OpenAI server VAD handles turn detection and response interruption; input
  transcription is enabled for the call language. SIP signalling and RTP are not handled by this media adapter.
- **Hugging Face → FastAPI:** `response.output_audio.delta` carries base64 PCM. The local Indic Parler adapter
  emits raw PCM16 at 24 kHz; the HF S2S OpenAI-compatible TTS adapter resamples it to the pipeline's native
  16 kHz before returning Realtime audio.
- **FastAPI → Edesy:** send binary PCM16LE mono at 16 kHz, in 640-byte frames paced at 20 ms. On the selected
  engine's `input_audio_buffer.speech_started`, send Edesy `{"type":"killAudio"}` and flush queued playback.
  On hangup/opt-out, send `{"type":"disconnect"}`.
- The Indic Parler endpoint synthesizes each segment before returning it; pacing the bytes does not make model
  inference incremental. The actual first-audio latency and Telugu quality must be measured on the target GPU.

### Without Docker

```bash
# 1. Postgres 16 with a database `voiceleads` and user `voice`
# 2. Lead service (needs JDK 21 and Maven)
cd springboot-lead-service
DATABASE_URL=jdbc:postgresql://localhost:5432/voiceleads DATABASE_USER=voice DATABASE_PASSWORD=voicepass \
INTERNAL_SERVICE_KEY=change-me BUSINESS_NAME="Acme" mvn spring-boot:run

# 3. Voice service
cd fastapi-voice-service
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
cp .env.example .env     # fill in Edesy and internal-service values
.venv/bin/uvicorn main:app --port 8000

# 4. Dashboard
cd dashboard && npm install && npm run dev      # proxies /api to http://127.0.0.1:8080
```

## Configuration

Lead service (`springboot-lead-service/src/main/resources/application.properties`, all overridable by env; put local secrets such as
`spring.datasource.password` in the git-ignored `springboot-lead-service/local.properties`):

| Variable | Meaning |
| --- | --- |
| `DATABASE_URL` / `DATABASE_USER` / `DATABASE_PASSWORD` | PostgreSQL JDBC URL and separate credentials. The URL must start with `jdbc:postgresql://`; do not place `user:password@` in the URL. For Render, use its hostname and database name to form `jdbc:postgresql://<host>:5432/<database>?sslmode=require`, then set username/password separately. For Railway, see the service-variable setup below. |
| `BUSINESS_NAME`, `BUSINESS_DESCRIPTION` | Used in WhatsApp messages and sent to the voice service for prompts |
| `BUSINESS_TIMEZONE` (`Asia/Kolkata`), `FOLLOW_UP_CALL_DAYS` (`7`) | "Tomorrow" and the one-call-per-day rule use this timezone |
| `META_VERIFY_TOKEN`, `META_APP_SECRET`, `WHATSAPP_ACCESS_TOKEN`, `WHATSAPP_PHONE_NUMBER_ID`, `WHATSAPP_GRAPH_API_VERSION` | Meta |
| `INTERNAL_SERVICE_KEY` | Shared secret for lead ⇄ voice calls |
| `AD_POPUP_TOKEN`, `CORS_ORIGINS` | Public popup and allowed direct browser origins |
| `FIREBASE_PROJECT_ID` | Firebase project used to verify Google/Facebook ID tokens for the dashboard |
| `CAMPEX_ADMIN_PASSWORD_HASH`, `CAMPEX_ADMIN_SESSION_TTL_SECONDS` | Optional legacy password login; unused when Firebase auth is configured |
| `META_APP_ID`, `META_APP_SECRET`, `META_OAUTH_REDIRECT_URI`, `META_GRAPH_API_VERSION`, `META_TOKEN_ENCRYPTION_KEY`, `META_OAUTH_SCOPES`, `CAMPEX_FRONTEND_URL` | Server-side Meta OAuth and token encryption |
| `VOICE_SERVICE_URL`, `VOICE_SERVICE_TIMEOUT_SECONDS` | Where the voice service is |
| `APP_CURRENCY` (`INR`) | Currency code shown for sales and revenue |
| `ENVIRONMENT` | Anything other than `development` makes missing keys a hard `503` instead of "open" |

### Railway variables for the Spring lead service

The source root is `/AI-VOICE-PLATFORM/springboot-lead-service` when the GitHub repository root is the parent of
`AI-VOICE-PLATFORM`; otherwise use `/springboot-lead-service`. The directory contains a [Dockerfile](./springboot-lead-service/Dockerfile).
Set runtime values in the Spring service's **Variables** tab, not in the Dockerfile. Railway injects `PORT`; the app
already binds to it.

After adding a Railway PostgreSQL service, set the Spring service variables below. Replace `Postgres` and
`voice-service` with the exact service names in your Railway project:

```dotenv
ENVIRONMENT=production
DATABASE_URL=jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}
DATABASE_USER=${{Postgres.PGUSER}}
DATABASE_PASSWORD=${{Postgres.PGPASSWORD}}
BUSINESS_NAME=Campex
BUSINESS_DESCRIPTION=Describe the business and its offering
BUSINESS_TIMEZONE=Asia/Kolkata
FOLLOW_UP_CALL_DAYS=7
CALL_POLL_SECONDS=3
VOICE_MAX_CONCURRENT_CALLS=1
FIREBASE_PROJECT_ID=campex-f3ada
VOICE_SERVICE_URL=http://${{voice-service.RAILWAY_PRIVATE_DOMAIN}}:8000
VOICE_SERVICE_TIMEOUT_SECONDS=30
INTERNAL_SERVICE_KEY=use-the-same-random-secret-on-the-voice-service
META_VERIFY_TOKEN=your-meta-webhook-verify-token
META_APP_SECRET=your-meta-app-secret
WHATSAPP_ACCESS_TOKEN=your-meta-system-user-token
WHATSAPP_PHONE_NUMBER_ID=your-meta-phone-number-id
WHATSAPP_GRAPH_API_VERSION=your-supported-graph-api-version
META_APP_ID=your-meta-business-app-id
META_OAUTH_REDIRECT_URI=https://campex-production.up.railway.app/oauth/meta/callback
META_GRAPH_API_VERSION=v27.0
META_LOGIN_CONFIG_ID=your-facebook-login-for-business-config-id
META_OAUTH_SCOPES=ads_management,ads_read,business_management,pages_show_list,pages_read_engagement,instagram_basic,instagram_manage_insights
META_TOKEN_ENCRYPTION_KEY=<base64-encoded-32-byte-key>
CAMPEX_FRONTEND_URL=https://campexdashboard.vercel.app
AD_POPUP_TOKEN=use-a-separate-random-secret
CORS_ORIGINS=https://campexdashboard.vercel.app
```

Railway's PostgreSQL `DATABASE_URL` is a `postgresql://` URI; this Spring app requires a JDBC URL, so construct it
from the PostgreSQL service's `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, and `PGPASSWORD` references as shown.
Keep generated secrets only in Railway Variables. Dashboard login is Firebase Google/Facebook; Meta connections
are stored against the signed-in Firebase account email. The `INTERNAL_SERVICE_KEY` must match on Spring and
FastAPI; configure `VOICE_SERVICE_URL` to the reachable FastAPI service. Generate `META_TOKEN_ENCRYPTION_KEY`
with `openssl rand -base64 32` and keep a backup: losing it makes stored Meta tokens unreadable. The checked-in
[lead-service env template](./infrastructure/lead-service.env.example) is for local/Docker setup, not a secret store.

For the FastAPI Railway service, set `LEAD_SERVICE_URL=http://${{lead-service.RAILWAY_PRIVATE_DOMAIN}}:8080` and
the same `INTERNAL_SERVICE_KEY`. Replace `lead-service` and `voice-service` in Railway references with the exact
service names you use.

For Vercel, set the server-side function variable `CLIENT_SERVICE_URL=https://campex-production.up.railway.app` in the
Vercel project. Do not prefix it with `VITE_`: the `[...path].js` Vercel function proxies dashboard requests to Spring,
and the backend URL is not embedded in browser code. The proxy forwards the HttpOnly admin session cookie. Set
`CAMPEX_FRONTEND_URL=https://campexdashboard.vercel.app` on Spring; that is where the OAuth callback returns the user.
FastAPI has its own `LEAD_SERVICE_URL`, which should point privately to Spring, for example
`http://${{lead-service.RAILWAY_PRIVATE_DOMAIN}}:8080`. `CORS_ORIGINS` is only needed for any direct browser-to-Spring
calls; the Vercel proxy keeps dashboard API calls same-origin. See the [dashboard env template](./dashboard/.env.example)
and [voice-service env template](./infrastructure/voice-service.env.example) for their remaining settings.

### Auth architecture (two separate Meta products)

Campex uses **two different identity flows**. Do not put Facebook Login for Business credentials into Firebase.

| Layer | Purpose | Product | Where |
| --- | --- | --- | --- |
| **1. Campex user authentication** | Sign into the dashboard | **Firebase** (Google, consumer Facebook Login, email/password) | Login screen |
| **2. Connect Meta Ads account** | Grant Business assets | **Meta Business Login** | Channels → Connect Meta Business |

#### 1) Firebase — Campex user authentication

Enable in Firebase Console → Authentication → Sign-in method: **Google**, **Facebook**, **Email/Password**.

For Firebase **Facebook Login**, use a **consumer** Meta app (use case *Authenticate and request data from users with Facebook Login*):

1. Use cases → Edit → **Add** `email` (keep `public_profile`).
2. Facebook Login → Settings: enable Client/Web OAuth Login.
3. Valid OAuth Redirect URI: `https://campex-f3ada.firebaseapp.com/__/auth/handler`
4. App Domains: `campex-f3ada.firebaseapp.com`, `campexdashboard.vercel.app`
5. Paste that app’s App ID + App Secret into Firebase → Facebook provider.
6. Firebase Authorized domains: include `campexdashboard.vercel.app` and `localhost`.

Do **not** use Facebook Login for Business for this layer — that causes “needs at least one supported permission” when only `email` is requested.

#### 2) Meta Business Login — Connect Meta Ads account

Used only by **Channels** to load Facebook Business assets: ad accounts, Pages, Instagram professional accounts, and campaign permissions (`ads_management`, `ads_read`, `business_management`, `pages_*`, `instagram_*`). Graph API version **v27.0**.

1. Meta App Dashboard → add **Facebook Login for Business**.
2. **Configurations** → create a config with at least:
   - `ads_management`, `ads_read`
   - `business_management`
   - `pages_show_list`, `pages_read_engagement`
   - `instagram_basic`, `instagram_manage_insights`
3. Valid OAuth Redirect URI: `https://campex-production.up.railway.app/oauth/meta/callback`
4. App Domains: `campex-production.up.railway.app`, `campexdashboard.vercel.app`
5. Railway variables:
   - `META_APP_ID` / `META_APP_SECRET` (Business app)
   - `META_OAUTH_REDIRECT_URI=https://campex-production.up.railway.app/oauth/meta/callback`
   - `META_LOGIN_CONFIG_ID=<configuration id from step 2>`
   - `META_GRAPH_API_VERSION=v27.0`
   - `META_TOKEN_ENCRYPTION_KEY` (`openssl rand -base64 32`)
   - `CAMPEX_FRONTEND_URL=https://campexdashboard.vercel.app`

The callback exchanges the code server-to-server, validates OAuth `state`, loads profile / Pages / Instagram / ad accounts, and stores tokens encrypted with AES-256-GCM. Status responses never include access tokens.

Campaign Studio **Publish** calls `POST /api/meta/campaigns/publish` and creates a **PAUSED** Meta campaign and ad set using the connected Business token. The current dashboard does not upload its selected image as a Meta creative; finish the ad in Ads Manager before activating.

### CAMPEX Meta Ads MCP

CAMPEX hosts a private FastMCP server inside `fastapi-voice-service` at `/mcp/meta-ads`. The Campaign AI uses FastMCP's in-process client to discover and call read-only account, campaign, ad-set, ad, and Insights tools. The existing Spring backend authenticates the Firebase user and supplies that user's encrypted Meta token to FastAPI over the internal service connection; the token is not included in the OpenAI prompt, model-visible tool schema, browser response, or logs. The AI receives only the requested Meta result data for its response.

```mermaid
flowchart LR
  UI[React dashboard] -->|Firebase ID token| Spring[Spring Boot auth and account boundary]
  Spring -->|private service key + user Meta token| AI[FastAPI campaign assistant]
  AI -->|MCP Client| MCP[FastMCP tools]
  MCP -->|Meta token in Authorization header| Graph[Meta Marketing API]
```

The HTTP MCP endpoint also requires the internal `X-Service-Key`; do not publish that key in a browser or MCP client config. The cloned `google-ads-meta-ads-mcp` directory contains a Ryze hosted-connector configuration, not a self-hosted server implementation. CAMPEX does not call that third-party endpoint or send account credentials to it. The local audit prompt is `fastapi-voice-service/prompts/meta_ads_audit.txt`, adapted from the supplied Meta Ads audit skill without its Ryze connection instructions.

The AI assistant exposes read-only tools for account/page/Instagram discovery, campaigns, ad sets, ads, and account/campaign/ad-set/ad insights. It does not invoke write tools from natural-language chat. Campaign Studio creation requires an explicit browser confirmation and creates objects as **PAUSED**; status-change and delete tools also require an explicit confirmation argument. Image upload, lead-form retrieval, Ad Library search, audience-overlap measurement, and budget/creative editing are not implemented in this integration. Live Meta API behavior still needs verification with an approved test account; no live campaign was created during development.

Set `META_GRAPH_API_VERSION=v27.0` in the voice-service environment. The MCP HTTP endpoint uses the existing `INTERNAL_SERVICE_KEY`; keep the same key configured on the Spring and FastAPI services.

Voice service (`fastapi-voice-service/.env.example`): `CALL_PROVIDER=edesy` (legacy `openai_sip` remains available),
`SPEECH_ENGINE=hf|openai_realtime`, Edesy settings, and `MAX_CONCURRENT_CALLS`. HF mode uses `HF_SPEECH_WS_URL`;
OpenAI Realtime mode uses `OPENAI_API_KEY`, `OPENAI_REALTIME_WS_URL`, `OPENAI_REALTIME_MODEL`,
`OPENAI_REALTIME_VOICE`, and `OPENAI_REALTIME_TRANSCRIPTION_MODEL`. `LEAD_SERVICE_URL` and
`INTERNAL_SERVICE_KEY` are also required for service integration. OpenAI Realtime speech is independent of the
legacy OpenAI SIP settings; ElevenLabs remains preview-only. `VOICE_MAX_CONCURRENT_CALLS` on Spring must not exceed
the available capacity of the selected speech engine.
`hf-model.env` separately holds the gated Hugging Face token and GPU settings; do not copy it into the public voice
service environment or commit its real values.

## HTTP API

Lead service — dashboard APIs require the CAMPEX admin session cookie (the Vercel same-origin proxy forwards it):
`POST /auth/login` (email/password; sets an HttpOnly cookie), `GET /auth/session`, `POST /auth/logout`,
`GET /api/dashboard`, `GET /api/leads`
(`status`, `language`, `q`, `limit`, `offset`), `GET /api/leads/{id}`, `PATCH /api/leads/{id}/language`,
`GET /api/calls`. `dashboard`, `leads` and `calls` take an optional `client_id`.

Meta OAuth: `GET /oauth/meta/login` (admin session required; redirects to Meta), `GET /oauth/meta/callback`
(Meta redirect; one-time state), `GET /oauth/meta/status`, and `DELETE /oauth/meta/connection`. The Vercel dashboard
uses `/api/oauth/meta/*` through its server-side proxy; only Meta redirects directly to the Railway callback URL.

Clients, sales, analytics (same admin session): `GET|POST /api/clients`, `GET|POST /api/clients/{id}/ads`,
`POST /api/clients/{id}/ad-reach` (up to 5000 rows), `GET /api/sales` (`client_id`, `channel`, `limit`, `offset`),
`POST /api/sales`, `DELETE /api/sales/{id}`, `GET /api/analytics?client_id=&days=` (omit `client_id` for all
clients; days 7–365), `GET /api/analytics/clients?days=`.

Public: `POST /api/ad-leads` (header `X-Ad-Popup-Token`; optional `client` slug), `GET|POST /webhooks/whatsapp`
(HMAC-verified), `GET /health`. Internal: `POST /internal/call-events` (`X-Service-Key`).

Voice service: `POST /api/calls`, `POST /api/calls/{session_id}/hangup` (`X-Service-Key`),
`POST /webhooks/voice/events` (`X-Webhook-Secret`; legacy provider), `POST /webhooks/edesy/status` (Edesy
timestamped HMAC), `WS /media/edesy` (Edesy metadata + bidirectional binary L16), and `GET /health`.

Dashboard campaign copy assistant: `POST /api/ai/campaign-assist` (dashboard auth). Spring forwards the request to
FastAPI using `X-Service-Key`; configure `AI_PROVIDER=openai` plus `AI_API_KEY`, or
`AI_PROVIDER=openai_compatible` with a private OpenAI-compatible `AI_BASE_URL` and `AI_MODEL` (for example a secured
Ollama or vLLM endpoint). The provider key is never sent to React.

Lead review: `GET /api/leads?review_status=pending|accepted|rejected`, `GET /api/leads/review-stats`, and
`PATCH /api/leads/{id}/review` with `{"review_status":"accepted"|"rejected"|"pending"}`. Rejection cancels queued
calls and prevents future automatic follow-up. Acceptance does not create call consent.

Internal summary write: `POST /internal/call-summaries` (`X-Service-Key`); only the locally generated summary is
persisted to the existing call row. Raw audio and transcript are kept in memory only and are not sent to Spring.

## Tests

```bash
cd springboot-lead-service && mvn test
cd fastapi-voice-service && .venv/bin/pytest
cd dashboard && npm test && npm run build
```

## Things to know

- **Live-call state is in the voice service's memory.** If it restarts mid-call, the lead service never hears the
  end; its sweeper closes calls older than 2 hours as `lost_contact` so the lead is not stuck. Run one voice
  service instance (or add shared state before scaling it out).
- **Opt-out detection on a call is phrase matching** on the caller's transcript, and it permanently deletes the
  lead. A bare "stop" is deliberately not treated as an opt-out, but the Telugu phrase `వద్దు` ("don't want") is,
  as before. Review `is_not_interested` in `conversation_service.py` before relying on it.
- **Deleting a lead deletes its consent evidence and call history** (`ON DELETE CASCADE`). Keep a separate
  audit log if you need one for compliance.
- Calling people who only entered their number on an ad popup is a legal question that varies by country
  (consent / do-not-call rules). This integration requires an explicit `CALL` reply before an ad lead can be queued;
  have counsel confirm wording, retention, DND screening, and operating requirements for your use case.
- Rotate any API, SIP/CPaaS, database, or internal-service credentials that were previously placed in source or
  example configuration. Use ignored local env files or a secret manager instead.
- The schema is managed by Flyway. Hibernate only validates it (`ddl-auto: validate`).
