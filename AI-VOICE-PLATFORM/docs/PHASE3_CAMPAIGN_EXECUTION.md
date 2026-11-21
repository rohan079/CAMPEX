# Phase 3 — Campaign execution & automation

Core rule: **AI recommends and prepares. A person approves. Spring Boot validates and executes. Campex records the
result.** Nothing in Phase 3 lets an AI model, a rule or a stale browser tab change a live Meta campaign without an
approval recorded against the exact configuration being executed.

Phase 3 is delivered in increments. This document is updated with each one; sections marked *planned* are not built.

| # | Increment | Status |
|---|-----------|--------|
| 1 | Campaign execution engine: drafts, validation, approval, idempotent PAUSED publish, reconciliation, notifications | **built** |
| 2 | Campaign Studio → approval flow; Campaign approvals page (history, validation, approvals, actions); notification bell | **built** |
| 3 | Creative Studio: AI copy with claim checks, artwork generation/upload, persistent storage, versions, review, attach to campaigns | **built** |
| 4 | Optimization actions: pause / resume / daily budget, previewed from Meta, approved, applied once, verified | **built** |
| 5 | Automation rules (levels 1–3; level 4 off by default) | planned |
| 6 | Experiment execution on top of Phase 2 experiments | planned |

---

## 1. Existing implementation assessment (gap analysis)

What exists before Phase 3, verified in the code:

| Area | What exists | Gap for Phase 3 |
|---|---|---|
| Campaign Studio (`dashboard/src/CampaignStudio.tsx`) | Single form: objective, platforms, location, ages, headline, body, CTA; AI copy/image suggestions; **drafts in browser `localStorage` only** | No server-side draft, no versions, no validation report, no approval |
| Publishing (`MetaCampaignPublishService`, `POST /api/meta/campaigns/publish`) | Owner-email scoped; `window.confirm` then a direct call to the FastAPI `create_paused_campaign` MCP tool; creates **campaign + ad set, both PAUSED**; no ad (no Meta image upload) | Not client-scoped, no approval record, **no idempotency** (double click = two campaigns), timeouts are not reconciled, result not stored |
| Meta MCP (`fastapi-voice-service/services/meta_ads_mcp.py`) | Read tools; write tools (`create_paused_campaign`, `set_campaign_status`, `update_ad_set`, `update_ad_status`, `create_ad_set`, `create_ad`, `create_ad_creative`, `delete_campaign`) guarded only by a `confirmed` flag; endpoint protected by the internal service key | Write tools have no notion of approval; must only be reachable from Spring's execution engine |
| AI assistant (`services/campaign_assistant.py`) | Allowlisted to `READ_ONLY_META_TOOLS` — the model cannot call write tools | Keep it that way (verified) |
| Creative generation | OpenAI Images via the assistant; images returned to the browser only | **No persistent storage**, no asset library, versions or approval |
| Phase 2 recommendations | Evidence-backed, `proposed_change` JSON, accept/reject recorded; never executed | Needs an execution path (increment 4) |
| Phase 2 experiments | Design, start/complete, statistics, "no reliable winner" outcome | Variants are not published from Campex (increment 6) |
| Anomalies / budget policies | Deterministic detection, `client_budget_policies` (daily/monthly limits) | Rules are fixed; no configurable rules engine (increment 5). Budget policy is reused by Phase 3 validation |
| Access control (`ClientAccessService`) | Per-client membership, roles `owner` / `manager` / `agent` / `viewer`, platform admins | Spec roles map onto these (see §7) |
| Audit (`audit_events`, `AuditService`) | Generic per-client audit trail | Reused for every Phase 3 transition |
| Notifications | None | New table + API (increment 1) |
| Tests | Unit tests + Postgres integration tests gated on `CAMPEX_IT_DATABASE_URL`; Meta always mocked | Same approach for Phase 3 |

## 2. Architecture

```mermaid
flowchart LR
  UI[React dashboard] -->|Firebase ID token| API[Spring Boot\nCampaignExecutionController]
  API --> ACC[ClientAccessService\nrole + tenant check]
  API --> ENG[CampaignExecutionService\nstate machine]
  ENG --> VAL[CampaignValidator\npure rules]
  ENG --> DB[(PostgreSQL\nexecutions, versions,\napprovals, steps,\nnotifications, audit)]
  ENG --> GW[MetaExecutionGateway]
  GW -->|X-Service-Key + token header| FAPI[FastAPI /api/meta/*]
  FAPI --> MCP[Meta Ads MCP tools]
  MCP --> META[Meta Graph API]
  AI[AI assistant] -. read-only tools .-> MCP
```

