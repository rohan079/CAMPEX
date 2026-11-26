import { useCallback, useEffect, useMemo, useState } from "react";
import {
  approveCreative, archiveCreative, creativeImage, creativeVideo, editCreative, fileToBase64, generateArtwork, generateCopy, getCreative,
  getMediaLimits, listCreatives, reformatCreative, rejectCreative, submitCreative, uploadArtwork, uploadVideoCreative,
} from "./creativeApi";
import { AD_ASPECTS } from "./creativeTypes";
import type { AdAspect } from "./creativeTypes";
import {
  AD_TYPES, type ClaimCheck, type CopySuggestions, type CreativeAction, type CreativeDetail, type CreativeStatus,
  type CreativeSummary,
} from "./creativeTypes";
import { formatWhen } from "./format";
import { ClientRequired, ErrorBanner, errorText } from "./measurementUi";
import { actorName } from "./privacy";

/** Phase 3 Creative Studio: AI copy, artwork, versions and creative review. Nothing here publishes to Meta. */

const STATUS_LABEL: Record<CreativeStatus, string> = {
  GENERATED: "Generated", EDITING: "Editing", REVIEW_PENDING: "In review", APPROVED: "Approved", REJECTED: "Changes requested",
  ARCHIVED: "Archived",
};
const STATUS_TONE: Record<CreativeStatus, string> = {
  GENERATED: "live", EDITING: "neutral", REVIEW_PENDING: "warn", APPROVED: "good", REJECTED: "bad", ARCHIVED: "neutral",
};
const CTA_OPTIONS = ["LEARN_MORE", "SIGN_UP", "CONTACT_US", "MESSAGE_PAGE", "BOOK_NOW", "GET_QUOTE", "APPLY_NOW"];
const MAX_UPLOAD = 8 * 1024 * 1024;
const ctaLabel = (cta: string | null | undefined) => (cta ? cta.replaceAll("_", " ").toLowerCase() : "learn more");
const lines = (text: string) => text.split("\n").map((l) => l.trim()).filter(Boolean);

export function CreativeStatusPill({ status }: { status: CreativeStatus }) {
  return <span className={`pill tone-${STATUS_TONE[status]}`}>{STATUS_LABEL[status]}</span>;
}

/** Stored images need the Firebase token, so they are fetched as blobs rather than linked directly. */
export function AuthImage({ imageId, alt, className }: { imageId: string | null; alt: string; className?: string }) {
  const [url, setUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    if (!imageId) return undefined;
    let objectUrl: string | null = null;
    let active = true;
    creativeImage(imageId)
      .then((blob) => { if (active) { objectUrl = URL.createObjectURL(blob); setUrl(objectUrl); } })
      .catch(() => active && setFailed(true));
    return () => { active = false; if (objectUrl) URL.revokeObjectURL(objectUrl); };
  }, [imageId]);
  if (!imageId || failed) return <div className={`img-placeholder ${className ?? ""}`}>{failed ? "Image unavailable" : "No artwork"}</div>;
  if (!url) return <div className={`img-placeholder loading ${className ?? ""}`} aria-label="Loading image" />;
  return <img className={className} src={url} alt={alt} />;
}

