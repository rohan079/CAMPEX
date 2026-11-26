import { useCallback, useEffect, useMemo, useState } from "react";
import type { ChangeEvent, FormEvent, ReactNode } from "react";
import { ApiError, assistCampaign, getMetaConnection } from "./api";
import type { MetaConnectionStatus } from "./api";
import { getCreative, listCreatives } from "./creativeApi";
import type { CreativeSummary } from "./creativeTypes";
import { AuthImage, AuthVideo, formatDuration } from "./CreativeStudioPage";
import { createExecution, getExecution, listExecutions, submitExecution, updateExecution, validateExecution } from "./executionApi";
import type { CampaignConfig, ExecutionDetail, ExecutionSummary } from "./executionTypes";
import { getBudgetPolicy } from "./intelligenceApi";
import { LocationPicker } from "./LocationPicker";
import { getAdAccounts, setAdAccounts } from "./measurementApi";
import type { BudgetPolicy } from "./intelligenceTypes";
import type { CampaignPlatform, Client, LanguageCode } from "./types";

/**
 * Campaign Studio: a guided builder for Meta campaigns. It saves server-side drafts and sends them for approval;
 * publishing (always PAUSED) happens in Campaign approvals. Everything shown here maps to fields Campex actually
 * sends to Meta; anything not yet supported is labelled as such.
 */

type Objective = "leads" | "traffic" | "sales" | "awareness";
type StepId = "goal" | "audience" | "budget" | "ad" | "review";
type Placement = "facebook" | "instagram" | "whatsapp_ads";
type PreviewKind = "fb_feed" | "ig_feed" | "story";

interface StudioForm {
  name: string;
  objective: Objective;
  adAccountId: string;
  pageId: string;
  location: string;
  locationKey: string | null;
  locationType: "city" | "region" | "country" | null;
  ageMin: number;
  ageMax: number;
  placements: Placement[];
  language: LanguageCode;
  dailyBudget: string;
  headline: string;
  body: string;
  description: string;
  cta: string;
  url: string;
  utm: boolean;
  creativeId: string | null;
  creativeVersion: number | null;
  creativeImageId: string | null;
  creativeVideoId: string | null;
}

type Issue = { step: StepId; level: "error" | "warning"; text: string };
type AssistantMessage = { role: "assistant" | "user"; content: string; suggestedCopy?: string };

const AGE_MIN = 18;
const AGE_MAX = 65;
const LEGACY_KEYS = ["campaign-studio.drafts.v2", "campaign-studio.drafts.v1"];

const OBJECTIVES: Record<Objective, { label: string; icon: string; optimizes: string; bestFor: string; measure: string }> = {
  leads: { label: "Leads", icon: "✉", optimizes: "Lead generation", bestFor: "Site-visit requests, brochure downloads, call-backs", measure: "Cost per qualified lead (CRM-verified)" },
  traffic: { label: "Traffic", icon: "↗", optimizes: "Link clicks", bestFor: "Sending buyers to a project or landing page", measure: "Cost per landing-page visit, then leads" },
  sales: { label: "Sales", icon: "₹", optimizes: "Website conversions (needs the Campex pixel)", bestFor: "Bookings or token payments tracked on your site", measure: "Cost per booking, ROAS" },
  awareness: { label: "Awareness", icon: "◎", optimizes: "Reach", bestFor: "Launch announcements and brand recall", measure: "Reach, frequency, cost per 1,000 reached" },
};

const PLACEMENTS: { id: Placement; label: string; detail: string; mark: string; color: string }[] = [
  { id: "facebook", label: "Facebook", detail: "Feed and Stories", mark: "f", color: "facebook" },
  { id: "instagram", label: "Instagram", detail: "Feed, Stories and Reels", mark: "◎", color: "instagram" },
  { id: "whatsapp_ads", label: "Click-to-WhatsApp", detail: "Runs on Facebook placements", mark: "↗", color: "whatsapp-ads" },
];

const CTAS: { id: string; label: string }[] = [
  { id: "LEARN_MORE", label: "Learn more" }, { id: "BOOK_NOW", label: "Book now" }, { id: "SIGN_UP", label: "Sign up" },
  { id: "CONTACT_US", label: "Contact us" }, { id: "GET_QUOTE", label: "Get quote" }, { id: "MESSAGE_PAGE", label: "Send message" },
  { id: "APPLY_NOW", label: "Apply now" },
];

const AGE_PRESETS: [number, number][] = [[25, 45], [30, 55], [35, 65], [18, 65]];
const BUDGET_PRESETS = [500, 1000, 2000, 5000];

const STEPS: { id: StepId; label: string; hint: string }[] = [
  { id: "goal", label: "Goal", hint: "Objective and account" },
  { id: "audience", label: "Audience", hint: "Who sees it, where" },
  { id: "budget", label: "Budget", hint: "Daily spend and forecast" },
  { id: "ad", label: "Ad", hint: "Creative, copy and link" },
  { id: "review", label: "Review", hint: "Checks and approval" },
];

function blankForm(): StudioForm {
  return {
    name: "", objective: "leads", adAccountId: "", pageId: "", location: "", locationKey: null, locationType: null, ageMin: 25, ageMax: 55,
    placements: ["facebook", "instagram"], language: "en", dailyBudget: "1000", headline: "", body: "", description: "",
    cta: "LEARN_MORE", url: "", utm: true, creativeId: null, creativeVersion: null, creativeImageId: null, creativeVideoId: null,
  };
}