* The execution engine in Spring is the only caller of Meta write tools for Phase 3 flows.
* Meta tokens are resolved server-side (`MetaAccessResolver`) and sent to FastAPI in a header; they never reach the
  browser, an AI prompt, a log line or an error message.
* The engine never holds a database transaction open across a Meta call.

## 3. Campaign execution state machine

```mermaid
stateDiagram-v2
  [*] --> DRAFT
  DRAFT --> READY_FOR_REVIEW: validate (no errors)
  READY_FOR_REVIEW --> PENDING_APPROVAL: submit
  PENDING_APPROVAL --> APPROVED: approve
  PENDING_APPROVAL --> DRAFT: reject
  READY_FOR_REVIEW --> DRAFT: edit
  PENDING_APPROVAL --> DRAFT: edit
  APPROVED --> DRAFT: edit (approval invalidated)
  APPROVED --> EXECUTING: publish
  EXECUTING --> PUBLISHED_PAUSED: Meta confirmed campaign + ad set
  EXECUTING --> FAILED: Meta rejected / nothing created
  EXECUTING --> EXECUTING: outcome unknown (needs reconciliation)
  FAILED --> EXECUTING: retry (after reconciliation finds nothing)
  DRAFT --> CANCELLED
  READY_FOR_REVIEW --> CANCELLED
  PENDING_APPROVAL --> CANCELLED
  APPROVED --> CANCELLED
  FAILED --> CANCELLED
```

* `ACTIVE` exists in the status set but is only reached through an approved activation action (increment 4).
  Publishing always creates PAUSED objects.
* **Local status and Meta status are separate columns.** `status` is Campex's workflow state; `meta_status` /
  `meta_effective_status` are whatever Meta last reported, with `meta_checked_at`. A successful API call is not
  treated as "active".

### Duplicate prevention and reconciliation

1. **Create** accepts an `Idempotency-Key` header; the same key for the same client returns the existing execution.
2. **Publish** moves `APPROVED → EXECUTING` with a conditional `UPDATE … WHERE status = 'APPROVED'`. A second click,
   a second tab or a retrying proxy finds the row already `EXECUTING` and gets `409`.
3. Every execution has a unique **publish tag** (`cx-` + 10 hex chars). The Meta campaign is named
   `"<name> [cx-…]"`, so Campex can find the campaign it created without relying on the response.
4. If the Meta call fails or times out, the engine **reconciles before deciding**: it searches the ad account for the
   tag. Found → `PUBLISHED_PAUSED` (IDs recorded from Meta). Not found → `FAILED`, safe to retry. Lookup itself
   fails → stays `EXECUTING` with `publish_outcome = unknown`; `POST …/reconcile` repeats the lookup later.
5. **Retry never blindly re-creates.** It reconciles first and is refused if a Meta campaign ID is already known.
6. A partially created publish (campaign created, ad set rejected) is `FAILED` with the campaign ID recorded and
   cannot be retried; the paused campaign is finished or deleted in Ads Manager.

### Approval binding

An approval records the approver, timestamp, config version, **SHA-256 of the canonical config**, and the approved
daily budget. Publish re-validates and refuses unless the latest non-invalidated approval matches the current config
hash. Any edit creates a new config version, invalidates approvals and returns the execution to `DRAFT`.

## 4. Validation rules (`CampaignValidator`)

Errors block submit/approve/publish; warnings are shown but do not block.

| Check | Rule |
|---|---|
| Name | 1–140 chars (room for the publish tag) |
| Objective | `leads`, `traffic`, `sales`, `awareness` — the objectives the existing MCP tool maps to Meta `OUTCOME_*` |
| Platforms | non-empty subset of `facebook`, `instagram`, `whatsapp_ads` |
| Location | required, ≤ 160; resolved against Meta's location search at publish (ambiguous → Meta error, shown) |
| Age | 18–65, max ≥ min |
| Daily budget | ≥ 100 minor units; **error** if above the client's `daily_spend_limit` (Phase 2 budget policy) |
| Ad account | must be mapped to this client (`client_ad_accounts`) |
| Meta connection | a usable stored token for this client (checked at approve/publish) |
| Destination URL | required for `traffic`/`sales`; must be `https`, a public host (no localhost / IP literals) |
| Headline | required, ≤ 80; warning above 40 chars (truncation in feed) |
| Primary text | required, ≤ 1500; warning above 125 chars |
| CTA | one of `LEARN_MORE`, `SIGN_UP`, `CONTACT_US`, `MESSAGE_PAGE`, `BOOK_NOW`, `GET_QUOTE`, `APPLY_NOW` |
| Ad creation | **warning**: Campex creates the campaign and ad set (paused); the ad itself is not created until Meta image upload is implemented (§9) |

