import { useEffect, useRef, useState } from "react";
import { getLead, updateLeadLanguage } from "./api";
import { LanguagePill, StatusPill } from "./pills";
import { SaleForm } from "./SaleForm";
import { formatDay, formatWhen, labelLanguage } from "./format";
import type { Client, LanguageCode, LeadDetail } from "./types";

export function LeadDrawer({
  id,
  clients,
  onClose,
  onChanged,
}: {
  id: string;
  clients: Client[];
  onClose: () => void;
  onChanged: () => void;
}) {
  const [lead, setLead] = useState<LeadDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const savingRef = useRef(false);

  useEffect(() => {
    let cancelled = false;
    async function pull() {
      if (savingRef.current) return;
      try {
        const next = await getLead(id);
        if (!cancelled) {
          setLead(next);
          setError(null);
        }
      } catch (err) {
        if (!cancelled) setError(err instanceof Error ? err.message : "Could not load this lead");
      }
    }
    void pull();
    const timer = window.setInterval(() => void pull(), 5000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [id]);

  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      if (event.key === "Escape") onClose();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  async function choose(language: LanguageCode) {
    if (!lead || lead.preferred_language === language || saving) return;
    savingRef.current = true;
    setSaving(true);
    setNote(null);
    try {
      const updated = await updateLeadLanguage(id, language);
      setLead(updated);
      setNote(
        language === "te"
          ? "Saved. The next call greets and continues in Telugu."
          : "Saved. The next call greets and continues in English.",
      );
      onChanged();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not update the language");
    } finally {
      savingRef.current = false;
      setSaving(false);
    }
  }

  const title = lead?.first_name?.trim() || lead?.phone_number || "Lead";

  return (
    <div className="drawer-root">
      <button type="button" className="backdrop" aria-label="Close lead" onClick={onClose} />
      <aside className="drawer" role="dialog" aria-modal="true" aria-labelledby="lead-title">
        <div className="drawer-head">
          <div>
            <p className="eyebrow">Lead</p>
            <h2 id="lead-title">{title}</h2>
          </div>
          <button type="button" className="refresh" onClick={onClose}>Close</button>
        </div>
        {error && <p className="banner" role="alert">{error}</p>}
        {!lead && !error && <p className="muted">Loading lead…</p>}
        {lead && (
          <div className="drawer-body">
            <dl className="facts">
              <div>
                <dt>Phone</dt>
                <dd>{lead.phone_number}</dd>
              </div>
              <div>
                <dt>Status</dt>
                <dd><StatusPill status={lead.status} /></dd>
              </div>
              <div>
                <dt>Campaign</dt>
                <dd>{lead.campaign_id || "—"}</dd>
              </div>
              <div>
                <dt>Created</dt>
                <dd>{formatWhen(lead.created_at)}</dd>
              </div>
              <div>
                <dt>Follow-up</dt>
                <dd>
                  {lead.follow_up_start
                    ? `${formatDay(lead.follow_up_start)} – ${formatDay(lead.follow_up_end)}`
                    : "—"}
                </dd>
              </div>
            </dl>

            {(lead.ad_headline || lead.ad_body) && (
              <section>
                <h3>Ad</h3>
                {lead.ad_headline && <p className="message">{lead.ad_headline}</p>}
                {lead.ad_body && <p className="message">{lead.ad_body}</p>}
              </section>
            )}

            <section className="language-box">
              <div className="card-head">
                <h3>Call language</h3>
                <p>A WhatsApp reply stops the daily calls. NOT INTERESTED deletes the lead. TELUGU switches the spoken language.</p>
              </div>
              <div className="segmented" role="group" aria-label="Call language">
                <button
                  type="button"
                  aria-pressed={lead.preferred_language === "en"}
                  disabled={saving}
                  onClick={() => void choose("en")}
                >
                  English
                </button>
                <button
                  type="button"
                  lang="te"
                  aria-pressed={lead.preferred_language === "te"}
                  disabled={saving}
                  onClick={() => void choose("te")}
                >
                  తెలుగు
                </button>
              </div>
              {lead.language_locked && (
                <p className="hint">A call already in progress keeps the language it started with. This changes the next one.</p>
              )}
              {note && <p className="hint saved">{note}</p>}
            </section>

            <section>
              <h3>First message</h3>
              <p className="message" lang={lead.preferred_language === "te" ? "te" : "en"}>
                {lead.initial_message || "No message stored."}
              </p>
            </section>

            <section>
              <h3>Record a sale</h3>
              <p className="hint">
                The sale is filed under {clients.find((c) => c.id === lead.client_id)?.name ?? "this lead's client"} and
                this lead's ad. Choose where it came from.
              </p>
              <SaleForm
                clients={clients}
                leadId={lead.id}
                channel={lead.calls.some((c) => c.status === "completed") ? "calling_agent" : "ad"}
                onSaved={onChanged}
              />
            </section>

            <section>
              <h3>Calls</h3>
              {lead.calls.length === 0 ? (
                <p className="empty">No call yet.</p>
              ) : (
                <ul className="call-list">
                  {lead.calls.map((call) => (
                    <li key={call.id}>
                      <div>
                        <StatusPill status={call.status} />
                        <LanguagePill code={call.language} />
                      </div>
                      <p>
                        {labelLanguage(call.language)} · {call.attempts} {call.attempts === 1 ? "attempt" : "attempts"}
                        {" · "}
                        {formatWhen(call.started_at || call.created_at)}
                      </p>
                      {call.summary && <p>{call.summary}</p>}
                    </li>
                  ))}
                </ul>
              )}
            </section>
          </div>
        )}
      </aside>
    </div>
  );
}
