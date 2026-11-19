import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { FormEvent } from "react";
import {
  assistCampaign,
  deleteCampaignSkill,
  getMetaConnection,
  listCampaignSkills,
  uploadCampaignSkill,
  type MetaConnectionStatus,
} from "./api";
import type {
  AssistImagePayload,
  CampaignPlatform,
  CampaignSkillSummary,
  LanguageCode,
} from "./types";

type ChatMessage = {
  role: "user" | "assistant";
  content: string;
  imageUrl?: string;
  suggestedCopy?: string;
  suggestedHeadline?: string;
  suggestedCta?: string;
  designNotes?: string;
};

type LocalImage = {
  name: string;
  mime: AssistImagePayload["mime_type"];
  previewUrl: string;
  dataBase64: string;
};

const OBJECTIVES = [
  { id: "leads", label: "Leads" },
  { id: "traffic", label: "Traffic" },
  { id: "sales", label: "Sales" },
  { id: "awareness", label: "Awareness" },
] as const;

const PLATFORMS: Array<{ id: CampaignPlatform; label: string }> = [
  { id: "facebook", label: "Facebook" },
  { id: "instagram", label: "Instagram" },
  { id: "whatsapp_ads", label: "WhatsApp Ads" },
];

const MAX_IMAGE_BYTES = 2_400_000;

function fileToPayload(file: File): Promise<LocalImage> {
  return new Promise((resolve, reject) => {
    if (!["image/jpeg", "image/png", "image/webp"].includes(file.type)) {
      reject(new Error("Use JPEG, PNG, or WebP images"));
      return;
    }
    if (file.size > MAX_IMAGE_BYTES) {
      reject(new Error("Keep each image under about 2.4 MB"));
      return;
    }
    const reader = new FileReader();
    reader.onerror = () => reject(new Error("Could not read image"));
    reader.onload = () => {
      const result = String(reader.result || "");
      const comma = result.indexOf(",");
      if (!result.startsWith("data:") || comma < 0) {
        reject(new Error("Could not encode image"));
        return;
      }
      resolve({
        name: file.name,
        mime: file.type as AssistImagePayload["mime_type"],
        previewUrl: result,
        dataBase64: result.slice(comma + 1),
      });
    };
    reader.readAsDataURL(file);
  });
}