const clampAge = (v: number, fallback: number) => (Number.isFinite(v) ? Math.min(AGE_MAX, Math.max(AGE_MIN, Math.round(v))) : fallback);
const inr = (n: number) => new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 0 }).format(n);
const slug = (v: string) => v.toLowerCase().normalize("NFKD").replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "").slice(0, 60);

/** Adds standard UTM tags so CRM attribution can tell paid social traffic apart. Existing utm_* params win. */
export function withUtm(url: string, campaignName: string): string {
  try {
    const u = new URL(url);
    const set = (k: string, v: string) => { if (!u.searchParams.has(k)) u.searchParams.set(k, v); };
    set("utm_source", "meta");
    set("utm_medium", "paid_social");
    set("utm_campaign", slug(campaignName) || "campaign");
    return u.toString();
  } catch {
    return url;
  }
}

export function studioIssues(f: StudioForm, policy: BudgetPolicy | null, mappedAccount: boolean | null): Issue[] {
  const out: Issue[] = [];
  const e = (step: StepId, text: string) => out.push({ step, level: "error", text });
  const w = (step: StepId, text: string) => out.push({ step, level: "warning", text });
  if (!f.name.trim()) e("goal", "Name the campaign.");
  if (!f.adAccountId) e("goal", "Choose the Meta ad account.");
  if (mappedAccount === false) e("goal", "This ad account isn't linked to the client yet. Link it on the Goal step.");
  if (!f.pageId) w("goal", "No Facebook Page selected; Meta needs one for the ad.");
  if (!f.location.trim()) e("audience", "Add a target location.");
  else if (!f.locationKey) w("audience", "Pick the location from Meta's suggestions, or Meta may reject the publish.");
  if (f.ageMax < f.ageMin) e("audience", "Maximum age is below minimum age.");
  if (f.placements.length === 0) e("audience", "Choose at least one placement.");
  const budget = Number(f.dailyBudget);
  if (!Number.isFinite(budget) || budget < 1) e("budget", "Set a daily budget of at least ₹1.");
  else if (policy?.daily_spend_limit != null && budget > policy.daily_spend_limit) {
    e("budget", `Above the client's daily limit of ${inr(policy.daily_spend_limit)}.`);
  }
  if (!f.headline.trim()) e("ad", "Write a headline.");
  else if (f.headline.trim().length > 40) w("ad", "Headlines over 40 characters get cut off in feeds.");
  if (!f.body.trim()) e("ad", "Write the primary text.");
  else if (f.body.trim().length > 125) w("ad", "Primary text over 125 characters hides behind “See more”.");
  const needsUrl = f.objective === "traffic" || f.objective === "sales";
  if (needsUrl && !f.url.trim()) e("ad", "Traffic and sales campaigns need a destination URL.");
  if (f.url.trim() && !/^https:\/\/[^\s/]+\.[^\s]+/i.test(f.url.trim())) e("ad", "The destination must be a public https:// link.");
  if (!f.creativeId) w("ad", "No approved creative attached: the copy hasn't been through creative review.");
  if (f.objective === "sales") w("goal", "Sales optimizes for website conversions; make sure the Campex pixel is installed.");
  return out;
}