## 5. Data model (increment 1, migration `V16__campaign_execution.sql`)

```mermaid
erDiagram
  clients ||--o{ campaign_executions : has
  campaign_executions ||--o{ campaign_execution_versions : "config history"
  campaign_executions ||--o{ campaign_approvals : "decisions"
  campaign_executions ||--o{ campaign_execution_steps : "attempt log"
  clients ||--o{ notifications : has
  campaign_executions {
    uuid id PK
    uuid client_id FK
    varchar status
    jsonb config
    int config_version
    varchar config_hash
    varchar idempotency_key "unique per client"
    varchar publish_tag "unique"
    varchar meta_campaign_id
    varchar meta_adset_id
    varchar meta_status
    varchar publish_outcome
    text last_error
  }
  campaign_approvals {
    uuid id PK
    uuid execution_id FK
    int config_version
    varchar config_hash
    bigint approved_daily_budget_minor
    varchar decision
    varchar decided_by
    timestamptz invalidated_at
  }
  campaign_execution_steps {
    uuid id PK
    uuid execution_id FK
    varchar step
    varchar outcome
    text detail
    jsonb meta_ids
  }
  notifications {
    uuid id PK
    uuid client_id FK
    varchar recipient_email
    varchar type
    varchar severity
    timestamptz read_at
  }
```

Every transition also writes an `audit_events` row (actor, entity, action, before/after).

## 6. API (increment 1)

All routes require a Firebase-authenticated dashboard user and client membership. Responses never contain tokens.

| Method | Path | Permission | Notes |
|---|---|---|---|
| POST | `/api/clients/{clientId}/campaign-executions` | MANAGE | Body: config. Optional `Idempotency-Key` header |
| GET | `/api/clients/{clientId}/campaign-executions` | READ | Optional `status` filter |
| GET | `/api/campaign-executions/{id}` | READ | Includes versions, approvals, steps, last validation |
| PUT | `/api/campaign-executions/{id}` | MANAGE | Edit config → new version, back to `DRAFT` |
| POST | `/api/campaign-executions/{id}/validate` | MANAGE | Returns errors/warnings; `DRAFT → READY_FOR_REVIEW` when clean |
| POST | `/api/campaign-executions/{id}/submit` | MANAGE | `READY_FOR_REVIEW → PENDING_APPROVAL`; notifies approvers |
| POST | `/api/campaign-executions/{id}/approve` | MANAGE | Re-validates; binds approval to config hash |
| POST | `/api/campaign-executions/{id}/reject` | MANAGE | Body `{ "note": … }`; back to `DRAFT` |
| POST | `/api/campaign-executions/{id}/publish` | MANAGE | Body `{ "confirm": true }`; creates PAUSED campaign + ad set |
| POST | `/api/campaign-executions/{id}/reconcile` | MANAGE | Looks the publish tag up in Meta; refreshes `meta_status` |
| POST | `/api/campaign-executions/{id}/retry` | MANAGE | `FAILED` only; reconciles first |
| POST | `/api/campaign-executions/{id}/cancel` | MANAGE | Not allowed once executing/published |
| GET | `/api/notifications` | signed-in | Current user's notifications; `?unread=true` |
| POST | `/api/notifications/{id}/read` | recipient | |
| POST | `/api/notifications/read-all` | signed-in | |

Internal FastAPI additions (service-key protected, read-only): `POST /api/meta/campaigns/lookup` (find campaigns by
name fragment in one ad account) and `POST /api/meta/campaigns/status` (current status/effective status of one
campaign). The existing `POST /api/meta/publish` is reused for creation.

The legacy `POST /api/meta/campaigns/publish` stays for backward compatibility, but the dashboard no longer calls it:
Campaign Studio's button is now **Send for approval** (create or update the server draft, validate, submit), and
publishing happens from **Campaign approvals** after a recorded approval and an explicit acknowledgement.

## 6a. Creative Studio (increment 3)

