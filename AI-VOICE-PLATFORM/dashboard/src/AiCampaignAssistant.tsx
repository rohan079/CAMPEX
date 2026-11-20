import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { FormEvent, KeyboardEvent } from "react";
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
};

type LocalImage = {
  name: string;
  mime: AssistImagePayload["mime_type"];
  previewUrl: string;
  dataBase64: string;
};

const MAX_IMAGE_BYTES = 2_400_000;
const DEFAULT_PLATFORMS: CampaignPlatform[] = ["facebook", "instagram"];

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
  clientId,
  clientName,
  onOpenChannels,
}: {
  clientId?: string;
  clientName?: string;
  onOpenChannels: () => void;
}) {
  const [skills, setSkills] = useState<CampaignSkillSummary[]>([]);
  const [skillId, setSkillId] = useState("");
  const [skillMenuOpen, setSkillMenuOpen] = useState(false);
  const [skillQuery, setSkillQuery] = useState("");
  const [meta, setMeta] = useState<MetaConnectionStatus | null>(null);
  const [adAccountId, setAdAccountId] = useState("");
  const [language] = useState<LanguageCode>("en");
  const [prompt, setPrompt] = useState("");
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [images, setImages] = useState<LocalImage[]>([]);
  const [busy, setBusy] = useState(false);
  const [skillBusy, setSkillBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const messagesRef = useRef<HTMLDivElement>(null);
  const skillInputRef = useRef<HTMLInputElement>(null);
  const imageInputRef = useRef<HTMLInputElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

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

  useEffect(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = "0px";
    el.style.height = `${Math.min(el.scrollHeight, 180)}px`;
  }, [prompt]);

  const filteredSkills = useMemo(() => {
    const query = skillQuery.trim().toLowerCase();
    if (!query) return skills;
    return skills.filter((skill) =>
      `${skill.name} ${skill.description}`.toLowerCase().includes(query));
  }, [skills, skillQuery]);

  const selectedSkill = skills.find((skill) => skill.id === skillId) ?? null;
  const metaReady = Boolean(meta?.connected && !meta.expired);
  const empty = messages.length === 0 && !busy;

  async function onUploadImages(fileList: FileList | null) {
    if (!fileList?.length) return;
    setError(null);
    try {
      const next: LocalImage[] = [];
      for (const file of Array.from(fileList).slice(0, 3)) {
        next.push(await fileToPayload(file));
      }
      setImages(next);
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
      setSkillMenuOpen(false);
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
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not remove skill");
    } finally {
      setSkillBusy(false);
    }
  }

  async function sendPrompt(event?: FormEvent) {
    event?.preventDefault();
    const question = prompt.trim();
    if (!question || busy) return;
    const imageSnapshot = images[0]?.previewUrl;
    const attached = [...images];
    setMessages((current) => [...current, {
      role: "user",
      content: question,
      imageUrl: imageSnapshot,
    }]);
    setPrompt("");
    setBusy(true);
    setError(null);
    setSkillMenuOpen(false);
    try {
      const result = await assistCampaign({
        campaign_name: clientName ? `${clientName} campaign` : "Campaign assistant",
        objective: "leads",
        platforms: DEFAULT_PLATFORMS,
        language,
        prompt: question,
        content: "No draft copy supplied.",
        ad_account_id: adAccountId || undefined,
        client_id: clientId || undefined,
        client_name: clientName || undefined,
        skill_id: skillId || undefined,
        mode: attached.length ? "design" : "assist",
        images: attached.map((image) => ({
          name: image.name,
          mime_type: image.mime,
          data_base64: image.dataBase64,
        })),
      });
      setMessages((current) => [...current, {
        role: "assistant",
        content: result.reply,
      }]);
      setImages([]);
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

  function onComposerKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      void sendPrompt();
    }
  }

  return (
    <div className="gpt-shell">
      <div className="gpt-thread" ref={messagesRef}>
        {empty ? (
          <div className="gpt-empty">
            <div className="gpt-empty-mark" aria-hidden="true">✦</div>
            <h2>How can I help with your campaigns?</h2>
            <p>
              {clientName
                ? `Active client: ${clientName}. `
                : "Select a client in the top bar before asking about that client’s ads. "}
              {metaReady
                ? "Ask about Meta performance, run a skill, or attach images for ad design."
                : "Connect Meta under Channels for live insights, or ask about copy and creative now."}
            </p>
            {!metaReady && (
              <button type="button" className="text-button" onClick={onOpenChannels}>Open Channels</button>
            )}
            <div className="gpt-starters">
              {[
                "Audit my Meta account for wasted spend",
                "Write 5 ad copy variants for lead gen",
                "Review this creative for Instagram feed",
              ].map((starter) => (
                <button
                  key={starter}
                  type="button"
                  className="gpt-starter"
                  onClick={() => {
                    setPrompt(starter);
                    textareaRef.current?.focus();
                  }}
                >
                  {starter}
                </button>
              ))}
            </div>
          </div>
        ) : (
          <div className="gpt-messages">
            {messages.map((message, index) => (
              <article key={`${message.role}-${index}`} className={`gpt-row ${message.role}`}>
                <div className="gpt-avatar" aria-hidden="true">{message.role === "user" ? "You" : "AI"}</div>
                <div className="gpt-bubble">
                  {message.imageUrl && <img className="gpt-attach" src={message.imageUrl} alt="" />}
                  <p>{message.content}</p>
                </div>
              </article>
            ))}
            {busy && (
              <article className="gpt-row assistant">
                <div className="gpt-avatar" aria-hidden="true">AI</div>
                <div className="gpt-bubble"><p className="thinking"><i /><span>Thinking…</span></p></div>
              </article>
            )}
          </div>
        )}
      </div>

      <div className="gpt-dock">
        {error && <p className="notice error gpt-error" role="alert">{error}</p>}

        <form className="gpt-composer" onSubmit={(event) => void sendPrompt(event)}>
          {images.length > 0 && (
            <div className="gpt-image-strip">
              {images.map((image) => (
                <figure key={image.name}>
                  <img src={image.previewUrl} alt={image.name} />
                  <button
                    type="button"
                    aria-label={`Remove ${image.name}`}
                    onClick={() => setImages((current) => current.filter((item) => item.name !== image.name))}
                  >
                    ×
                  </button>
                </figure>
              ))}
            </div>
          )}

          <textarea
            ref={textareaRef}
            rows={1}
            value={prompt}
            onChange={(event) => setPrompt(event.target.value)}
            onKeyDown={onComposerKeyDown}
            placeholder="Message AI Campaign Assistant"
          />

          <div className="gpt-composer-bar">
            <div className="gpt-tools">
              <div className="gpt-skill-wrap">
                <button
                  type="button"
                  className={`gpt-tool${skillMenuOpen ? " active" : ""}`}
                  onClick={() => setSkillMenuOpen((open) => !open)}
                >
                  {selectedSkill?.name ?? "Skill"}
                </button>
                {skillMenuOpen && (
                  <div className="gpt-skill-menu" role="listbox" aria-label="Skills">
                    <input
                      value={skillQuery}
                      onChange={(event) => setSkillQuery(event.target.value)}
                      placeholder="Search skills"
                      autoFocus
                    />
                    <div className="gpt-skill-options">
                      {filteredSkills.map((skill) => (
                        <button
                          key={skill.id}
                          type="button"
                          role="option"
                          aria-selected={skill.id === skillId}
                          className={skill.id === skillId ? "active" : undefined}
                          onClick={() => {
                            setSkillId(skill.id);
                            setSkillMenuOpen(false);
                            setSkillQuery("");
                          }}
                        >
                          <strong>{skill.name}</strong>
                          <span>{skill.source === "uploaded" ? "Uploaded" : skill.featured ? "Featured" : "Library"}</span>
                        </button>
                      ))}
                      {!filteredSkills.length && <p>No skills found</p>}
                    </div>
                    <div className="gpt-skill-actions">
                      <button
                        type="button"
                        disabled={skillBusy}
                        onClick={() => skillInputRef.current?.click()}
                      >
                        {skillBusy ? "Uploading…" : "Upload skill"}
                      </button>
                      {selectedSkill?.source === "uploaded" && (
                        <button type="button" disabled={skillBusy} onClick={() => void removeUploadedSkill()}>
                          Remove
                        </button>
                      )}
                    </div>
                  </div>
                )}
              </div>

              <button
                type="button"
                className="gpt-tool"
                onClick={() => imageInputRef.current?.click()}
              >
                {images.length ? `Image · ${images.length}` : "Upload image"}
              </button>
              <input
                ref={skillInputRef}
                type="file"
                accept=".md,text/markdown,text/plain"
                hidden
                onChange={(event) => void onUploadSkill(event.target.files)}
              />
              <input
                ref={imageInputRef}
                type="file"
                accept="image/jpeg,image/png,image/webp"
                multiple
                hidden
                onChange={(event) => void onUploadImages(event.target.files)}
              />
            </div>

            <button
              className="gpt-send"
              type="submit"
              disabled={busy || !prompt.trim()}
              aria-label="Send message"
            >
              ↑
            </button>
          </div>
        </form>

        <p className="gpt-footnote">
          {clientName ? `Client: ${clientName}` : "No client selected"}
          {" · "}
          {selectedSkill ? `Skill: ${selectedSkill.name}` : "No skill selected"}
          {metaReady ? " · Live Meta connected" : " · Draft mode"}
        </p>
      </div>
    </div>
  );
}