export function AiCampaignAssistant({
  clientName,
  onOpenChannels,
}: {
  clientName?: string;
  onOpenChannels: () => void;
}) {
  const [skills, setSkills] = useState<CampaignSkillSummary[]>([]);
  const [skillId, setSkillId] = useState("");
  const [skillQuery, setSkillQuery] = useState("");
  const [meta, setMeta] = useState<MetaConnectionStatus | null>(null);
  const [adAccountId, setAdAccountId] = useState("");
  const [objective, setObjective] = useState<(typeof OBJECTIVES)[number]["id"]>("leads");
  const [platforms, setPlatforms] = useState<CampaignPlatform[]>(["facebook", "instagram"]);
  const [language, setLanguage] = useState<LanguageCode>("en");
  const [campaignName, setCampaignName] = useState(clientName ? `${clientName} campaign` : "New campaign");
  const [draftCopy, setDraftCopy] = useState("");
  const [prompt, setPrompt] = useState("");
  const [messages, setMessages] = useState<ChatMessage[]>([{
    role: "assistant",
    content: "Ask about live Meta performance, run a skill, or upload images for ad design guidance.",
  }]);
  const [images, setImages] = useState<LocalImage[]>([]);
  const [busy, setBusy] = useState(false);
  const [skillBusy, setSkillBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [designHeadline, setDesignHeadline] = useState("");
  const [designBody, setDesignBody] = useState("");
  const [designCta, setDesignCta] = useState("Learn more");
  const messagesRef = useRef<HTMLDivElement>(null);
  const skillInputRef = useRef<HTMLInputElement>(null);
  const imageInputRef = useRef<HTMLInputElement>(null);

  const refreshSkills = useCallback(async () => {
    try {
      const next = await listCampaignSkills();
      setSkills(next);
      setSkillId((current) => current || next.find((item) => item.featured)?.id || next[0]?.id || "");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not load skills");
    }
  }, []);

  useEffect(() => {
    void refreshSkills();
    void getMetaConnection()
      .then((connection) => {
        setMeta(connection);
        if (connection.ad_accounts[0]) setAdAccountId(connection.ad_accounts[0].id);
      })
      .catch(() => setMeta(null));
  }, [refreshSkills]);

  useEffect(() => {
    messagesRef.current?.scrollTo({ top: messagesRef.current.scrollHeight, behavior: "smooth" });
  }, [messages, busy]);

  const filteredSkills = useMemo(() => {
    const query = skillQuery.trim().toLowerCase();
    if (!query) return skills;
    return skills.filter((skill) =>
      `${skill.name} ${skill.description} ${skill.source}`.toLowerCase().includes(query));
  }, [skills, skillQuery]);

  const selectedSkill = skills.find((skill) => skill.id === skillId) ?? null;
  const previewImage = images[0]?.previewUrl ?? null;
  const metaReady = Boolean(meta?.connected && !meta.expired);

  function togglePlatform(id: CampaignPlatform) {
    setPlatforms((current) =>
      current.includes(id) ? current.filter((item) => item !== id) : [...current, id]);
  }

  async function onUploadImages(fileList: FileList | null) {
    if (!fileList?.length) return;
    setError(null);
    try {
      const next: LocalImage[] = [];
      for (const file of Array.from(fileList).slice(0, 3)) {
        next.push(await fileToPayload(file));
      }
      setImages(next);
      setNotice(next.length === 1
        ? "Image ready for ad design. Ask the assistant for layout and copy."
        : `${next.length} images ready for ad design.`);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not load images");
    } finally {
      if (imageInputRef.current) imageInputRef.current.value = "";
    }
  }

  async function onUploadSkill(fileList: FileList | null) {
    const file = fileList?.[0];
    if (!file) return;
    setSkillBusy(true);
    setError(null);
    try {
      const markdown = await file.text();
      const saved = await uploadCampaignSkill({
        markdown,
        name: file.name.replace(/\.md$/i, ""),
      });
      await refreshSkills();
      setSkillId(saved.id);
      setNotice(`Skill “${saved.name}” uploaded and selected.`);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not upload skill");
    } finally {
      setSkillBusy(false);
      if (skillInputRef.current) skillInputRef.current.value = "";
    }
  }

  async function removeUploadedSkill() {
    if (!selectedSkill || selectedSkill.source !== "uploaded") return;
    if (!window.confirm(`Remove uploaded skill “${selectedSkill.name}”?`)) return;
    setSkillBusy(true);
    try {
      await deleteCampaignSkill(selectedSkill.id);
      setSkillId("");
      await refreshSkills();
      setNotice("Uploaded skill removed.");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not remove skill");
    } finally {
      setSkillBusy(false);
    }
  }

  async function sendPrompt(event: FormEvent) {
    event.preventDefault();
    const question = prompt.trim();
    if (!question || platforms.length === 0 || busy) return;
    const imageSnapshot = images[0]?.previewUrl;
    setMessages((current) => [...current, {
      role: "user",
      content: question,
      imageUrl: imageSnapshot,
    }]);
    setPrompt("");
    setBusy(true);
    setError(null);
    try {
      const result = await assistCampaign({
        campaign_name: campaignName.trim() || "Untitled campaign",
        objective,
        platforms,
        language,
        prompt: question,
        content: draftCopy.trim() || "No draft copy supplied.",
        ad_account_id: adAccountId || undefined,
        skill_id: skillId || undefined,
        mode: images.length ? "design" : "assist",
        images: images.map((image) => ({
          name: image.name,
          mime_type: image.mime,
          data_base64: image.dataBase64,
        })),
      });
      if (result.suggested_headline) setDesignHeadline(result.suggested_headline.split("\n")[0].slice(0, 80));
      if (result.suggested_copy) setDesignBody(result.suggested_copy);
      if (result.suggested_cta) setDesignCta(result.suggested_cta.split("\n")[0].slice(0, 40));
      setMessages((current) => [...current, {
        role: "assistant",
        content: result.reply,
        suggestedCopy: result.suggested_copy ?? undefined,
        suggestedHeadline: result.suggested_headline ?? undefined,
        suggestedCta: result.suggested_cta ?? undefined,
        designNotes: result.design_notes ?? undefined,
      }]);
    } catch (err) {
      const message = err instanceof Error ? err.message : "The assistant is unavailable.";
      setMessages((current) => [...current, {
        role: "assistant",
        content: `I couldn’t complete that request: ${message}`,
      }]);
    } finally {
      setBusy(false);
    }
  }

  function applySuggestion(message: ChatMessage) {
    if (message.suggestedHeadline) setDesignHeadline(message.suggestedHeadline.split("\n")[0].slice(0, 80));
    if (message.suggestedCopy) {
      setDesignBody(message.suggestedCopy);
      setDraftCopy(message.suggestedCopy);
    }
    if (message.suggestedCta) setDesignCta(message.suggestedCta.split("\n")[0].slice(0, 40));
    setNotice("Suggestion applied to the design preview. Review facts before publishing.");
  }

  return (
    <div className="assistant-page">
      <aside className="assistant-side">
        <section className="panel-card">
          <div className="panel-head">
            <div>
              <p className="step-label">Skills</p>
              <h3>Playbooks</h3>
            </div>
            <button
              type="button"
              className="secondary-button"
              disabled={skillBusy}
              onClick={() => skillInputRef.current?.click()}
            >
              {skillBusy ? "Uploading…" : "Upload skill"}
            </button>
            <input
              ref={skillInputRef}
              type="file"
              accept=".md,text/markdown,text/plain"
              hidden
              onChange={(event) => void onUploadSkill(event.target.files)}
            />
          </div>
          <label className="form-field">
            <span>Search</span>
            <input
              value={skillQuery}
              onChange={(event) => setSkillQuery(event.target.value)}
              placeholder="Audit, creative, budget…"
            />
          </label>
          <div className="skill-list" role="listbox" aria-label="Campaign skills">
            {filteredSkills.map((skill) => (
              <button
                key={skill.id}
                type="button"
                role="option"
                aria-selected={skill.id === skillId}
                className={`skill-chip${skill.id === skillId ? " active" : ""}`}
                onClick={() => setSkillId(skill.id)}
              >
                <strong>{skill.name}</strong>
                <small>{skill.source === "uploaded" ? "Uploaded" : skill.featured ? "Featured" : "Library"}</small>
                <span>{skill.description}</span>
              </button>
            ))}
            {!filteredSkills.length && <p className="empty-state">No skills match that search.</p>}
          </div>
          {selectedSkill?.source === "uploaded" && (
            <button type="button" className="text-button" disabled={skillBusy} onClick={() => void removeUploadedSkill()}>
              Remove uploaded skill
            </button>
          )}
        </section>

        <section className="panel-card">
          <div className="panel-head">
            <div>
              <p className="step-label">Context</p>
              <h3>Campaign setup</h3>
            </div>
          </div>
          <label className="form-field">
            <span>Campaign name</span>
            <input value={campaignName} onChange={(event) => setCampaignName(event.target.value)} />
          </label>
          <label className="form-field">
            <span>Objective</span>
            <select value={objective} onChange={(event) => setObjective(event.target.value as typeof objective)}>
              {OBJECTIVES.map((item) => <option key={item.id} value={item.id}>{item.label}</option>)}
            </select>
          </label>
          <div className="platform-row">
            {PLATFORMS.map((platform) => (
              <button
                key={platform.id}
                type="button"
                className={`platform-pill${platforms.includes(platform.id) ? " active" : ""}`}
                aria-pressed={platforms.includes(platform.id)}
                onClick={() => togglePlatform(platform.id)}
              >
                {platform.label}
              </button>
            ))}
          </div>
          <label className="form-field">
            <span>Language</span>
            <select value={language} onChange={(event) => setLanguage(event.target.value as LanguageCode)}>
              <option value="en">English</option>
              <option value="te">Telugu</option>
            </select>
          </label>
          <label className="form-field">
            <span>Ad account</span>
            <select
              value={adAccountId}
              onChange={(event) => setAdAccountId(event.target.value)}
              disabled={!metaReady}
            >
              <option value="">{metaReady ? "Select ad account" : "Connect Meta under Channels"}</option>
              {(meta?.ad_accounts ?? []).map((account) => (
                <option key={account.id} value={account.id}>{account.name || account.id}</option>
              ))}
            </select>
          </label>
          {!metaReady && (
            <p className="inline-hint">
              Live Meta insights need a Business connection.{" "}
              <button type="button" className="text-button" onClick={onOpenChannels}>Open Channels</button>
            </p>
          )}
          <label className="form-field">
            <span>Draft copy (optional)</span>
            <textarea
              rows={4}
              value={draftCopy}
              onChange={(event) => setDraftCopy(event.target.value)}
              placeholder="Paste existing ad copy for the assistant to refine."
            />
          </label>
        </section>
      </aside>

      <section className="assistant-main panel-card">
        <div className="assistant-head">
          <span className="assistant-spark" aria-hidden="true">✦</span>
          <div>
            <p className="step-label">AI Campaign Assistant</p>
            <h3>{selectedSkill?.name ?? "Choose a skill"}</h3>
          </div>
          <span className="ai-mode">{metaReady ? "Live Meta" : "Draft mode"}</span>
        </div>
        <p className="assistant-intro">
          Uses your selected skill with live Meta data when connected. Upload images to get ad layout and copy suggestions.
        </p>
        {error && <p className="notice error" role="alert">{error}</p>}
        {notice && <p className="notice success" role="status">{notice}</p>}

        <div className="assistant-messages assistant-messages-tall" ref={messagesRef}>
          {messages.map((message, index) => (
            <article key={`${message.role}-${index}`} className={`chat-message ${message.role}`}>
              <span>{message.role === "user" ? "You" : "Assistant"}</span>
              {message.imageUrl && <img className="chat-thumb" src={message.imageUrl} alt="" />}
              <p>{message.content}</p>
              {(message.suggestedCopy || message.suggestedHeadline) && (
                <button type="button" className="text-button" onClick={() => applySuggestion(message)}>
                  Apply to design preview
                </button>
              )}
            </article>
          ))}
          {busy && <p className="thinking"><i /><span>Working with your skill and Meta data…</span></p>}
        </div>

        <form className="assistant-compose" onSubmit={(event) => void sendPrompt(event)}>
          <div className="compose-toolbar">
            <button type="button" className="secondary-button" onClick={() => imageInputRef.current?.click()}>
              {images.length ? `Images (${images.length})` : "Upload images"}
            </button>
            <input
              ref={imageInputRef}
              type="file"
              accept="image/jpeg,image/png,image/webp"
              multiple
              hidden
              onChange={(event) => void onUploadImages(event.target.files)}
            />
            {images.length > 0 && (
              <button type="button" className="text-button" onClick={() => setImages([])}>Clear images</button>
            )}
          </div>
          {images.length > 0 && (
            <div className="image-strip">
              {images.map((image) => (
                <figure key={image.name}>
                  <img src={image.previewUrl} alt={image.name} />
                  <figcaption>{image.name}</figcaption>
                </figure>
              ))}
            </div>
          )}
          <textarea
            rows={3}
            value={prompt}
            onChange={(event) => setPrompt(event.target.value)}
            placeholder={images.length
              ? "Ask for ad design: safer text zones, headline, CTA, and mobile feed fit…"
              : "Ask for an audit, copy variants, budget pacing, or creative feedback…"}
          />
          <div className="assistant-compose-foot">
            <small>{selectedSkill ? `Skill: ${selectedSkill.name}` : "No skill selected"}</small>
            <button className="send-button" type="submit" disabled={busy || !prompt.trim() || platforms.length === 0} aria-label="Send">
              ↑
            </button>
          </div>
        </form>
      </section>

      <aside className="assistant-preview">
        <section className="panel-card design-preview-card">
          <div className="panel-head">
            <div>
              <p className="step-label">Ad design</p>
              <h3>Feed preview</h3>
            </div>
          </div>
          <div className="design-preview">
            <div className="preview-account">
              <span className="channel-mark facebook">f</span>
              <span>
                <strong>{clientName ?? "Your business"}</strong>
                <small>Sponsored · Preview only</small>
              </span>
              <span className="preview-menu">···</span>
            </div>
            <p className="design-body-copy">{designBody || "Primary text will appear here after the assistant suggests copy."}</p>
            <div className={`design-media${previewImage ? "" : " empty"}`}>
              {previewImage ? (
                <img src={previewImage} alt="Uploaded creative" />
              ) : (
                <span>Upload an image to preview the creative</span>
              )}
              {(designHeadline || designCta) && previewImage && (
                <div className="design-overlay">
                  {designHeadline && <strong>{designHeadline}</strong>}
                  {designCta && <em>{designCta}</em>}
                </div>
              )}
            </div>
            <div className="design-cta-row">
              <span>{designHeadline || "Headline"}</span>
              <button type="button" className="secondary-button" disabled>{designCta || "CTA"}</button>
            </div>
          </div>
          <label className="form-field">
            <span>Headline</span>
            <input value={designHeadline} onChange={(event) => setDesignHeadline(event.target.value)} maxLength={80} />
          </label>
          <label className="form-field">
            <span>Primary text</span>
            <textarea rows={4} value={designBody} onChange={(event) => setDesignBody(event.target.value)} />
          </label>
          <label className="form-field">
            <span>CTA</span>
            <input value={designCta} onChange={(event) => setDesignCta(event.target.value)} maxLength={40} />
          </label>
          <p className="ai-privacy">Preview only. Publishing stays in Campaign studio after you review facts.</p>
        </section>
      </aside>
    </div>
  );
}