export function formatDuration(ms: number | null | undefined): string {
  if (!ms) return "";
  const total = Math.round(ms / 1000);
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, "0")}`;
}

/** Plays a stored video. It is fetched only when someone presses play (videos can be large). */
export function AuthVideo({ videoId, posterId, className, label }: { videoId: string; posterId: string | null; className?: string; label: string }) {
  const [url, setUrl] = useState<string | null>(null);
  const [state, setState] = useState<"idle" | "loading" | "error">("idle");
  useEffect(() => () => { if (url) URL.revokeObjectURL(url); }, [url]);
  if (url) return <video className={className} src={url} controls autoPlay playsInline aria-label={label} />;
  return (
    <div className={`video-poster ${className ?? ""}`}>
      <AuthImage imageId={posterId} alt="" className="video-poster-image" />
      <button type="button" className="play-button" aria-label={`Play ${label}`} disabled={state === "loading"}
        onClick={() => {
          setState("loading");
          creativeVideo(videoId).then((blob) => setUrl(URL.createObjectURL(blob))).catch(() => setState("error"));
        }}>{state === "loading" ? "…" : "▶"}</button>
      {state === "error" && <span className="video-error">Video unavailable</span>}
    </div>
  );
}

/** Reads duration and captures a thumbnail frame in the browser (Meta needs a thumbnail for video ads). */
export function inspectVideo(file: File): Promise<{ durationMs: number; thumbnail: Blob }> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const video = document.createElement("video");
    video.preload = "metadata";
    video.muted = true;
    video.playsInline = true;
    const done = (fn: () => void) => { fn(); URL.revokeObjectURL(url); };
    video.onerror = () => done(() => reject(new Error("This video can't be read by the browser. Export it as H.264 MP4.")));
    video.onloadedmetadata = () => { video.currentTime = Math.min(1, (video.duration || 1) / 2); };
    video.onseeked = () => {
      const scale = Math.min(1, 1080 / Math.max(video.videoWidth, 1));
      const canvas = document.createElement("canvas");
      canvas.width = Math.max(1, Math.round(video.videoWidth * scale));
      canvas.height = Math.max(1, Math.round(video.videoHeight * scale));
      canvas.getContext("2d")?.drawImage(video, 0, 0, canvas.width, canvas.height);
      canvas.toBlob((blob) => done(() => blob ? resolve({ durationMs: Math.round(video.duration * 1000), thumbnail: blob })
        : reject(new Error("Could not capture a thumbnail from the video."))), "image/png");
    };
    video.src = url;
  });
}

export function ClaimCheckNotice({ check }: { check: ClaimCheck | null }) {
  if (!check) return null;
  if (!check.flagged_phrases.length && !check.unsupported_numbers.length) {
    return <p className="notice success-notice">Claim check passed: every figure comes from the verified facts.</p>;
  }
  return (
    <div className="notice warn-notice claim-check" role="status">
      {check.flagged_phrases.length > 0 && <p><strong>Not allowed in property ads:</strong> {check.flagged_phrases.map((f) => `“${f.text}” (${f.reason.toLowerCase()})`).join(", ")}</p>}
      {check.unsupported_numbers.length > 0 && <p><strong>Not in your verified facts:</strong> {check.unsupported_numbers.join(", ")}. Remove them or add the verified fact.</p>}
    </div>
  );
}

type Tab = "all" | "review" | "approved";

export function CreativeStudioPage({ clientId }: { clientId?: string }) {
  const [items, setItems] = useState<CreativeSummary[] | null>(null);
  const [tab, setTab] = useState<Tab>("all");
  const [openId, setOpenId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [mode, setMode] = useState<"copy" | "artwork">("copy");
  const [facts, setFacts] = useState("");
  const [adType, setAdType] = useState("property_launch");
  const [chosen, setChosen] = useState<{ headline?: string; primary_text?: string; description?: string; cta?: string }>({});
  const [artPrompt, setArtPrompt] = useState("");

  const load = useCallback(async () => {
    if (!clientId) return;
    try { setItems(await listCreatives(clientId)); } catch (err) { setError(errorText(err, "Could not load creatives")); }
  }, [clientId]);
  useEffect(() => { setItems(null); void load(); }, [load]);

  const visible = useMemo(() => (items ?? []).filter((i) => tab === "all" || (tab === "review" ? i.status === "REVIEW_PENDING" : i.status === "APPROVED")), [items, tab]);

  return (
    <ClientRequired clientId={clientId}>
      <div className="page-stack">
        <section className="page-intro">
          <div>
            <p className="eyebrow">AI Creative Studio</p>
            <h2>Artwork and copy, reviewed before they run.</h2>
            <p>AI drafts from the facts you provide. Every figure is checked against them, and creatives need approval before a campaign can use them.</p>
          </div>
        </section>
        <ErrorBanner error={error} onDismiss={() => setError(null)} />

        <section className="surface-card creative-create">
          <div className="segmented-tabs" role="tablist" aria-label="Create">
            <button type="button" role="tab" aria-selected={mode === "copy"} onClick={() => setMode("copy")}>Write copy</button>
            <button type="button" role="tab" aria-selected={mode === "artwork"} onClick={() => setMode("artwork")}>Artwork</button>
          </div>
          <div className="form-grid-2">
            <label className="form-field"><span>Ad type</span>
              <select value={adType} onChange={(e) => setAdType(e.target.value)}>{AD_TYPES.map((t) => <option key={t.id} value={t.id}>{t.label}</option>)}</select></label>
            <label className="form-field facts-field"><span>Verified facts <small>one per line · only what you can prove</small></span>
              <textarea rows={4} value={facts} onChange={(e) => setFacts(e.target.value)}
                placeholder={"3 BHK villas from ₹85 lakh\nRERA registered: P02400005678\nPossession by December 2027"} /></label>
          </div>
          {mode === "copy"
            ? <CopyWriter clientId={clientId!} adType={adType} facts={lines(facts)} chosen={chosen} onChoose={setChosen}
                onUseConcept={(visual) => { setArtPrompt(visual); setMode("artwork"); }} onError={setError} />
            : <ArtworkMaker clientId={clientId!} adType={adType} facts={lines(facts)} prompt={artPrompt} onPrompt={setArtPrompt}
                chosen={chosen} onError={setError} onCreated={(id) => { void load(); setOpenId(id); }} />}
        </section>

        <section className="section-title-row"><div><p className="step-label">ASSET LIBRARY</p><h3>Creatives</h3></div>
          <div className="segmented-tabs" role="tablist" aria-label="Library filter">
            {(["all", "review", "approved"] as Tab[]).map((t) => (
              <button key={t} type="button" role="tab" aria-selected={tab === t} onClick={() => setTab(t)}>{t === "all" ? "All" : t === "review" ? "In review" : "Approved"}</button>
            ))}
          </div>
        </section>
        {items === null ? <div className="loading-panel">Loading creatives…</div> : visible.length === 0 ? (
          <div className="empty-state large"><span>✦</span><strong>No creatives here yet</strong><p>Generate artwork or upload your own to start the library.</p></div>
        ) : (
          <div className="creative-library">
            {visible.map((item) => (
              <button key={item.id} type="button" className="creative-tile" onClick={() => setOpenId(item.id)} aria-label={`Open ${item.name}`}>
                <span className="thumb-wrap"><AuthImage imageId={item.image_id} alt="" className="creative-thumb" />
                  {item.media_type === "video" && <span className="video-badge">▶ {formatDuration(item.video_duration_ms)}</span>}</span>
                <span className="creative-tile-body"><strong>{item.name}</strong>
                  <span className="creative-tile-meta"><CreativeStatusPill status={item.status} /><small>v{item.current_version}{item.approved_version ? ` · approved v${item.approved_version}` : ""}</small></span>
                  {item.headline && <small className="clamp">{item.headline}</small>}</span>
              </button>
            ))}
          </div>
        )}
      </div>
      {openId && <CreativeDrawer id={openId} chosen={chosen} onClose={() => setOpenId(null)} onChanged={() => void load()} />}
    </ClientRequired>
  );
}

function CopyWriter({ clientId, adType, facts, chosen, onChoose, onUseConcept, onError }: {
  clientId: string; adType: string; facts: string[]; chosen: Record<string, string | undefined>;
  onChoose: (c: { headline?: string; primary_text?: string; description?: string; cta?: string }) => void;
  onUseConcept: (visual: string) => void; onError: (e: string | null) => void;
}) {
  const [product, setProduct] = useState("");
  const [audience, setAudience] = useState("");
  const [location, setLocation] = useState("");
  const [tone, setTone] = useState("trustworthy");
  const [offer, setOffer] = useState("");
  const [language, setLanguage] = useState<"en" | "te" | "hi" | "kn">("en");
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<CopySuggestions | null>(null);

  async function run() {
    setBusy(true); onError(null);
    try {
      const r = await generateCopy(clientId, { ad_type: adType, product, audience, location, tone, offer, language, facts });
      setResult(r);
      onChoose({ headline: r.headlines[0], primary_text: r.primary_texts[0], description: r.descriptions[0], cta: r.cta_recommendations[0]?.cta });
    } catch (err) { onError(errorText(err, "Could not generate copy")); } finally { setBusy(false); }
  }

  const pick = (key: "headline" | "primary_text" | "description", value: string) => onChoose({ ...chosen, [key]: value });
  return (
    <div className="copy-writer">
      <div className="form-grid-2">
        <label className="form-field"><span>Property or offer</span><input value={product} maxLength={2000} onChange={(e) => setProduct(e.target.value)} placeholder="Green Villas, gated community near Guntur" /></label>
        <label className="form-field"><span>Target audience</span><input value={audience} maxLength={500} onChange={(e) => setAudience(e.target.value)} placeholder="Families upgrading from apartments" /></label>
        <label className="form-field"><span>Location</span><input value={location} maxLength={160} onChange={(e) => setLocation(e.target.value)} /></label>
        <label className="form-field"><span>Offer <small>optional</small></span><input value={offer} maxLength={500} onChange={(e) => setOffer(e.target.value)} /></label>
        <label className="form-field"><span>Brand tone</span><input value={tone} maxLength={80} onChange={(e) => setTone(e.target.value)} /></label>
        <label className="form-field"><span>Language</span>
          <select value={language} onChange={(e) => setLanguage(e.target.value as typeof language)}>
            <option value="en">English</option><option value="te">Telugu (beta)</option><option value="hi">Hindi (beta)</option><option value="kn">Kannada (beta)</option>
          </select></label>
      </div>
      <div className="row-actions"><button type="button" className="primary-button" disabled={busy || !product.trim()} onClick={() => void run()}>{busy ? "Writing…" : "Write variations"}</button></div>
      {result && (
        <div className="copy-results">
          <ClaimCheckNotice check={result.claim_check} />
          <p className="targeting-note">AI-generated suggestions. They are not guaranteed to perform better; test them.</p>
          <CopyGroup title="Headlines" name="headline" options={result.headlines} value={chosen.headline} onPick={(v) => pick("headline", v)} />
          <CopyGroup title="Primary text" name="primary_text" options={result.primary_texts} value={chosen.primary_text} onPick={(v) => pick("primary_text", v)} />
          <CopyGroup title="Descriptions" name="description" options={result.descriptions} value={chosen.description} onPick={(v) => pick("description", v)} />
          <div className="copy-group"><h4>Call to action</h4>
            {result.cta_recommendations.map((r) => <p key={r.cta}><strong>{ctaLabel(r.cta)}</strong> — {r.reason}</p>)}</div>
          {result.concepts.length > 0 && <div className="copy-group"><h4>Creative concepts</h4>
            <ul className="concept-list">{result.concepts.map((c) => (
              <li key={c.title}><strong>{c.title}</strong><span>{c.angle}</span><small>{c.visual}</small>
                <button type="button" className="text-button" onClick={() => onUseConcept(c.visual)}>Use as artwork prompt →</button></li>
            ))}</ul></div>}
          <p className="hint">The selected lines are applied when you generate artwork or press “Use selected copy” on a creative.</p>
        </div>
      )}
    </div>
  );
}

function CopyGroup({ title, name, options, value, onPick }: { title: string; name: string; options: string[]; value?: string; onPick: (v: string) => void }) {
  return (
    <fieldset className="copy-group"><legend>{title}</legend>
      {options.map((o) => (
        <label key={o} className={`copy-option${value === o ? " selected" : ""}`}>
          <input type="radio" name={name} checked={value === o} onChange={() => onPick(o)} /><span>{o}</span><small>{o.length}</small>
        </label>
      ))}
    </fieldset>
  );
}

function AspectPicker({ value, onChange }: { value: AdAspect; onChange: (a: AdAspect) => void }) {
  return (
    <div className="form-field"><span>Format <small>Campex saves the artwork at this exact Meta size</small></span>
      <div className="preset-chips" role="radiogroup" aria-label="Artwork format">
        {AD_ASPECTS.map((o) => (
          <button key={o.id} type="button" role="radio" aria-checked={value === o.id} className={`chip${value === o.id ? " active" : ""}`}
            onClick={() => onChange(o.id)}>{o.label} <small>{o.hint}</small></button>))}
      </div>
    </div>
  );
}

/** Meta and the server resizer need PNG/JPEG; WebP uploads are converted in the browser first. */
async function webpToPng(file: File): Promise<File> {
  const bitmap = await createImageBitmap(file);
  const canvas = document.createElement("canvas");
  canvas.width = bitmap.width;
  canvas.height = bitmap.height;
  canvas.getContext("2d")?.drawImage(bitmap, 0, 0);
  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, "image/png"));
  if (!blob) throw new Error("Could not convert the WebP image; upload a PNG or JPEG.");
  return new File([blob], file.name.replace(/\.webp$/i, ".png"), { type: "image/png" });
}

function ArtworkMaker({ clientId, adType, facts, prompt, onPrompt, chosen, onError, onCreated }: {
  clientId: string; adType: string; facts: string[]; prompt: string; onPrompt: (p: string) => void;
  chosen: Record<string, string | undefined>; onError: (e: string | null) => void; onCreated: (id: string) => void;
}) {
  const [name, setName] = useState("");
  const [aspect, setAspect] = useState<AdAspect>("9:16");
  const [reference, setReference] = useState<File | null>(null);
  const [upload, setUpload] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [limits, setLimits] = useState<{ video_enabled: boolean; max_video_seconds: number; max_video_mb: number } | null>(null);
  const [video, setVideo] = useState<{ file: File; durationMs: number; thumbnail: Blob; thumbUrl: string } | null>(null);
  const [progress, setProgress] = useState<number | null>(null);
  useEffect(() => { getMediaLimits().then(setLimits).catch(() => setLimits(null)); }, []);
  useEffect(() => () => { if (video) URL.revokeObjectURL(video.thumbUrl); }, [video]);

  async function chooseVideo(file: File | null) {
    setVideo(null); onError(null);
    if (!file || !limits) return;
    if (!["video/mp4", "video/quicktime"].includes(file.type)) { onError("Use an MP4 or MOV video."); return; }
    if (file.size > limits.max_video_mb * 1024 * 1024) { onError(`Videos must be ${limits.max_video_mb} MB or smaller.`); return; }
    try {
      const info = await inspectVideo(file);
      if (info.durationMs > limits.max_video_seconds * 1000 + 500) {
        onError(`Videos can be at most ${limits.max_video_seconds} seconds; this one is ${formatDuration(info.durationMs)}.`);
        return;
      }
      setVideo({ file, ...info, thumbUrl: URL.createObjectURL(info.thumbnail) });
    } catch (err) {
      onError(err instanceof Error ? err.message : "Could not read the video");
    }
  }

  async function doVideoUpload() {
    if (!video) return;
    setBusy(true); setProgress(0); onError(null);
    try {
      const detail = await uploadVideoCreative(clientId, { name: name.trim() || video.file.name, adType, facts, video: video.file, thumbnail: video.thumbnail }, setProgress);
      await applyCopy(detail);
      setVideo(null);
      onCreated(detail.asset.id);
    } catch (err) { onError(errorText(err, "Could not upload the video")); } finally { setBusy(false); setProgress(null); }
  }

  async function applyCopy(detail: CreativeDetail) {
    if (!chosen.headline && !chosen.primary_text) return;
    await editCreative(detail.asset.id, { headline: chosen.headline, primary_text: chosen.primary_text,
      description: chosen.description, cta: chosen.cta, change_note: "Copy from AI suggestions" });
  }

  function checkFile(file: File | null): boolean {
    if (file && file.size > MAX_UPLOAD) { onError("Images must be 8 MB or smaller."); return false; }
    if (file && !["image/png", "image/jpeg", "image/webp"].includes(file.type)) { onError("Use a PNG, JPEG or WebP image."); return false; }
    return true;
  }

  async function generate() {
    if (!checkFile(reference)) return;
    setBusy(true); onError(null);
    try {
      const detail = await generateArtwork(clientId, { name: name.trim() || "Untitled creative", ad_type: adType, prompt, facts, aspect,
        ...(reference ? { reference_base64: await fileToBase64(reference), reference_mime: reference.type } : {}) });
      await applyCopy(detail);
      onCreated(detail.asset.id);
    } catch (err) { onError(errorText(err, "Could not generate artwork")); } finally { setBusy(false); }
  }

  async function doUpload() {
    if (!upload || !checkFile(upload)) return;
    setBusy(true); onError(null);
    try {
      const image = upload.type === "image/webp" ? await webpToPng(upload) : upload;
      const detail = await uploadArtwork(clientId, { name: name.trim() || upload.name, ad_type: adType, facts, aspect,
        image_base64: await fileToBase64(image), mime_type: image.type });
      await applyCopy(detail);
      onCreated(detail.asset.id);
    } catch (err) { onError(errorText(err, "Could not upload the image")); } finally { setBusy(false); }
  }

  return (
    <div className="artwork-maker">
      <label className="form-field"><span>Creative name</span><input value={name} maxLength={160} onChange={(e) => setName(e.target.value)} placeholder="Diwali site-visit villa" /></label>
      <AspectPicker value={aspect} onChange={setAspect} />
      <label className="form-field"><span>Describe the artwork <small>text, prices and logos are never drawn into the image</small></span>
        <textarea rows={3} maxLength={3000} value={prompt} onChange={(e) => onPrompt(e.target.value)} placeholder="Warm evening light on an east-facing villa with a lawn, family arriving" /></label>
      <div className="form-grid-2">
        <label className="form-field"><span>Reference image <small>optional · needs a GPT Image model</small></span>
          <input type="file" accept="image/png,image/jpeg,image/webp" onChange={(e) => setReference(e.target.files?.[0] ?? null)} /></label>
        <label className="form-field"><span>Or upload finished artwork</span>
          <input type="file" accept="image/png,image/jpeg,image/webp" onChange={(e) => setUpload(e.target.files?.[0] ?? null)} /></label>
      </div>
      <div className="video-upload">
        <label className="form-field"><span>Or upload a video ad <small>{limits?.video_enabled
          ? `MP4 or MOV · up to ${limits.max_video_seconds} s · ${limits.max_video_mb} MB`
          : "not available: the server has no media storage configured"}</small></span>
          <input type="file" accept="video/mp4,video/quicktime" disabled={!limits?.video_enabled || busy}
            onChange={(e) => void chooseVideo(e.target.files?.[0] ?? null)} /></label>
        {video && (
          <div className="video-chosen">
            <img src={video.thumbUrl} alt="Video thumbnail" />
            <div><strong>{video.file.name}</strong><small>{formatDuration(video.durationMs)} · {(video.file.size / 1024 / 1024).toFixed(1)} MB · thumbnail captured from the video</small>
              {progress !== null && <div className="upload-progress" role="progressbar" aria-valuenow={Math.round(progress * 100)} aria-valuemin={0} aria-valuemax={100}>
                <span style={{ width: `${Math.round(progress * 100)}%` }} /></div>}</div>
            <button type="button" className="primary-button" disabled={busy} onClick={() => void doVideoUpload()}>{busy ? `Uploading ${Math.round((progress ?? 0) * 100)}%` : "Upload video"}</button>
          </div>
        )}
      </div>
      <p className="targeting-note">Generated artwork is illustrative. Factual claims belong in the copy, where they are checked and approved.</p>
      <div className="row-actions">
        <button type="button" className="secondary-button" disabled={busy || !upload} onClick={() => void doUpload()}>Upload</button>
        <button type="button" className="primary-button" disabled={busy || prompt.trim().length < 3} onClick={() => void generate()}>{busy ? "Working…" : "Generate artwork"}</button>
      </div>
    </div>
  );
}

function CreativeDrawer({ id, chosen, onClose, onChanged }: { id: string; chosen: Record<string, string | undefined>; onClose: () => void; onChanged: () => void }) {
  const [detail, setDetail] = useState<CreativeDetail | null>(null);
  const [busy, setBusy] = useState<CreativeAction | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [draft, setDraft] = useState({ headline: "", primary_text: "", description: "", cta: "LEARN_MORE", facts: "" });
  const [ack, setAck] = useState(false);
  const [rejectNote, setRejectNote] = useState("");

  const sync = useCallback((d: CreativeDetail) => {
    setDetail(d);
    const v = d.versions[0];
    setDraft({ headline: v?.headline ?? "", primary_text: v?.primary_text ?? "", description: v?.description ?? "", cta: v?.cta ?? "LEARN_MORE", facts: (v?.facts ?? []).join("\n") });
    setAck(false);
  }, []);
  useEffect(() => { getCreative(id).then(sync).catch((err) => setError(errorText(err, "Could not load creative"))); }, [id, sync]);

  async function run(action: CreativeAction, fn: () => Promise<CreativeDetail>, done: string) {
    setBusy(action); setError(null); setNote(null);
    try { sync(await fn()); setNote(done); onChanged(); } catch (err) { setError(errorText(err, "That action could not be completed")); } finally { setBusy(null); }
  }

  const current = detail?.versions[0];
  const allowed = new Set(detail?.allowed_actions ?? []);
  const check = current?.claim_check ?? null;
  const needsAck = Boolean(check?.unsupported_numbers.length);
  const blocked = Boolean(check?.flagged_phrases.length);
  const dirty = current && (draft.headline !== (current.headline ?? "") || draft.primary_text !== (current.primary_text ?? "")
    || draft.description !== (current.description ?? "") || draft.cta !== (current.cta ?? "LEARN_MORE") || draft.facts !== current.facts.join("\n"));

  return (
    <div className="drawer-root">
      <button type="button" className="backdrop" aria-label="Close creative" onClick={onClose} />
      <aside className="drawer crm-drawer exec-drawer" role="dialog" aria-modal="true" aria-labelledby="creative-title">
        <div className="drawer-head">
          <div><p className="eyebrow">Creative</p><h2 id="creative-title">{detail?.asset.name ?? "Loading…"}</h2>
            {detail && <div className="exec-head-meta"><CreativeStatusPill status={detail.asset.status} /><small>Version {detail.asset.current_version}{detail.asset.approved_version ? ` · approved v${detail.asset.approved_version}` : ""}</small></div>}</div>
          <button type="button" className="refresh" onClick={onClose}>Close</button>
        </div>
        {!detail || !current ? <p className="muted">{error ?? "Loading creative…"}</p> : (
          <div className="drawer-body">
            <ErrorBanner error={error} onDismiss={() => setError(null)} />
            {note && <p className="hint saved" role="status">{note}</p>}

            <div className="ad-preview" aria-label="Ad preview">
              <div className="ad-preview-head"><span className="ad-avatar" /> <span><strong>Your Page</strong><small>Sponsored</small></span></div>
              <p className="ad-preview-text">{draft.primary_text || "Primary text appears here."}</p>
              {current.video_id
                ? <AuthVideo videoId={current.video_id} posterId={current.image_id} className="ad-preview-image" label={detail.asset.name} />
                : <AuthImage imageId={current.image_id} alt={`Artwork for ${detail.asset.name}`} className="ad-preview-image" />}
              <div className="ad-preview-foot"><span><small>{draft.description || " "}</small><strong>{draft.headline || "Headline"}</strong></span>
                <span className="ad-preview-cta">{ctaLabel(draft.cta)}</span></div>
            </div>
            {current.image_source === "ai_generated" && <p className="targeting-note">AI-generated artwork · illustrative only, not a factual claim.</p>}
            {allowed.has("edit") && !current.video_id && (
              <div className="reformat-row" role="group" aria-label="Resize artwork">
                <span>Resize artwork</span>
                {AD_ASPECTS.map((o) => (
                  <button key={o.id} type="button" className="chip" disabled={busy !== null} title={o.hint}
                    onClick={() => void run("edit", () => reformatCreative(id, o.id),
                      `Made a ${o.label} version (${o.hint.split(" · ")[1]}). Review and approve it again before using it.`)}>
                    Make {o.label}</button>))}
              </div>
            )}

            {allowed.has("edit") && (
              <section className="config-form">
                <div className="section-title-row"><h3>Copy</h3>
                  {(chosen.headline || chosen.primary_text) && <button type="button" className="text-button" onClick={() => setDraft((d) => ({ ...d,
                    headline: chosen.headline ?? d.headline, primary_text: chosen.primary_text ?? d.primary_text,
                    description: chosen.description ?? d.description, cta: chosen.cta ?? d.cta }))}>Use selected copy</button>}</div>
                <label className="form-field"><span>Headline <small>{draft.headline.length}/80</small></span><input maxLength={80} value={draft.headline} onChange={(e) => setDraft({ ...draft, headline: e.target.value })} /></label>
                <label className="form-field"><span>Primary text <small>{draft.primary_text.length}/1500</small></span><textarea rows={4} maxLength={1500} value={draft.primary_text} onChange={(e) => setDraft({ ...draft, primary_text: e.target.value })} /></label>
                <div className="form-grid-2">
                  <label className="form-field"><span>Description</span><input maxLength={255} value={draft.description} onChange={(e) => setDraft({ ...draft, description: e.target.value })} /></label>
                  <label className="form-field"><span>Call to action</span><select value={draft.cta} onChange={(e) => setDraft({ ...draft, cta: e.target.value })}>{CTA_OPTIONS.map((c) => <option key={c} value={c}>{ctaLabel(c)}</option>)}</select></label>
                </div>
                <label className="form-field"><span>Verified facts <small>one per line</small></span><textarea rows={3} value={draft.facts} onChange={(e) => setDraft({ ...draft, facts: e.target.value })} /></label>
                {dirty && <div className="row-actions"><button type="button" className="primary-button" disabled={busy !== null}
                  onClick={() => void run("edit", () => editCreative(id, { headline: draft.headline, primary_text: draft.primary_text, description: draft.description,
                    cta: draft.cta, facts: lines(draft.facts), change_note: "Edited copy" }), "Saved as a new version.")}>{busy === "edit" ? "Saving…" : "Save new version"}</button></div>}
              </section>
            )}

            {detail.asset.status === "REVIEW_PENDING" && <ClaimCheckNotice check={check} />}
            <div className="exec-actions">
              {allowed.has("submit") && <button type="button" className="primary-button" disabled={busy !== null || Boolean(dirty)} title={dirty ? "Save your changes first" : undefined}
                onClick={() => void run("submit", () => submitCreative(id), "Submitted. The copy was checked against your verified facts.")}>{busy === "submit" ? "Checking…" : "Submit for review"}</button>}
              {allowed.has("approve") && <>
                {needsAck && !blocked && <label className="check-line"><input type="checkbox" checked={ack} onChange={(e) => setAck(e.target.checked)} />I verified the flagged figures against project documents.</label>}
                <button type="button" className="primary-button" disabled={busy !== null || blocked || (needsAck && !ack)}
                  title={blocked ? "Remove prohibited claims first" : undefined}
                  onClick={() => void run("approve", () => approveCreative(id, ack), "Approved. Campaigns can now use this version.")}>{busy === "approve" ? "Approving…" : `Approve v${current.version}`}</button>
              </>}
              {allowed.has("archive") && <button type="button" className="text-button link-danger" disabled={busy !== null}
                onClick={() => { if (window.confirm("Archive this creative? It will be hidden from the library.")) void run("archive", () => archiveCreative(id), "Archived."); }}>Archive</button>}
            </div>
            {allowed.has("reject") && (
              <form className="decision-form" onSubmit={(e) => { e.preventDefault(); void run("reject", () => rejectCreative(id, rejectNote.trim()), "Changes requested."); }}>
                <label className="form-field"><span>Request changes</span><textarea rows={2} maxLength={1000} value={rejectNote} onChange={(e) => setRejectNote(e.target.value)} placeholder="What needs to change?" /></label>
                <div className="row-actions"><button type="submit" className="danger-button" disabled={!rejectNote.trim() || busy !== null}>Request changes</button></div>
              </form>
            )}

            <section><h3>Versions</h3>
              <ul className="timeline">{detail.versions.map((v) => (
                <li key={v.version}><span className="timeline-type">v{v.version}{detail.asset.approved_version === v.version ? " · approved" : ""}{v.image_source === "ai_generated" && v.version === detail.versions.at(-1)?.version ? " · AI artwork" : ""}</span>
                  {v.change_note && <p>{v.change_note}</p>}{v.headline && <p className="muted">{v.headline}</p>}
                  <small>{formatWhen(v.created_at)} · {actorName(v.created_by)}</small></li>
              ))}</ul></section>
            {detail.approvals.length > 0 && <section><h3>Review history</h3>
              <ul className="timeline">{detail.approvals.map((a) => (
                <li key={a.id}><span className="timeline-type">{a.decision === "approved" ? "Approved" : "Changes requested"} · v{a.version}</span>
                  {a.note && <p>{a.note}</p>}
                  <small>{formatWhen(a.decided_at)} · {actorName(a.decided_by)}{a.claims_acknowledged ? " · figures verified by approver" : ""}</small></li>
              ))}</ul></section>}
            {current.image_prompt && <details className="tech-details"><summary>Artwork prompt</summary><p className="pre-wrap">{current.image_prompt}</p></details>}
          </div>
        )}
      </aside>
    </div>
  );
}