export function CampaignStudio({ client, onOpenChannels, onOpenApprovals }: {
  client?: Client;
  onOpenChannels: () => void;
  onOpenApprovals?: (executionId?: string) => void;
}) {
  const [form, setForm] = useState<StudioForm>(blankForm);
  const [step, setStep] = useState<StepId>("goal");
  const [meta, setMeta] = useState<MetaConnectionStatus | null>(null);
  const [metaLoading, setMetaLoading] = useState(true);
  const [policy, setPolicy] = useState<BudgetPolicy | null>(null);
  const [creatives, setCreatives] = useState<CreativeSummary[]>([]);
  const [drafts, setDrafts] = useState<ExecutionSummary[]>([]);
  const [draftId, setDraftId] = useState<string | null>(null);
  const [draftStatus, setDraftStatus] = useState<string | null>(null);
  const [dirty, setDirty] = useState(false);
  const [saveKey, setSaveKey] = useState(() => crypto.randomUUID());
  const [busy, setBusy] = useState<"save" | "send" | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [mapped, setMapped] = useState<string[] | null>(null);
  const [linking, setLinking] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [serverErrors, setServerErrors] = useState<string[]>([]);
  const [image, setImage] = useState<File | null>(null);
  const [imageUrl, setImageUrl] = useState<string | null>(null);
  const [preview, setPreview] = useState<PreviewKind>("fb_feed");

  const set = useCallback(<K extends keyof StudioForm>(key: K, value: StudioForm[K]) => {
    setForm((f) => ({ ...f, [key]: value }));
    setDirty(true);
    setSaveKey(crypto.randomUUID());
    setNotice(null);
  }, []);

  // Meta connection (ad accounts and Pages)
  useEffect(() => {
    let active = true;
    setMetaLoading(true);
    getMetaConnection()
      .then((status) => {
        if (!active) return;
        setMeta(status);
        setForm((f) => ({ ...f, adAccountId: f.adAccountId || status.ad_accounts[0]?.id || "", pageId: f.pageId || status.pages[0]?.id || "" }));
      })
      .catch(() => active && setMeta(null))
      .finally(() => active && setMetaLoading(false));
    return () => { active = false; };
  }, []);

  // Client-scoped data: budget policy, approved creatives, server drafts
  const loadDrafts = useCallback(async () => {
    if (!client) { setDrafts([]); return; }
    try { setDrafts(await listExecutions(client.id)); } catch { setDrafts([]); }
  }, [client]);
  useEffect(() => {
    setPolicy(null); setCreatives([]); setDraftId(null); setDraftStatus(null); setMapped(null);
    if (!client) return;
    getAdAccounts(client.id).then((r) => setMapped(r.ad_account_ids)).catch(() => setMapped(null));
    getBudgetPolicy(client.id).then(setPolicy).catch(() => setPolicy(null));
    listCreatives(client.id, "APPROVED").then(setCreatives).catch(() => setCreatives([]));
    void loadDrafts();
  }, [client, loadDrafts]);

  useEffect(() => {
    if (!image) { setImageUrl(null); return undefined; }
    const url = URL.createObjectURL(image);
    setImageUrl(url);
    return () => URL.revokeObjectURL(url);
  }, [image]);

  const metaReady = Boolean(meta?.connected && !meta.expired && (meta.ad_accounts?.length ?? 0) > 0);
  const accountKey = form.adAccountId && !form.adAccountId.startsWith("act_") ? `act_${form.adAccountId}` : form.adAccountId;
  const mappedAccount = mapped && accountKey ? mapped.includes(accountKey) : null;
  const issues = useMemo(() => studioIssues(form, policy, mappedAccount), [form, policy, mappedAccount]);
  const errorsFor = (s: StepId) => issues.filter((i) => i.step === s && i.level === "error");
  const stepDone = (s: StepId) => s === "review" ? false : errorsFor(s).length === 0;
  const blocking = issues.filter((i) => i.level === "error");
  const finalUrl = form.url.trim() && form.utm ? withUtm(form.url.trim(), form.name) : form.url.trim();
  const budget = Number(form.dailyBudget) || 0;
  const account = meta?.ad_accounts.find((a) => a.id === form.adAccountId);
  const page = meta?.pages.find((p) => p.id === form.pageId);

  async function linkAccount() {
    if (!client || !mapped || !accountKey) return;
    setLinking(true);
    setError(null);
    try {
      const r = await setAdAccounts(client.id, [...mapped, accountKey]);
      setMapped(r.ad_account_ids);
      setNotice(`${account?.name ?? accountKey} is now linked to ${client.name}.`);
    } catch (err) {
      setError(err instanceof ApiError && err.status === 403
        ? "Only an owner or manager of this client can link ad accounts. Ask one of them, or use Tracking → Ad accounts."
        : err instanceof Error ? err.message : "Could not link the ad account");
    } finally {
      setLinking(false);
    }
  }

  function suggestName() {
    const month = new Intl.DateTimeFormat("en-IN", { month: "short", year: "2-digit" }).format(new Date());
    const parts = [client?.name, OBJECTIVES[form.objective].label, form.location.trim() || undefined, month].filter(Boolean);
    set("name", parts.join(" | ").slice(0, 140));
  }

  async function attachCreative(id: string) {
    if (!id) {
      setForm((f) => ({ ...f, creativeId: null, creativeVersion: null, creativeImageId: null, creativeVideoId: null }));
      setDirty(true);
      return;
    }
    try {
      const d = await getCreative(id);
      const v = d.versions.find((x) => x.version === d.asset.approved_version);
      if (!v) return;
      setForm((f) => ({ ...f, creativeId: id, creativeVersion: v.version, creativeImageId: v.image_id, creativeVideoId: v.video_id,
        headline: v.headline ?? "", body: v.primary_text ?? "", description: v.description ?? "", cta: v.cta ?? f.cta }));
      setDirty(true);
      setSaveKey(crypto.randomUUID());
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not load the creative");
    }
  }

  function toConfig(): CampaignConfig {
    return {
      ad_account_id: form.adAccountId, name: form.name.trim(), objective: form.objective, platforms: form.placements,
      location: form.location.trim(), location_key: form.locationKey, location_type: form.locationKey ? form.locationType : null,
      age_min: form.ageMin, age_max: form.ageMax,
      daily_budget_minor: Math.round(budget * 100), headline: form.headline.trim(), primary_text: form.body.trim(),
      description: form.description.trim() || null, cta: form.cta, destination_url: finalUrl || null, page_id: form.pageId || null,
      creative_asset_id: form.creativeId, creative_version: form.creativeVersion,
    };
  }

  /** Creates or updates the server draft. A changed form gets a new idempotency key, so a double click saves once. */
  async function persist(): Promise<ExecutionDetail | null> {
    if (!client) { setError("Choose a client first."); return null; }
    if (!form.name.trim() || !form.adAccountId) { setError("Name the campaign and choose an ad account before saving."); setStep("goal"); return null; }
    let saved: ExecutionDetail | null = null;
    if (draftId && (draftStatus === null || ["DRAFT", "READY_FOR_REVIEW", "PENDING_APPROVAL", "APPROVED"].includes(draftStatus))) {
      try {
        saved = dirty ? await updateExecution(draftId, toConfig()) : await getExecution(draftId);
      } catch (err) {
        if (!(err instanceof ApiError && (err.status === 409 || err.status === 404))) throw err;
      }
    }
    saved ??= await createExecution(client.id, toConfig(), saveKey);
    setDraftId(saved.execution.id);
    setDraftStatus(saved.execution.status);
    setDirty(false);
    return saved;
  }

  async function saveDraft() {
    setBusy("save"); setError(null); setNotice(null); setServerErrors([]);
    try {
      const saved = await persist();
      if (saved) { setNotice(`Draft saved in Campex (version ${saved.execution.config_version}). Nothing has been published.`); void loadDrafts(); }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not save the draft");
    } finally { setBusy(null); }
  }

  async function sendForApproval() {
    setBusy("send"); setError(null); setNotice(null); setServerErrors([]);
    try {
      const saved = await persist();
      if (!saved) return;
      if (saved.execution.status === "PENDING_APPROVAL") { setNotice("Already waiting for approval."); return; }
      const report = await validateExecution(saved.execution.id);
      if (!report.valid) {
        setServerErrors(report.errors.map((i) => i.message));
        setNotice("Saved as a draft. Fix the issues Campex found, then send again.");
        return;
      }
      const submitted = await submitExecution(saved.execution.id);
      setDraftStatus(submitted.execution.status);
      setNotice("Sent for approval. An approver publishes it as a paused campaign; nothing is live yet.");
      void loadDrafts();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not send for approval");
    } finally { setBusy(null); }
  }

  async function openDraft(id: string) {
    setError(null); setNotice(null); setServerErrors([]);
    try {
      const d = await getExecution(id);
      const c = d.config;
      const url = c.destination_url ?? "";
      setForm({
        name: c.name, objective: c.objective, adAccountId: c.ad_account_id, pageId: c.page_id ?? "", location: c.location,
        locationKey: c.location_key ?? null, locationType: c.location_type ?? null,
        ageMin: c.age_min, ageMax: c.age_max, placements: c.platforms.filter((p): p is Placement => ["facebook", "instagram", "whatsapp_ads"].includes(p)),
        language: form.language, dailyBudget: String(Math.round(c.daily_budget_minor / 100)), headline: c.headline, body: c.primary_text,
        description: c.description ?? "", cta: c.cta, url, utm: false, creativeId: c.creative_asset_id ?? null,
        creativeVersion: c.creative_version ?? null, creativeImageId: null, creativeVideoId: null,
      });
      if (c.creative_asset_id) {
        getCreative(c.creative_asset_id).then((cr) => {
          const v = cr.versions.find((x) => x.version === c.creative_version);
          setForm((f) => ({ ...f, creativeImageId: v?.image_id ?? null, creativeVideoId: v?.video_id ?? null }));
        }).catch(() => undefined);
      }
      setDraftId(id); setDraftStatus(d.execution.status); setDirty(false); setStep("goal");
      setNotice(`Editing “${c.name}” (version ${d.execution.config_version}).`);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not open the draft");
    }
  }

  function startFresh() {
    setForm((f) => ({ ...blankForm(), adAccountId: f.adAccountId, pageId: f.pageId }));
    setDraftId(null); setDraftStatus(null); setDirty(false); setImage(null); setStep("goal");
    setNotice(null); setError(null); setServerErrors([]); setSaveKey(crypto.randomUUID());
  }

  const idx = STEPS.findIndex((s) => s.id === step);
  const go = (delta: number) => setStep(STEPS[Math.min(STEPS.length - 1, Math.max(0, idx + delta))].id);
  const statusChip = draftStatus === "PENDING_APPROVAL" ? "Awaiting approval" : draftStatus === "APPROVED" ? "Approved"
    : draftId ? (dirty ? "Unsaved changes" : "Draft saved") : (dirty ? "Not saved" : "New campaign");

  return (
    <div className="studio">
      <header className="studio-head surface-card">
        <div className="studio-title">
          <p className="eyebrow">Campaign studio · {client?.name ?? "Select a client"}</p>
          <input className="studio-name" aria-label="Campaign name" value={form.name} maxLength={140}
            placeholder="Untitled campaign" onChange={(e) => set("name", e.target.value)} />
          <div className="studio-meta">
            <span className={`pill tone-${draftStatus === "PENDING_APPROVAL" ? "warn" : dirty ? "neutral" : draftId ? "good" : "neutral"}`}>{statusChip}</span>
            <span>{OBJECTIVES[form.objective].label}</span><span>·</span><span>{budget ? `${inr(budget)}/day` : "No budget"}</span>
            {account && <><span>·</span><span>{account.name}</span></>}
          </div>
        </div>
        <div className="studio-head-actions">
          <button type="button" className="text-button" onClick={startFresh}>New</button>
          <button type="button" className="secondary-button" disabled={busy !== null || !client} onClick={() => void saveDraft()}>{busy === "save" ? "Saving…" : "Save draft"}</button>
          <button type="button" className="primary-button" disabled={busy !== null || !client || !metaReady || blocking.length > 0}
            title={blocking.length ? `${blocking.length} item(s) to fix first` : undefined} onClick={() => void sendForApproval()}>
            {busy === "send" ? "Sending…" : "Send for approval"}</button>
        </div>
      </header>

      {!client && <p className="notice warn-notice">Choose a client in the top bar to build a campaign for them.</p>}
      {!metaLoading && !metaReady && (
        <p className="notice warn-notice">Meta Business isn't connected with an ad account. <button type="button" className="text-button" onClick={onOpenChannels}>Connect it under Channels</button></p>
      )}
      {error && <p className="notice error" role="alert">{error}</p>}
      {notice && <p className="notice success-notice" role="status">{notice}
        {draftId && onOpenApprovals && draftStatus && draftStatus !== "DRAFT" && <> <button type="button" className="text-button" onClick={() => onOpenApprovals(draftId)}>Open in Campaign approvals →</button></>}</p>}
      {serverErrors.length > 0 && <ul className="notice error validation-list" role="alert">{serverErrors.map((m) => <li key={m}>{m}</li>)}</ul>}

      <div className="studio-layout">
        <nav className="studio-steps" aria-label="Campaign steps">
          {STEPS.map((s, i) => {
            const done = stepDone(s.id);
            const errs = errorsFor(s.id).length;
            return (
              <button key={s.id} type="button" className={`studio-step${step === s.id ? " active" : ""}${done ? " done" : ""}`}
                aria-current={step === s.id ? "step" : undefined} onClick={() => setStep(s.id)}>
                <span className="studio-step-dot">{done ? "✓" : i + 1}</span>
                <span><strong>{s.label}</strong><small>{s.id === "review" ? (blocking.length ? `${blocking.length} to fix` : "Ready to send") : errs ? `${errs} to fix` : s.hint}</small></span>
              </button>
            );
          })}
          <DraftList drafts={drafts} activeId={draftId} onOpen={(id) => void openDraft(id)} onOpenApprovals={onOpenApprovals} />
        </nav>

        <main className="studio-main surface-card">
          {step === "goal" && (
            <Step title="What should this campaign achieve?" sub="Meta optimizes delivery for the objective you choose.">
              <div className="objective-grid" role="radiogroup" aria-label="Objective">
                {(Object.keys(OBJECTIVES) as Objective[]).map((o) => (
                  <button key={o} type="button" role="radio" aria-checked={form.objective === o}
                    className={`objective-card${form.objective === o ? " selected" : ""}`} onClick={() => set("objective", o)}>
                    <span className="objective-icon">{OBJECTIVES[o].icon}</span><strong>{OBJECTIVES[o].label}</strong>
                    <small>{OBJECTIVES[o].bestFor}</small>
                  </button>))}
              </div>
              <dl className="facts objective-facts">
                <div><dt>Meta optimizes for</dt><dd>{OBJECTIVES[form.objective].optimizes}</dd></div>
                <div><dt>Judge it by</dt><dd>{OBJECTIVES[form.objective].measure}</dd></div>
              </dl>
              <div className="form-grid-2">
                <label className="form-field"><span>Ad account</span>
                  <select value={form.adAccountId} onChange={(e) => set("adAccountId", e.target.value)} disabled={!metaReady}>
                    {!metaReady && <option value="">Connect Meta first</option>}
                    {(meta?.ad_accounts ?? []).map((a) => <option key={a.id} value={a.id}>{a.name}{a.currency ? ` · ${a.currency}` : ""}</option>)}
                  </select>
                  {mappedAccount === false && client && (
                    <span className="field-fix" role="alert">
                      Not linked to {client.name}. Campex only publishes to ad accounts linked to the client.{" "}
                      <button type="button" className="text-button" onClick={() => void linkAccount()} disabled={linking}>
                        {linking ? "Linking…" : `Link to ${client.name}`}
                      </button>
                    </span>
                  )}</label>
                <label className="form-field"><span>Facebook Page <small>the ad runs as this Page</small></span>
                  <select value={form.pageId} onChange={(e) => set("pageId", e.target.value)} disabled={!metaReady}>
                    {(meta?.pages ?? []).length === 0 && <option value="">No Pages connected</option>}
                    {(meta?.pages ?? []).map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
                  </select></label>
              </div>
              <div className="name-row">
                <label className="form-field"><span>Campaign name <small>client | objective | location | month keeps reports tidy</small></span>
                  <input value={form.name} maxLength={140} onChange={(e) => set("name", e.target.value)} placeholder="Green Fields | Leads | Guntur | Nov 26" /></label>
                <button type="button" className="secondary-button" onClick={suggestName}>Suggest name</button>
              </div>
            </Step>
          )}

          {step === "audience" && (
            <Step title="Who should see it?" sub={`${form.location.trim() || "Location not set"} · ages ${form.ageMin}–${form.ageMax} · ${form.placements.length} placement${form.placements.length === 1 ? "" : "s"}`}>
              <LocationPicker clientId={client?.id} value={{ location: form.location, key: form.locationKey, type: form.locationType }}
                onChange={(next) => { set("location", next.location); setForm((f) => ({ ...f, locationKey: next.key, locationType: next.type })); }} />
              <div className="form-field"><span>Age range</span>
                <div className="age-row">
                  <input aria-label="Minimum age" type="number" min={AGE_MIN} max={AGE_MAX} value={form.ageMin} onChange={(e) => set("ageMin", clampAge(Number(e.target.value), AGE_MIN))} />
                  <span>to</span>
                  <input aria-label="Maximum age" type="number" min={AGE_MIN} max={AGE_MAX} value={form.ageMax} onChange={(e) => set("ageMax", clampAge(Number(e.target.value), AGE_MAX))} />
                  <div className="preset-chips">{AGE_PRESETS.map(([a, b]) => (
                    <button key={`${a}-${b}`} type="button" className={`chip${form.ageMin === a && form.ageMax === b ? " active" : ""}`}
                      onClick={() => { set("ageMin", a); set("ageMax", b); }}>{a}–{b}{b === 65 ? "+" : ""}</button>))}</div>
                </div></div>
              <fieldset className="placement-grid"><legend>Placements</legend>
                {PLACEMENTS.map((p) => {
                  const on = form.placements.includes(p.id);
                  return <button key={p.id} type="button" aria-pressed={on} className={`channel-option${on ? " selected" : ""}`}
                    onClick={() => set("placements", on ? form.placements.filter((x) => x !== p.id) : [...form.placements, p.id])}>
                    <span className={`channel-mark ${p.color}`}>{p.mark}</span>
                    <span className="channel-info"><strong>{p.label}</strong><small>{p.detail}</small></span>
                    <span className="channel-check" aria-hidden="true">{on ? "✓" : "+"}</span></button>;
                })}
              </fieldset>
              <label className="form-field narrow"><span>Copy language</span>
                <select value={form.language} onChange={(e) => set("language", e.target.value as LanguageCode)}><option value="en">English</option><option value="te">తెలుగు</option></select></label>
              <p className="targeting-note">Detailed interests, custom audiences and the housing special ad category are set in Ads Manager for now. Review them there before activating.</p>
            </Step>
          )}

          {step === "budget" && (
            <Step title="How much should it spend?" sub="Daily budget on the ad set. Campaigns stay paused until someone activates them.">
              <div className="budget-row">
                <label className="form-field"><span>Daily budget (₹)</span>
                  <input className="budget-input" type="number" min={1} step={50} value={form.dailyBudget} onChange={(e) => set("dailyBudget", e.target.value)} /></label>
                <div className="preset-chips">{BUDGET_PRESETS.map((b) => (
                  <button key={b} type="button" className={`chip${budget === b ? " active" : ""}`} onClick={() => set("dailyBudget", String(b))}>{inr(b)}</button>))}</div>
              </div>
              <div className="forecast">
                <div><span>Per week</span><strong>{inr(budget * 7)}</strong></div>
                <div><span>Per 30 days</span><strong>{inr(budget * 30)}</strong></div>
                <div><span>Client daily limit</span><strong>{policy?.daily_spend_limit != null ? inr(policy.daily_spend_limit) : "Not set"}</strong></div>
                {policy?.target_cost_per_qualified_lead ? (
                  <div><span>At target cost per qualified lead</span>
                    <strong>≈ {Math.floor((budget * 30) / policy.target_cost_per_qualified_lead)} / month</strong>
                    <small>Estimate from the client's {inr(policy.target_cost_per_qualified_lead)} target, not a forecast from Meta.</small></div>
                ) : <div><span>Qualified-lead estimate</span><strong>—</strong><small>Set a target cost per qualified lead in Performance AI → Budget.</small></div>}
              </div>
              {policy?.daily_spend_limit != null && budget > policy.daily_spend_limit && <p className="notice error">Above the client's daily spend limit; approval will be refused.</p>}
              <p className="targeting-note">Schedule: runs continuously from activation. Start and end dates aren't sent to Meta yet; pause it from Optimization actions when it should stop.</p>
            </Step>
          )}

          {step === "ad" && (
            <Step title="Build the ad" sub="Use an approved creative so the copy has been reviewed, or write it here.">
              <label className="form-field"><span>Approved creative <small>from Creative Studio · locks the copy to the approved version</small></span>
                <select value={form.creativeId ?? ""} onChange={(e) => void attachCreative(e.target.value)}>
                  <option value="">None, write copy here</option>
                  {creatives.map((c) => <option key={c.id} value={c.id}>{c.name} · v{c.approved_version}{c.media_type === "video" ? ` · video ${formatDuration(c.video_duration_ms)}` : ""}</option>)}
                </select></label>
              {!form.creativeId && (
                <label className="form-field"><span>Image for preview <small>preview only; save artwork in Creative Studio to use it in a campaign</small></span>
                  <input type="file" accept="image/png,image/jpeg,image/webp" onChange={(e: ChangeEvent<HTMLInputElement>) => {
                    const file = e.target.files?.[0] ?? null;
                    setImage(file && file.type.startsWith("image/") && file.size <= 10 * 1024 * 1024 ? file : null);
                  }} /></label>
              )}
              <label className="form-field"><span>Headline <small className={form.headline.length > 40 ? "warn-text" : undefined}>{form.headline.length}/40 recommended</small></span>
                <input value={form.headline} maxLength={80} readOnly={Boolean(form.creativeId)} onChange={(e) => set("headline", e.target.value)} placeholder="One clear benefit" /></label>
              <label className="form-field"><span>Primary text <small className={form.body.length > 125 ? "warn-text" : undefined}>{form.body.length}/125 before “See more”</small></span>
                <textarea rows={5} maxLength={1500} readOnly={Boolean(form.creativeId)} value={form.body} onChange={(e) => set("body", e.target.value)} placeholder="Verified details only: no invented prices, approvals or returns." /></label>
              <div className="form-grid-2">
                <label className="form-field"><span>Description <small>optional</small></span>
                  <input value={form.description} maxLength={255} readOnly={Boolean(form.creativeId)} onChange={(e) => set("description", e.target.value)} /></label>
                <label className="form-field"><span>Call to action</span>
                  <select value={form.cta} onChange={(e) => set("cta", e.target.value)}>{CTAS.map((c) => <option key={c.id} value={c.id}>{c.label}</option>)}</select></label>
              </div>
              <label className="form-field"><span>Destination URL <small>{form.objective === "traffic" || form.objective === "sales" ? "required" : "optional for lead and awareness campaigns"}</small></span>
                <input type="url" value={form.url} placeholder="https://greenfields.example/villas" onChange={(e) => set("url", e.target.value)} /></label>
              <label className="check-line"><input type="checkbox" checked={form.utm} onChange={(e) => set("utm", e.target.checked)} />Add UTM tags so CRM attribution recognises this campaign</label>
              {finalUrl && form.utm && <p className="utm-preview"><span>Final URL</span><code>{finalUrl}</code></p>}
              {form.creativeId && <p className="targeting-note">Copy is locked to the approved creative. Change it in Creative Studio and approve a new version.</p>}
              {form.creativeId && (form.url.trim() && form.pageId
                ? <p className="notice note-info">After an approver publishes the campaign, Campex uploads the {form.creativeVideoId ? "video and its thumbnail" : "image"} to Meta
                    {form.creativeVideoId ? ", waits until Meta has processed the video," : ""} and creates the ad, paused.</p>
                : <p className="notice warn-notice">Add a destination URL and a Facebook Page so Campex can create the ad itself; otherwise add it in Ads Manager.</p>)}
            </Step>
          )}

          {step === "review" && (
            <Step title="Review before approval" sub="Approvers see exactly this configuration; any later edit cancels their approval.">
              <ul className="checklist">
                {STEPS.filter((s) => s.id !== "review").map((s) => {
                  const list = issues.filter((i) => i.step === s.id);
                  return <li key={s.id}><button type="button" className="text-button" onClick={() => setStep(s.id)}>{s.label}</button>
                    {list.length === 0 ? <span className="check-ok">All set</span> : <ul>{list.map((i) => <li key={i.text} className={`issue-${i.level}`}>{i.text}</li>)}</ul>}</li>;
                })}
              </ul>
              <dl className="facts">
                <div><dt>Objective</dt><dd>{OBJECTIVES[form.objective].label}</dd></div>
                <div><dt>Account · Page</dt><dd>{account?.name ?? "—"} · {page?.name ?? "—"}</dd></div>
                <div><dt>Audience</dt><dd>{form.location || "—"}, {form.ageMin}–{form.ageMax}</dd></div>
                <div><dt>Placements</dt><dd>{form.placements.map((p) => PLACEMENTS.find((x) => x.id === p)?.label).join(", ") || "—"}</dd></div>
                <div><dt>Budget</dt><dd>{inr(budget)}/day · {inr(budget * 30)}/30 days</dd></div>
                <div><dt>Creative</dt><dd>{form.creativeId ? `Approved v${form.creativeVersion}` : "Not reviewed"}</dd></div>
                <div className="span-2"><dt>Destination</dt><dd className="break">{finalUrl || "—"}</dd></div>
              </dl>
              <p className="notice note-info">What happens next: an approver checks it in Campaign approvals and publishes it. Meta receives a <strong>paused</strong> campaign and ad set; the ad is added in Ads Manager, and nothing spends until someone activates it.</p>
            </Step>
          )}

          <div className="studio-nav">
            <button type="button" className="secondary-button" disabled={idx === 0} onClick={() => go(-1)}>Back</button>
            {step !== "review"
              ? <button type="button" className="primary-button" onClick={() => go(1)}>Continue</button>
              : <button type="button" className="primary-button" disabled={busy !== null || !client || !metaReady || blocking.length > 0} onClick={() => void sendForApproval()}>
                  {busy === "send" ? "Sending…" : "Send for approval"}</button>}
          </div>
        </main>

        <aside className="studio-side">
          <section className="surface-card preview-card">
            <div className="section-title-row"><h3>Preview</h3>
              <div className="segmented-tabs" role="tablist" aria-label="Preview placement">
                {([["fb_feed", "Facebook"], ["ig_feed", "Instagram"], ["story", "Story"]] as [PreviewKind, string][]).map(([k, label]) => (
                  <button key={k} type="button" role="tab" aria-selected={preview === k} onClick={() => setPreview(k)}>{label}</button>))}
              </div></div>
            <AdMock kind={preview} pageName={page?.name ?? client?.name ?? "Your Page"} headline={form.headline} body={form.body}
              description={form.description} cta={CTAS.find((c) => c.id === form.cta)?.label ?? "Learn more"}
              domain={(() => { try { return finalUrl ? new URL(finalUrl).hostname : ""; } catch { return ""; } })()}
              image={form.creativeVideoId ? <AuthVideo videoId={form.creativeVideoId} posterId={form.creativeImageId} className="mock-image" label="Approved video" />
                : form.creativeImageId ? <AuthImage imageId={form.creativeImageId} alt="Approved creative" className="mock-image" />
                : imageUrl ? <img className="mock-image" src={imageUrl} alt="Preview" /> : <div className="mock-image mock-empty">Image</div>} />
            <p className="preview-disclaimer">Approximate layout. Meta crops and truncates per placement.</p>
          </section>
          <section className="surface-card readiness-card" aria-label="Launch readiness">
            <h3>Launch readiness</h3>
            <ul>{STEPS.filter((s) => s.id !== "review").map((s) => (
              <li key={s.id} className={stepDone(s.id) ? "ok" : "todo"}><span>{stepDone(s.id) ? "✓" : "•"}</span>{s.label}
                {!stepDone(s.id) && <small>{errorsFor(s.id)[0]?.text}</small>}</li>))}</ul>
            {issues.filter((i) => i.level === "warning").length > 0 && <p className="targeting-note">{issues.filter((i) => i.level === "warning").length} suggestion(s) on the Review step.</p>}
          </section>
          <AssistantCard client={client} form={form} onUseCopy={(copy) => { if (!form.creativeId) set("body", copy); }} locked={Boolean(form.creativeId)} />
        </aside>
      </div>
    </div>
  );
}

function Step({ title, sub, children }: { title: string; sub: string; children: ReactNode }) {
  return <section className="studio-step-body"><div><h2>{title}</h2><p className="muted">{sub}</p></div>{children}</section>;
}

function AdMock({ kind, pageName, headline, body, description, cta, domain, image }: {
  kind: PreviewKind; pageName: string; headline: string; body: string; description: string; cta: string; domain: string; image: ReactNode;
}) {
  if (kind === "story") {
    return (
      <div className="mock story">
        {image}
        <div className="story-top"><span className="mock-avatar" /><strong>{pageName}</strong><small>Sponsored</small></div>
        <div className="story-bottom"><p>{body || "Primary text"}</p><span className="story-cta">⌃ {cta}</span></div>
      </div>
    );
  }
  const ig = kind === "ig_feed";
  return (
    <div className={`mock feed${ig ? " ig" : ""}`}>
      <div className="mock-head"><span className="mock-avatar" /><span><strong>{ig ? pageName.toLowerCase().replace(/\s+/g, "") : pageName}</strong><small>Sponsored</small></span></div>
      {!ig && <p className="mock-text">{body || "Primary text appears here."}</p>}
      {image}
      <div className="mock-foot">
        {ig ? <span className="mock-cta-bar">{cta} ›</span> : <>
          <span className="mock-link">{domain && <small>{domain.toUpperCase()}</small>}<strong>{headline || "Headline"}</strong>{description && <small>{description}</small>}</span>
          <span className="mock-cta">{cta}</span></>}
      </div>
      {ig && <p className="mock-text"><strong>{pageName.toLowerCase().replace(/\s+/g, "")}</strong> {body || "Primary text appears here."}</p>}
    </div>
  );
}

function DraftList({ drafts, activeId, onOpen, onOpenApprovals }: {
  drafts: ExecutionSummary[]; activeId: string | null; onOpen: (id: string) => void; onOpenApprovals?: (id?: string) => void;
}) {
  const editable = drafts.filter((d) => d.status === "DRAFT" || d.status === "READY_FOR_REVIEW");
  const waiting = drafts.filter((d) => d.status === "PENDING_APPROVAL");
  const legacy = (() => {
    try {
      for (const key of LEGACY_KEYS) {
        const v: unknown = JSON.parse(localStorage.getItem(key) ?? "null");
        if (Array.isArray(v) && v.length) return v.length;
      }
    } catch { /* storage unavailable */ }
    return 0;
  })();
  return (
    <div className="studio-drafts">
      <p className="step-label">DRAFTS</p>
      {editable.length === 0 && waiting.length === 0 && <p className="empty-state">Saved drafts appear here.</p>}
      {editable.slice(0, 6).map((d) => (
        <button key={d.id} type="button" className={`draft-link${activeId === d.id ? " active" : ""}`} onClick={() => onOpen(d.id)}>
          <strong>{d.name}</strong><small>v{d.config_version} · {new Date(d.updated_at).toLocaleDateString("en-IN")}</small></button>))}
      {waiting.length > 0 && <>
        <p className="step-label">AWAITING APPROVAL</p>
        {waiting.slice(0, 4).map((d) => (
          <button key={d.id} type="button" className="draft-link" onClick={() => onOpenApprovals?.(d.id)}>
            <strong>{d.name}</strong><small>Open in approvals →</small></button>))}
      </>}
      {legacy > 0 && <p className="targeting-note">{legacy} older draft(s) are saved only in this browser and aren't shown here.</p>}
    </div>
  );
}

function AssistantCard({ client, form, onUseCopy, locked }: {
  client?: Client; form: StudioForm; onUseCopy: (copy: string) => void; locked: boolean;
}) {
  const [open, setOpen] = useState(false);
  const [prompt, setPrompt] = useState("");
  const [busy, setBusy] = useState(false);
  const [messages, setMessages] = useState<AssistantMessage[]>([]);
  async function ask(e: FormEvent) {
    e.preventDefault();
    const q = prompt.trim();
    if (!q || busy) return;
    setMessages((m) => [...m, { role: "user", content: q }]);
    setPrompt(""); setBusy(true);
    try {
      const r = await assistCampaign({
        campaign_name: form.name.trim() || "Untitled campaign", objective: form.objective,
        platforms: form.placements as CampaignPlatform[], language: form.language, prompt: q,
        content: [form.headline.trim(), form.body.trim()].filter(Boolean).join("\n\n") || "No draft copy supplied.",
        ad_account_id: form.adAccountId || undefined, client_id: client?.id, client_name: client?.name,
      });
      setMessages((m) => [...m, { role: "assistant", content: r.reply, suggestedCopy: r.suggested_copy ?? undefined }]);
    } catch (err) {
      setMessages((m) => [...m, { role: "assistant", content: `I couldn't complete that: ${err instanceof Error ? err.message : "assistant unavailable"}` }]);
    } finally { setBusy(false); }
  }
  return (
    <section className="surface-card assistant-mini">
      <button type="button" className="assistant-toggle" aria-expanded={open} onClick={() => setOpen((v) => !v)}>
        <span className="assistant-spark">✦</span><span><strong>AI assistant</strong><small>Improve copy or ask about Meta results (read-only)</small></span><span aria-hidden="true">{open ? "−" : "+"}</span></button>
      {open && <>
        <div className="assistant-messages" aria-live="polite">
          {messages.length === 0 && <p className="muted">Try: “Shorten this for Instagram” or “Which of my campaigns had the lowest cost per lead last month?”</p>}
          {messages.map((m, i) => (
            <div key={i} className={`chat-message ${m.role}`}><span>{m.role === "assistant" ? "AI" : "You"}</span><p>{m.content}</p>
              {m.suggestedCopy && !locked && <button type="button" className="text-button" onClick={() => onUseCopy(m.suggestedCopy ?? "")}>Use as primary text</button>}</div>))}
          {busy && <p className="thinking"><i /> Thinking…</p>}
        </div>
        <form className="assistant-compose" onSubmit={(e) => void ask(e)}>
          <textarea rows={2} maxLength={2000} value={prompt} onChange={(e) => setPrompt(e.target.value)} placeholder="Ask the assistant" />
          <div className="assistant-compose-foot"><small>Verify every fact before using a suggestion.</small>
            <button type="submit" className="send-button" disabled={busy || !prompt.trim()} aria-label="Send prompt">↑</button></div>
        </form>
      </>}
    </section>
  );
}
