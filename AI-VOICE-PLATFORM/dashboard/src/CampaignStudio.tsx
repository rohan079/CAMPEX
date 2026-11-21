import { useEffect, useMemo, useState } from "react";
import type { ChangeEvent, FormEvent } from "react";
import { ApiError, assistCampaign, getMetaConnection } from "./api";
import { createExecution, submitExecution, updateExecution, validateExecution } from "./executionApi";
import type { CampaignConfig, ExecutionDetail } from "./executionTypes";
import type { MetaConnectionStatus } from "./api";
import type { CampaignPlatform, Client, LanguageCode } from "./types";

const META_PUBLISH_PLATFORMS = new Set<CampaignPlatform>(["facebook", "instagram", "whatsapp_ads"]);

type Objective = "leads" | "traffic" | "sales" | "awareness";
type Draft = {
  id: string;
  clientId: string;
  name: string;
  objective: Objective;
  platforms: CampaignPlatform[];
  language: LanguageCode;
  location: string;
  ageMin: number;
  ageMax: number;
  headline: string;
  body: string;
  cta: string;
  imageName: string;
  savedAt: string;
};
type AssistantMessage = { role: "assistant" | "user"; content: string; suggestedCopy?: string };

const STORAGE_KEY = "campaign-studio.drafts.v2";
const AGE_MIN_FLOOR = 18;
const AGE_MAX_CEILING = 65;
const CHANNELS: Array<{ id: CampaignPlatform; mark: string; label: string; detail: string; color: string }> = [
  { id: "facebook", mark: "f", label: "Facebook", detail: "Feed and stories", color: "facebook" },
  { id: "instagram", mark: "◎", label: "Instagram", detail: "Feed, stories and reels", color: "instagram" },
  { id: "whatsapp_business", mark: "◔", label: "WhatsApp", detail: "Business messages", color: "whatsapp" },
  { id: "google_ads", mark: "G", label: "Google Ads", detail: "Search and display", color: "google" },
  { id: "whatsapp_ads", mark: "↗", label: "WhatsApp Ads", detail: "Click-to-WhatsApp via Meta", color: "whatsapp-ads" },
];

const INITIAL_MESSAGES: AssistantMessage[] = [
  {
    role: "assistant",
    content: "Share a brief and I’ll summarize the offer or refine copy for the selected channels. I won’t add unverified prices or property claims.",
  },
];

function clampAge(value: number, fallback: number): number {
  if (!Number.isFinite(value)) return fallback;
  return Math.min(AGE_MAX_CEILING, Math.max(AGE_MIN_FLOOR, Math.round(value)));
}

function normalizeDraft(raw: Partial<Draft> & { id?: string }): Draft | null {
  if (!raw || typeof raw.id !== "string" || !raw.id) return null;
  const ageMin = clampAge(Number(raw.ageMin ?? AGE_MIN_FLOOR), AGE_MIN_FLOOR);
  let ageMax = clampAge(Number(raw.ageMax ?? AGE_MAX_CEILING), AGE_MAX_CEILING);
  if (ageMax < ageMin) ageMax = ageMin;
  return {
    id: raw.id,
    clientId: typeof raw.clientId === "string" ? raw.clientId : "",
    name: typeof raw.name === "string" ? raw.name : "",
    objective: (raw.objective as Objective) || "leads",
    platforms: Array.isArray(raw.platforms) ? raw.platforms as CampaignPlatform[] : ["facebook", "instagram"],
    language: (raw.language as LanguageCode) || "en",
    location: typeof raw.location === "string" ? raw.location : "",
    ageMin,
    ageMax,
    headline: typeof raw.headline === "string" ? raw.headline : "",
    body: typeof raw.body === "string" ? raw.body : "",
    cta: typeof raw.cta === "string" ? raw.cta : "Learn more",
    imageName: typeof raw.imageName === "string" ? raw.imageName : "",
    savedAt: typeof raw.savedAt === "string" ? raw.savedAt : new Date().toISOString(),
  };
}

function readDrafts(): Draft[] {
  try {
    const keys = [STORAGE_KEY, "campaign-studio.drafts.v1"];
    for (const key of keys) {
      const value: unknown = JSON.parse(localStorage.getItem(key) ?? "null");
      if (!Array.isArray(value)) continue;
      const drafts = value.map((item) => normalizeDraft(item as Partial<Draft>)).filter((item): item is Draft => item !== null);
      if (drafts.length || key === STORAGE_KEY) return drafts;
    }
    return [];
  } catch {
    return [];
  }
}

