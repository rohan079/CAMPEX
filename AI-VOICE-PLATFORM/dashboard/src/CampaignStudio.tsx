import { useEffect, useMemo, useState } from "react";
import type { ChangeEvent, FormEvent } from "react";
import { assistCampaign } from "./api";
import type { CampaignPlatform, Client, LanguageCode } from "./types";

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
type AssistantMessage = { role: "assistant" | "user"; content: string };

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

function suggestedCopy(reply: string): string {
  const match = /suggested copy:\s*([\s\S]*)/i.exec(reply);
  return (match?.[1] ?? reply).trim();
}

export function CampaignStudio({
  client,
  onOpenChannels,
}: {
  client?: Client;
  onOpenChannels: () => void;
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
  const [suggestion, setSuggestion] = useState<string | null>(null);
  const [aiBusy, setAiBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(drafts));
  }, [drafts]);

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
    if (!question || !body.trim() || platforms.length === 0 || aiBusy) return;
    setMessages((current) => [...current, { role: "user", content: question }]);
    setPrompt("");
    setAiBusy(true);
    setSuggestion(null);
    try {
      const result = await assistCampaign({
        campaign_name: name.trim() || "Untitled campaign",
        objective,
        platforms,
        language,
        prompt: question,
        content: [headline.trim(), body.trim()].filter(Boolean).join("\n\n"),
      });
      setMessages((current) => [...current, { role: "assistant", content: result.reply }]);
      setSuggestion(result.reply);
    } catch (err) {
      const message = err instanceof Error ? err.message : "The AI assistant is unavailable.";
      setMessages((current) => [...current, { role: "assistant", content: `I couldn’t complete that request: ${message}` }]);
    } finally {
      setAiBusy(false);
    }
  }

  function applySuggestion() {
    if (!suggestion) return;
    setBody(suggestedCopy(suggestion));
    setNotice("AI suggestion copied into the ad copy. Review all factual claims before publishing.");
  }

  function clearDraft() {
    setDraftId(null);
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
            <p className="targeting-note">Audience targeting is stored with the draft for Meta/Instagram campaigns. Live delivery still requires a connected ad account.</p>
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
          <div className="campaign-actions">
            <button type="button" className="secondary-button" onClick={clearDraft}>Start fresh</button>
            <button type="button" className="secondary-button" onClick={saveDraft}>Save draft</button>
            <button type="button" className="primary-button" disabled title="Connect publishing accounts to enable this action">
              Publish to {platforms.length || "selected"} channels <span>↗</span>
            </button>
          </div>
          <div className="publish-note">
            <span className="lock-icon">◈</span>
            <p><strong>Publishing is not connected yet.</strong> Drafts are saved in this browser. Connect platform accounts before a real publish action is enabled.</p>
            <button type="button" className="text-button" onClick={onOpenChannels}>View channel setup</button>
          </div>
        </div>

        <aside className="campaign-aside">
          <section className="assistant-card">
            <div className="assistant-head">
              <span className="assistant-spark">✦</span>
              <div><p className="step-label">AI COPY ASSISTANT</p><h3>Make the message clearer</h3></div>
              <span className="ai-mode">Server-side</span>
            </div>
            <p className="assistant-intro">Summarize the offer or adapt copy for your selected channels. Review every factual claim before using it.</p>
            <div className="assistant-messages" aria-live="polite">
              {messages.map((message, index) => (
                <div key={`${message.role}-${index}`} className={`chat-message ${message.role}`}>
                  <span>{message.role === "assistant" ? "AI" : "You"}</span>
                  <p>{message.content}</p>
                  {message.role === "assistant" && message.content === suggestion && (
                    <button type="button" className="text-button" onClick={applySuggestion}>Use as primary text</button>
                  )}
                </div>
              ))}
              {aiBusy && <p className="thinking"><i /> Thinking about your copy…</p>}
            </div>
            <form className="assistant-compose" onSubmit={(event) => void askAssistant(event)}>
              <textarea value={prompt} onChange={(event) => setPrompt(event.target.value)} rows={3} maxLength={2000}
                placeholder="e.g. Summarize this in two short sentences for Telugu-speaking families" />
              <div className="assistant-compose-foot"><small>{selectedNames.length ? selectedNames.join(" · ") : "Choose channels first"}</small>
                <button type="submit" className="send-button" disabled={aiBusy || !prompt.trim() || !body.trim() || platforms.length === 0} aria-label="Send prompt">↑</button>
              </div>
            </form>
            <p className="ai-privacy">Your AI key stays on the server. Use only verified property facts.</p>
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