```mermaid
stateDiagram-v2
  [*] --> GENERATED: AI artwork
  [*] --> EDITING: upload
  GENERATED --> EDITING: edit (new version)
  EDITING --> REVIEW_PENDING: submit (claims checked)
  REJECTED --> REVIEW_PENDING: submit
  REVIEW_PENDING --> APPROVED: approve (version recorded)
  REVIEW_PENDING --> REJECTED: request changes
  APPROVED --> EDITING: edit (approved version kept)
  GENERATED --> ARCHIVED
  EDITING --> ARCHIVED
  APPROVED --> ARCHIVED
```

* **Copy** (`POST /api/clients/{id}/creative/copy`): the brief plus the client's **verified facts** go to the configured
  LLM (same provider abstraction as Phase 2: OpenAI, Claude or OpenAI-compatible). Output: 5 headlines, 3 primary
  texts, 3 descriptions, CTA recommendations and 2–3 artwork concepts. English is the default; Telugu, Hindi and
  Kannada are offered as beta.
* **Claim check** (deterministic, `fastapi … /api/creative/check`): every number must appear in the verified facts;
  phrases such as guaranteed/assured returns, "double your money", "risk-free", "best investment", approval or
  possession claims not present in the facts are flagged. Generation retries once when its first draft fails the
  check; remaining problems are shown, never hidden.
* **Approval rules**: flagged phrases block approval; numbers not in the facts require the approver to tick "I verified
  the flagged figures", which is recorded on the approval.
* **Artwork** (`POST /api/clients/{id}/creative/generate`): OpenAI Images (`AI_IMAGE_MODEL`); with a reference image
  the GPT Image edits endpoint is used (DALL·E models refuse reference images with a clear message). The prompt
  forbids text, numbers, prices, logos and badges in the image, so factual claims live only in reviewable copy.
  Uploads are accepted as PNG, JPEG or WebP up to 8 MB, checked by magic bytes.
* **Storage**: image bytes are stored in PostgreSQL (`creative_images`, referenced by immutable `creative_versions`);
  provider URLs are downloaded immediately and never stored. Served only to client members via
  `GET /api/creative/images/{id}` with `Cache-Control: no-store`. Moving to object storage later only changes
  `creative_images`.
* **Campaign link**: a campaign draft can attach an approved creative (`creative_asset_id`, `creative_version`).
  Validation then requires that exact approved version and identical headline/primary text, so published copy is
  always the reviewed copy. Drafts without a creative get a warning.

| Method | Path | Permission |
|---|---|---|
| POST | `/api/clients/{clientId}/creative/copy` | MANAGE |
| POST | `/api/clients/{clientId}/creative/generate` | MANAGE |
| POST | `/api/clients/{clientId}/creative/assets` (upload) | MANAGE |
| GET | `/api/clients/{clientId}/creative/assets` | READ |
| GET | `/api/creative/assets/{id}` | READ |
| PUT | `/api/creative/assets/{id}` (new version) | MANAGE |
| POST | `/api/creative/assets/{id}/submit` · `/approve` · `/reject` · `/archive` | MANAGE |
| GET | `/api/creative/images/{imageId}` | READ |

## 6b. Optimization actions (increment 4)

```mermaid
sequenceDiagram
  participant U as Marketer
  participant S as Spring (CampaignActionService)
  participant M as Meta (via FastAPI/MCP)
  U->>S: preview (manual or from a recommendation)
  S->>M: read object state (before)
  S->>S: guardrails (account, no-op, budget policy)
  S-->>U: before / proposed, warnings
  U->>S: approve
  U->>S: execute (confirm)
  S->>S: claim APPROVED→EXECUTING (once)
  S->>M: read state again: unchanged since preview?
  S->>M: apply status / daily budget (only those fields)
  S->>M: read state (after)
  S-->>U: SUCCEEDED verified | mismatch | FAILED
```

* **Supported**: pause and resume a campaign, ad set or ad; set the daily budget of a campaign (CBO) or ad set. Nothing
  is created or deleted. Lifetime budgets, bids and targeting are not changed.
* **Guardrails** (`ActionGuardrails`): the object must belong to an ad account mapped to the client; no no-op changes;
  budget changes must stay within the client's `max_change_pct` (default 20%) and `daily_spend_limit`; resume warns
  that delivery and spend start immediately (and when a parent is paused).
* **Stale protection**: previews expire after 24 hours, and execution re-reads Meta first. If the fields being changed
  differ from the preview, nothing is sent and the change fails with "changed since the preview".
* **One open change per object** (unique index), and `APPROVED → EXECUTING` is a conditional claim, so concurrent
  clicks apply once.