/** Studio CTA labels → Meta call-to-action types accepted by the execution engine. */
const CTA_TYPES: Record<string, string> = {
  "Learn more": "LEARN_MORE",
  "Send message": "MESSAGE_PAGE",
  "Sign up": "SIGN_UP",
  "Contact us": "CONTACT_US",
};

export function CampaignStudio({
  client,
  onOpenChannels,
  onOpenApprovals,
}: {
  client?: Client;
  onOpenChannels: () => void;
  onOpenApprovals?: (executionId?: string) => void;
}) {
  const [drafts, setDrafts] = useState<Draft[]>(readDrafts);
  const [draftId, setDraftId] = useState<string | null>(null);
  const [name, setName] = useState("");
  const [objective, setObjective] = useState<Objective>("leads");
  const [platforms, setPlatforms] = useState<CampaignPlatform[]>(["facebook", "instagram"]);
  const [language, setLanguage] = useState<LanguageCode>("en");
  const [location, setLocation] = useState("");
  const [ageMin, setAgeMin] = useState(AGE_MIN_FLOOR);
  const [ageMax, setAgeMax] = useState(AGE_MAX_CEILING);
  const [headline, setHeadline] = useState("");
  const [body, setBody] = useState("");
  const [cta, setCta] = useState("Learn more");
  const [image, setImage] = useState<File | null>(null);
  const [imageUrl, setImageUrl] = useState<string | null>(null);
  const [imageError, setImageError] = useState<string | null>(null);
  const [previewPlatform, setPreviewPlatform] = useState<CampaignPlatform>("facebook");
  const [prompt, setPrompt] = useState("");
  const [messages, setMessages] = useState<AssistantMessage[]>(INITIAL_MESSAGES);
  const [aiBusy, setAiBusy] = useState(false);
  const [publishBusy, setPublishBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [metaConnection, setMetaConnection] = useState<MetaConnectionStatus | null>(null);
  const [metaLoading, setMetaLoading] = useState(true);
  const [selectedAdAccountId, setSelectedAdAccountId] = useState("");
  const [selectedPageId, setSelectedPageId] = useState("");
  const [dailyBudget, setDailyBudget] = useState("500");
  const [destinationUrl, setDestinationUrl] = useState("");
  const [submittedId, setSubmittedId] = useState<string | null>(null);
  // The server-side draft this studio session created; edits update it instead of creating another draft.
  const [serverDraftId, setServerDraftId] = useState<string | null>(null);
  const [validationErrors, setValidationErrors] = useState<string[]>([]);
  // One key per form state, so a double click (or a retry after a network error) cannot create two drafts.
  const [submitKey, setSubmitKey] = useState(() => crypto.randomUUID());

  useEffect(() => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(drafts));
  }, [drafts]);

  useEffect(() => {
    setSubmitKey(crypto.randomUUID());
    setSubmittedId(null);
  }, [name, objective, platforms, location, ageMin, ageMax, headline, body, cta, dailyBudget, destinationUrl,
    selectedAdAccountId, selectedPageId, client?.id]);

  useEffect(() => setServerDraftId(null), [client?.id]);

  useEffect(() => {
    let active = true;
    setMetaLoading(true);
    void getMetaConnection()
      .then((status) => {
        if (!active) return;
        setMetaConnection(status);
        setSelectedAdAccountId((current) => current || status.ad_accounts[0]?.id || "");
        setSelectedPageId((current) => current || status.pages[0]?.id || "");
      })
      .catch(() => {
        if (!active) return;
        setMetaConnection(null);
      })
      .finally(() => {
        if (active) setMetaLoading(false);
      });
    return () => { active = false; };
  }, []);

  useEffect(() => {
    if (!image) {
      setImageUrl(null);
      return;
    }
    const next = URL.createObjectURL(image);
    setImageUrl(next);
    return () => URL.revokeObjectURL(next);
  }, [image]);

  const selectedNames = useMemo(
    () => CHANNELS.filter((channel) => platforms.includes(channel.id)).map((channel) => channel.label),
    [platforms],
  );

  const metaPlatforms = useMemo(
    () => platforms.filter((platform) => META_PUBLISH_PLATFORMS.has(platform)),
    [platforms],
  );

  const metaReady = Boolean(
    metaConnection?.connected
      && !metaConnection.expired
      && (metaConnection.ad_accounts?.length ?? 0) > 0,
  );

  const unsupportedSelected = platforms.some((platform) => !META_PUBLISH_PLATFORMS.has(platform));
  const canPublish = metaReady && metaPlatforms.length > 0 && !unsupportedSelected;

  function togglePlatform(platform: CampaignPlatform) {
    setPlatforms((current) => current.includes(platform)
      ? current.filter((item) => item !== platform)
      : [...current, platform]);
    setNotice(null);
  }

  function chooseImage(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0] ?? null;
    setImageError(null);
    if (!file) {
      setImage(null);
      return;
    }
    if (!file.type.startsWith("image/")) {
      setImageError("Choose an image file.");
      event.target.value = "";
      return;
    }
    if (file.size > 10 * 1024 * 1024) {
      setImageError("Images must be 10 MB or smaller.");
      event.target.value = "";
      return;
    }
    setImage(file);
  }

  function saveDraft() {
    setError(null);
    setNotice(null);
    if (!client) {
      setError("Choose a client before saving a campaign draft.");
      return;
    }
    if (!name.trim() || platforms.length === 0 || !body.trim()) {
      setError("Add a campaign name, select at least one channel, and write the ad copy.");
      return;
    }
    if (!location.trim()) {
      setError("Add a target location for the campaign audience.");
      return;
    }
    const nextAgeMin = clampAge(ageMin, AGE_MIN_FLOOR);
    const nextAgeMax = clampAge(ageMax, AGE_MAX_CEILING);
    if (nextAgeMax < nextAgeMin) {
      setError("Maximum age must be greater than or equal to minimum age.");
      return;
    }
    const id = draftId ?? crypto.randomUUID();
    const draft: Draft = {
      id,
      clientId: client.id,
      name: name.trim(),
      objective,
      platforms,
      language,
      location: location.trim(),
      ageMin: nextAgeMin,
      ageMax: nextAgeMax,
      headline: headline.trim(),
      body: body.trim(),
      cta,
      imageName: image?.name ?? "",
      savedAt: new Date().toISOString(),
    };
    setDrafts((current) => [draft, ...current.filter((item) => item.id !== id)]);
    setDraftId(id);
    setNotice(image
      ? "Draft saved in this browser. The selected image file is not stored; choose it again after reloading. Nothing has been published."
      : "Draft saved in this browser. Nothing has been published.");
  }

  function loadDraft(draft: Draft) {
    setDraftId(draft.id);
    setName(draft.name);
    setObjective(draft.objective);
    setPlatforms(draft.platforms);
    setLanguage(draft.language);
    setLocation(draft.location);
    setAgeMin(draft.ageMin);
    setAgeMax(draft.ageMax);
    setHeadline(draft.headline);
    setBody(draft.body);
    setCta(draft.cta);
    setImage(null);
    setError(null);
    setNotice(`Loaded “${draft.name}”. Re-select its image to preview it.`);
  }

  async function askAssistant(event: FormEvent) {
    event.preventDefault();
    const question = prompt.trim();
    if (!question || platforms.length === 0 || aiBusy) return;
    setMessages((current) => [...current, { role: "user", content: question }]);
    setPrompt("");
    setAiBusy(true);
    try {
      const result = await assistCampaign({
        campaign_name: name.trim() || "Untitled campaign",
        objective,
        platforms,
        language,
        prompt: question,
        content: [headline.trim(), body.trim()].filter(Boolean).join("\n\n") || "No draft copy supplied.",
        ad_account_id: selectedAdAccountId || undefined,
        client_id: client?.id || undefined,
        client_name: client?.name || undefined,
      });
      setMessages((current) => [...current, {
        role: "assistant",
        content: result.reply,
        suggestedCopy: result.suggested_copy ?? undefined,
      }]);
    } catch (err) {
      const message = err instanceof Error ? err.message : "The AI assistant is unavailable.";
      setMessages((current) => [...current, { role: "assistant", content: `I couldn’t complete that request: ${message}` }]);
    } finally {
      setAiBusy(false);
    }
  }

  function applySuggestion(copy: string) {
    if (!copy) return;
    setBody(copy);
    setNotice("AI suggestion copied into the ad copy. Review all factual claims before publishing.");
  }

  function clearDraft() {
    setDraftId(null);
    setServerDraftId(null);
    setName("");
    setObjective("leads");
    setPlatforms(["facebook", "instagram"]);
    setLanguage("en");
    setLocation("");
    setAgeMin(AGE_MIN_FLOOR);
    setAgeMax(AGE_MAX_CEILING);
    setHeadline("");
    setBody("");
    setCta("Learn more");
    setImage(null);
    setNotice(null);
    setError(null);
  }

  /**
   * Phase 3: the studio no longer publishes directly. It saves the campaign as a server-side draft, validates it and
   * submits it for approval; publishing happens from Campaign approvals after a recorded approval.
   */
  async function sendForApproval() {
    setError(null);
    setNotice(null);
    setValidationErrors([]);
    if (!client) {
      setError("Choose a client before sending a campaign for approval.");
      return;
    }
    if (!canPublish) {
      setError(metaReady
        ? "Publishing currently supports Facebook, Instagram, and WhatsApp Ads only. Deselect other channels."
        : "Connect Meta Business with at least one ad account under Channels first.");
      return;
    }
    const budgetMinor = Math.round(Number(dailyBudget) * 100);
    if (!Number.isFinite(budgetMinor) || budgetMinor < 100) {
      setError("Enter a daily budget of at least ₹1.");
      return;
    }
    setPublishBusy(true);
    try {
      const config: CampaignConfig = {
        ad_account_id: selectedAdAccountId,
        name: name.trim(),
        objective,
        platforms: metaPlatforms,
        location: location.trim(),
        age_min: clampAge(ageMin, AGE_MIN_FLOOR),
        age_max: clampAge(ageMax, AGE_MAX_CEILING),
        daily_budget_minor: budgetMinor,
        headline: headline.trim(),
        primary_text: body.trim(),
        cta: CTA_TYPES[cta] ?? "LEARN_MORE",
        destination_url: destinationUrl.trim() || null,
        page_id: selectedPageId || null,
      };
      let created: ExecutionDetail | null = null;
      if (serverDraftId) {
        try {
          created = await updateExecution(serverDraftId, config);
        } catch (err) {
          // Already submitted, approved or published: start a new draft instead of changing that one.
          if (!(err instanceof ApiError && (err.status === 409 || err.status === 404))) throw err;
        }
      }
      created ??= await createExecution(client.id, config, submitKey);
      const id = created.execution.id;
      setServerDraftId(id);
      setSubmittedId(id);
      if (created.execution.status === "PENDING_APPROVAL") {
        setNotice("Already sent for approval. Nothing has been published.");
        return;
      }
      const report = await validateExecution(id);
      if (!report.valid) {
        setValidationErrors(report.errors.map((issue) => issue.message));
        setNotice("Saved as a draft in Campex. Fix the issues below, then send it again.");
        return;
      }
      await submitExecution(id);
      setNotice("Sent for approval. Nothing has been published: an approver publishes it as a paused campaign from Campaign approvals.");
    } catch (err) {
      setError(err instanceof ApiError || err instanceof Error ? err.message : "Could not send for approval");
    } finally {
      setPublishBusy(false);
    }
  }

  return (
    <div className="campaign-page">
      <section className="campaign-hero">
        <div>
          <p className="eyebrow">Campaign studio · {client?.name ?? "Select a client"}</p>
          <h2>One campaign. Every channel.</h2>
          <p>Draft your message once, tailor the preview, and keep each channel’s requirements in view.</p>
        </div>
        <span className="draft-status"><i /> Draft workspace</span>
      </section>

      <div className="campaign-layout">
        <div className="campaign-main">
          <section className="surface-card">
            <div className="section-title-row">
              <div>
                <p className="step-label">01 · SET UP</p>
                <h3>Campaign details</h3>
              </div>
              {draftId && <span className="saved-chip">Editing saved draft</span>}
            </div>
            <div className="form-two-col">
              <label className="form-field">
                <span>Campaign name</span>
                <input value={name} onChange={(event) => setName(event.target.value)} maxLength={160} placeholder="e.g. Vijayawada summer launch" />
              </label>
              <label className="form-field">
                <span>Objective</span>
                <select value={objective} onChange={(event) => setObjective(event.target.value as Objective)}>
                  <option value="leads">Generate leads</option>
                  <option value="traffic">Drive traffic</option>
                  <option value="sales">Drive sales</option>
                  <option value="awareness">Build awareness</option>
                </select>
              </label>
            </div>
            <div className="section-title-row compact-title">
              <div>
                <p className="step-label">02 · DISTRIBUTION</p>
                <h3>Choose channels</h3>
              </div>
              <span className="muted-label">{platforms.length} selected</span>
            </div>
            <div className="channel-grid">
              {CHANNELS.map((channel) => {
                const selected = platforms.includes(channel.id);
                return (
                  <button
                    key={channel.id}
                    type="button"
                    className={`channel-option${selected ? " selected" : ""}`}
                    aria-pressed={selected}
                    onClick={() => togglePlatform(channel.id)}
                  >
                    <span className={`channel-mark ${channel.color}`}>{channel.mark}</span>
                    <span className="channel-info"><strong>{channel.label}</strong><small>{channel.detail}</small></span>
                    <span className="channel-check" aria-hidden="true">{selected ? "✓" : "+"}</span>
                  </button>
                );
              })}
            </div>
            <div className="section-title-row compact-title">
              <div>
                <p className="step-label">02B · AUDIENCE</p>
                <h3>Location and age</h3>
              </div>
              <span className="muted-label">{ageMin}–{ageMax} yrs</span>
            </div>
            <div className="form-two-col targeting-grid">
              <label className="form-field">
                <span>Target location</span>
                <input
                  value={location}
                  onChange={(event) => setLocation(event.target.value)}
                  maxLength={160}
                  placeholder="e.g. Vijayawada, Andhra Pradesh"
                />
              </label>
              <div className="age-range-fields">
                <label className="form-field">
                  <span>Min age</span>
                  <input
                    type="number"
                    min={AGE_MIN_FLOOR}
                    max={AGE_MAX_CEILING}
                    value={ageMin}
                    onChange={(event) => setAgeMin(clampAge(Number(event.target.value), AGE_MIN_FLOOR))}
                  />
                </label>
                <label className="form-field">
                  <span>Max age</span>
                  <input
                    type="number"
                    min={AGE_MIN_FLOOR}
                    max={AGE_MAX_CEILING}
                    value={ageMax}
                    onChange={(event) => setAgeMax(clampAge(Number(event.target.value), AGE_MAX_CEILING))}
                  />
                </label>
              </div>
            </div>
            <p className="targeting-note">Audience targeting is applied when you publish. New campaigns stay paused until you activate them in Ads Manager.</p>
            <div className="form-two-col targeting-grid" style={{ marginTop: 12 }}>
              <label className="form-field">
                <span>Daily budget (₹)</span>
                <input type="number" min={1} step={1} value={dailyBudget} onChange={(event) => setDailyBudget(event.target.value)} />
              </label>
              <label className="form-field">
                <span>Destination URL <small>required for traffic and sales</small></span>
                <input type="url" placeholder="https://" value={destinationUrl} onChange={(event) => setDestinationUrl(event.target.value)} />
              </label>
            </div>
            {metaReady && (
              <div className="form-two-col targeting-grid" style={{ marginTop: 12 }}>
                <label className="form-field">
                  <span>Ad account</span>
                  <select value={selectedAdAccountId} onChange={(event) => setSelectedAdAccountId(event.target.value)}>
                    {(metaConnection?.ad_accounts ?? []).map((account) => (
                      <option key={account.id} value={account.id}>
                        {account.name}{account.currency ? ` · ${account.currency}` : ""}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="form-field">
                  <span>Facebook Page</span>
                  <select value={selectedPageId} onChange={(event) => setSelectedPageId(event.target.value)}>
                    {(metaConnection?.pages ?? []).length === 0 && <option value="">No Pages connected</option>}
                    {(metaConnection?.pages ?? []).map((page) => (
                      <option key={page.id} value={page.id}>{page.name}</option>
                    ))}
                  </select>
                </label>
              </div>
            )}
          </section>

          <section className="surface-card">
            <div className="section-title-row">
              <div>
                <p className="step-label">03 · CREATIVE</p>
                <h3>Build your ad</h3>
              </div>
              <label className="language-switch">
                <span>Copy language</span>
                <select value={language} onChange={(event) => setLanguage(event.target.value as LanguageCode)}>
                  <option value="en">English</option>
                  <option value="te">తెలుగు</option>
                </select>
              </label>
            </div>

            <div className="creative-grid">
              <div className="creative-fields">
                <label className="form-field">
                  <span>Headline <small>{headline.length}/80</small></span>
                  <input value={headline} onChange={(event) => setHeadline(event.target.value)} maxLength={80} placeholder="Lead with one clear benefit" />
                </label>
                <label className="form-field">
                  <span>Primary text <small>{body.length}/1500</small></span>
                  <textarea value={body} onChange={(event) => setBody(event.target.value)} maxLength={1500} rows={7} placeholder="Describe the offer using verified details only." />
                </label>
                <label className="form-field">
                  <span>Call to action</span>
                  <select value={cta} onChange={(event) => setCta(event.target.value)}>
                    <option>Learn more</option>
                    <option>Send message</option>
                    <option>Sign up</option>
                    <option>Contact us</option>
                  </select>
                </label>
              </div>
              <div className="asset-box">
                <div className="asset-heading"><strong>Campaign image</strong><span>Optional · up to 10 MB</span></div>
                <label className={`upload-zone${imageUrl ? " has-image" : ""}`}>
                  {imageUrl ? <img src={imageUrl} alt="Campaign asset preview" /> : <span className="upload-symbol">＋</span>}
                  <span>{image?.name ?? "Choose an image"}</span>
                  <small>JPG, PNG or WebP</small>
                  <input type="file" accept="image/*" onChange={chooseImage} />
                </label>
                {imageError && <p className="inline-error" role="alert">{imageError}</p>}
                {image && <button type="button" className="text-button" onClick={() => setImage(null)}>Remove image</button>}
                {draftId && !image && drafts.find((draft) => draft.id === draftId)?.imageName && (
                  <p className="file-note">Saved draft had “{drafts.find((draft) => draft.id === draftId)?.imageName}”. Choose it again to preview.</p>
                )}
              </div>
            </div>

            <div className="section-title-row compact-title">
              <div><p className="step-label">04 · REVIEW</p><h3>Channel preview</h3></div>
              <div className="preview-tabs" role="tablist" aria-label="Channel preview">
                {CHANNELS.filter((channel) => platforms.includes(channel.id)).map((channel) => (
                  <button key={channel.id} type="button" role="tab" aria-selected={previewPlatform === channel.id}
                    onClick={() => setPreviewPlatform(channel.id)}>{channel.label}</button>
                ))}
              </div>
            </div>
            <div className="ad-preview">
              <div className="preview-account"><span className={`channel-mark ${CHANNELS.find((c) => c.id === previewPlatform)?.color ?? "facebook"}`}>{CHANNELS.find((c) => c.id === previewPlatform)?.mark}</span><span><strong>{client?.name ?? "Your business"}</strong><small>Sponsored · Preview only</small></span><span className="preview-menu">···</span></div>
              {imageUrl ? <img className="preview-image" src={imageUrl} alt="Selected campaign creative" /> : <div className="preview-placeholder"><span>Image preview</span><small>Your selected creative appears here</small></div>}
              <div className="preview-copy">
                <strong>{headline || "Your headline will appear here"}</strong>
                <p>{body || "Your primary ad text will appear here. Keep the offer clear and factual."}</p>
                <button type="button" disabled>{cta}</button>
              </div>
            </div>
            <p className="preview-disclaimer">Preview only. Final layouts, media limits and placement rules vary by channel.</p>
          </section>

          {error && <p className="notice error" role="alert">{error}</p>}
          {notice && <p className="notice success" role="status">{notice}</p>}
          {validationErrors.length > 0 && (
            <ul className="notice error validation-list" role="alert">
              {validationErrors.map((message) => <li key={message}>{message}</li>)}
            </ul>
          )}
          {submittedId && onOpenApprovals && (
            <p className="notice success" role="status">
              <button type="button" className="text-button" onClick={() => onOpenApprovals(submittedId)}>Open in Campaign approvals →</button>
            </p>
          )}
          <div className="campaign-actions">
            <button type="button" className="secondary-button" onClick={clearDraft}>Start fresh</button>
            <button type="button" className="secondary-button" onClick={saveDraft}>Save draft</button>
            <button
              type="button"
              className="primary-button"
              disabled={publishBusy || metaLoading || !canPublish}
              title={canPublish ? "Save in Campex and request approval" : "Connect Meta Business with an ad account, and select only Meta channels"}
              onClick={() => void sendForApproval()}
            >
              {publishBusy ? "Sending…" : "Send for approval"}
              {!publishBusy && <span>→</span>}
            </button>
          </div>
          <div className="publish-note">
            <span className="lock-icon">{metaReady ? "✓" : "◈"}</span>
            {metaLoading ? (
              <p>Checking Meta Business connection…</p>
            ) : metaReady ? (
              <p>
                <strong>Meta Business connected.</strong>
                {" "}
                {unsupportedSelected
                  ? "Deselect Google Ads or WhatsApp Business messaging to enable Publish (Meta channels only)."
                  : "An approver publishes it as a paused campaign. Nothing reaches Meta without a recorded approval."}
              </p>
            ) : (
              <p><strong>Meta Business is not connected.</strong> Connect an ad account under Channels before you publish.</p>
            )}
            <button type="button" className="text-button" onClick={onOpenChannels}>View channel setup</button>
          </div>
        </div>

        <aside className="campaign-aside">
          <section className="assistant-card">
            <div className="assistant-head">
              <span className="assistant-spark">✦</span>
              <div><p className="step-label">AI CAMPAIGN ASSISTANT</p><h3>Copy and performance</h3></div>
              <span className="ai-mode">Secure</span>
            </div>
            <p className="assistant-intro">Improve copy or ask about connected Meta performance. Meta insights are read-only here; campaign changes require a separate confirmation.</p>
            <div className="assistant-messages" aria-live="polite">
              {messages.map((message, index) => (
                <div key={`${message.role}-${index}`} className={`chat-message ${message.role}`}>
                  <span>{message.role === "assistant" ? "AI" : "You"}</span>
                  <p>{message.content}</p>
                  {message.role === "assistant" && message.suggestedCopy && (
                    <button type="button" className="text-button" onClick={() => applySuggestion(message.suggestedCopy ?? "")}>Use as primary text</button>
                  )}
                </div>
              ))}
              {aiBusy && <p className="thinking"><i /> Thinking about your copy…</p>}
            </div>
            <form className="assistant-compose" onSubmit={(event) => void askAssistant(event)}>
              <textarea value={prompt} onChange={(event) => setPrompt(event.target.value)} rows={3} maxLength={2000}
                placeholder="e.g. Summarize this for Telugu-speaking families, or audit my last 14 days of Meta ads" />
              <div className="assistant-compose-foot"><small>{selectedNames.length ? selectedNames.join(" · ") : "Choose channels first"}</small>
                <button type="submit" className="send-button" disabled={aiBusy || !prompt.trim() || platforms.length === 0} aria-label="Send prompt">↑</button>
              </div>
            </form>
            <p className="ai-privacy">Use only verified property facts. Review every suggestion before publishing.</p>
          </section>

          <section className="surface-card drafts-card">
            <div className="section-title-row"><div><p className="step-label">WORKSPACE</p><h3>Recent drafts</h3></div><span className="draft-count">{drafts.length}</span></div>
            {drafts.length === 0 ? <p className="empty-state">Saved drafts in this browser will appear here.</p> : (
              <ul className="draft-list">
                {drafts.slice(0, 5).map((draft) => (
                  <li key={draft.id}>
                    <button type="button" className="draft-open" onClick={() => loadDraft(draft)}>
                      <span className="draft-thumb">{draft.imageName ? "▧" : "✦"}</span>
                      <span><strong>{draft.name}</strong><small>{draft.platforms.length} channels · {draft.location || "No location"} · {draft.ageMin}–{draft.ageMax} · {new Date(draft.savedAt).toLocaleDateString()}</small></span>
                    </button>
                    <button type="button" className="draft-delete" aria-label={`Delete ${draft.name}`} onClick={() => setDrafts((current) => current.filter((item) => item.id !== draft.id))}>×</button>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </aside>
      </div>
    </div>
  );
}