* **Verification**: the result is what Meta reports afterwards, not the API response. Outcomes: `verified`,
  `mismatch` (accepted but not reflected; notification sent) or `unverified` (could not read back). On an error the
  state is read first; if Meta already shows the change it is recorded as applied, otherwise it fails and can be
  retried (status/budget writes set absolute values, so a retry cannot double-apply).
* **Recommendations**: increase/reduce-budget and pause-for-review recommendations pre-fill a preview
  (Phase 2 `proposed_change.to` × 100 → minor units, campaign level). Approving the change marks the recommendation
  accepted. Other recommendation types need the marketer to choose the action.
* Stored per action: `before_state`, `proposed_after`, `actual_after`, guardrail results, approver, executor and an
  event log, plus audit events.
* Fixed along the way: the MCP tools `update_campaign` and `update_ad_set` used to send `status=PAUSED` by default,
  so a rename or budget-only change would have paused the object. They now send only the fields given.

| Method | Path | Permission |
|---|---|---|
| POST | `/api/clients/{clientId}/campaign-actions/preview` | MANAGE |
| GET | `/api/clients/{clientId}/campaign-actions` | READ |
| GET | `/api/campaign-actions/{id}` | READ |
| POST | `/api/campaign-actions/{id}/approve` · `/reject` · `/cancel` | MANAGE |
| POST | `/api/campaign-actions/{id}/execute` (`{"confirm": true}`) | MANAGE |

FastAPI (service key only, not in the AI allowlist): `POST /api/meta/objects/state` (read) and
`POST /api/meta/objects/apply` (write, Spring only).

## 7. Roles and permissions

Spec roles map onto the existing per-client roles (no schema change needed yet):

| Spec role | Campex role | Create/edit/submit | Approve | Publish / retry / reconcile | Read |
|---|---|---|---|---|---|
| OWNER | `owner` | ✓ | ✓ | ✓ | ✓ |
| ADMIN / MARKETER | `manager` | ✓ | ✓ | ✓ | ✓ |
| ANALYST | `agent` | – | – | – | ✓ |
| CLIENT_VIEWER | `viewer` | – | – | – | ✓ |
| Platform admin | `CAMPEX_PLATFORM_ADMIN_EMAILS` | ✓ | ✓ | ✓ | ✓ |

Self-approval is allowed (single freelancers manage their own clients). A per-client "require a second approver"
setting is a planned option. Non-members get `404` (tenant ids cannot be probed); members without the permission get
`403`.

## 8. Configuration

No new environment variables in increment 1. Uses the existing `CAMPEX_PLATFORM_ADMIN_EMAILS`, Meta OAuth settings,
`VOICE_SERVICE_BASE_URL` and internal service key.

## 9. Known limitations and unsupported Meta capabilities

* **Ads are not created yet.** The campaign and ad set are created paused; creating the ad needs an uploaded Meta
  image hash (`/act_{id}/adimages`) and an ad creative. Approved creatives are stored and previewed in Campex, but
  the Meta image upload will be added only after verifying the Graph API flow and permissions on a real test ad
  account.
* Creative images live in PostgreSQL. Fine for single-image ads at agency scale; move to object storage before
  storing video or large volumes.
* Ad set schedule (start/end time), placements beyond the tool's defaults, special ad categories (housing!) and
  detailed targeting are not configurable yet. **Real-estate advertisers in many regions must declare the housing
  special ad category**; the current tool sends an empty list. Until that is configurable, review the campaign in Ads
  Manager before activating it.
* Activating a newly published campaign uses an Optimization action (resume) with approval; it is never automatic.
* Budget amounts assume a two-decimal currency (INR) when converting to Meta's minor units.
* Reconciliation relies on the campaign name containing the publish tag. Renaming the campaign in Ads Manager before
  reconciliation completes would hide it from the lookup.
* Notifications are in-app only; email/WhatsApp channels are planned.

## 10. Tests

* `CampaignValidatorTest` — validation rules, budget policy, URL safety.
* `CampaignExecutionServiceTest` — state machine, approval hash binding, duplicate publish prevention, timeout →
  reconciliation (found / not found / lookup failure), partial creation, retry refusal, role checks. Meta is mocked.
* `CampaignExecutionIntegrationTest` — HTTP + PostgreSQL (Flyway), tenant isolation, viewer cannot publish,
  idempotent create, concurrent publish yields exactly one Meta call. Gated on `CAMPEX_IT_DATABASE_URL`.
* FastAPI: lookup/status routes with a mocked MCP.
