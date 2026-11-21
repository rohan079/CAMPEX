import { useCallback, useEffect, useState } from "react";
import { formatWhen } from "./format";
import { generateRecommendations, listRecommendations, updateRecommendation } from "./intelligenceApi";
import type { Evidence, Recommendation } from "./intelligenceTypes";
import { labelize } from "./measurementFormat";
import { ErrorBanner, SourceBadge, errorText } from "./measurementUi";
import { DataNotes } from "./PerformanceIntelligencePage";
import { actorName } from "./privacy";

function evidenceValue(e: Evidence) {
  if (e.value === null) return "unavailable";
  if (e.unit === "ratio") return `${(e.value * 100).toFixed(1)}%`;
  if (e.unit === "count") return Math.round(e.value).toLocaleString("en-IN");
  return `${e.unit} ${Number(e.value).toLocaleString("en-IN", { maximumFractionDigits: 2 })}`;
}

export function RecommendationsPanel({ clientId, from, to }: { clientId: string; from: string; to: string }) {
  const [items, setItems] = useState<Recommendation[]>([]);
  const [status, setStatus] = useState("pending");
  const [error, setError] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [notes, setNotes] = useState<import("./intelligenceTypes").DataNote[]>([]);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      setItems(await listRecommendations(clientId, status || undefined));
    } catch (err) {
      setError(errorText(err, "Could not load recommendations"));
    }
  }, [clientId, status]);
  useEffect(() => { void load(); }, [load]);

  async function generate() {
    setBusy(true);
    try {
      const r = await generateRecommendations(clientId, { from, to });
      setNotes(r.data_notes);
      setNote(`${r.created} new recommendation(s) from ${r.findings} finding(s)${r.skipped_duplicates ? `; ${r.skipped_duplicates} already open` : ""}.`
        + (r.ai_explained ? "" : " AI explanations were not added."));
      await load();
    } catch (err) {
      setError(errorText(err, "Could not generate recommendations"));
    } finally {
      setBusy(false);
    }
  }

  async function decide(rec: Recommendation, next: "accepted" | "rejected" | "reviewed") {
    try {
      const result = await updateRecommendation(rec.id, next);
      setNote(result.message);
      await load();
    } catch (err) {
      setError(errorText(err, "Could not update the recommendation"));
    }
  }

  return (
    <>
      <div className="filter-row">
        <label className="form-field"><span>Status</span>
          <select aria-label="Recommendation status" value={status} onChange={(e) => setStatus(e.target.value)}>
            {["pending", "reviewed", "accepted", "rejected", "expired", ""].map((s) => <option key={s} value={s}>{s ? labelize(s) : "All"}</option>)}
          </select></label>
        <div className="filter-actions"><button type="button" className="primary-button" disabled={busy} onClick={() => void generate()}>{busy ? "Analysing…" : "Generate recommendations"}</button></div>
      </div>
      <ErrorBanner error={error} onDismiss={() => setError(null)} />
      {note && <p className="notice success-notice" role="status">{note}</p>}
      <DataNotes notes={notes} />
      {items.length === 0 ? <p className="empty-state">No recommendations with this status.</p> : (
        <div className="rec-grid">{items.map((r) => <RecommendationCard key={r.id} rec={r} onDecide={decide} />)}</div>
      )}
    </>
  );
}

export function RecommendationCard({ rec, onDecide }: { rec: Recommendation; onDecide: (r: Recommendation, s: "accepted" | "rejected" | "reviewed") => void }) {
  const [showEvidence, setShowEvidence] = useState(false);
  const open = rec.status === "pending" || rec.status === "reviewed";
  return (
    <article className={`surface-card rec-card rec-${rec.category}`} aria-label={rec.title}>
      <div className="rec-head"><span className="pill">{labelize(rec.category)}</span>
        <span className={`pill conf-${rec.confidence}`}>Confidence: {rec.confidence}</span>
        <span className={`pill risk-${rec.risk_level}`}>Risk: {rec.risk_level}</span>
        <span className="pill">{labelize(rec.status)}</span></div>
      <h3>{rec.title}</h3>
      <p><strong>Now:</strong> {rec.current_performance}</p>
      <p><strong>Why:</strong> {rec.reasoning}</p>
      {rec.ai_explanation && <p className="ai-explanation"><strong>AI explanation:</strong> {rec.ai_explanation}</p>}
      <p><strong>Action:</strong> {rec.recommended_action}</p>
      <p className="estimate"><strong>Expected impact (estimate):</strong> {rec.expected_impact}</p>
      <small>Analysed {rec.analysis_from} – {rec.analysis_to} · created {formatWhen(rec.created_at)} · expires {formatWhen(rec.expires_at)}
        {rec.decided_by ? ` · ${rec.status} by ${actorName(rec.decided_by)}` : ""}</small>
      <div className="visit-actions">
        <button type="button" className="text-button" onClick={() => setShowEvidence((v) => !v)}>{showEvidence ? "Hide evidence" : "View evidence"}</button>
        {open && <>
          <button type="button" className="primary-button" onClick={() => onDecide(rec, "accepted")}>Accept</button>
          <button type="button" className="secondary-button" onClick={() => onDecide(rec, "rejected")}>Reject</button>
          {rec.status === "pending" && <button type="button" className="secondary-button" onClick={() => onDecide(rec, "reviewed")}>Mark reviewed</button>}
        </>}
      </div>
      {open && rec.category === "budget" && <p className="helper-text">Accepting records your decision only. Budgets are changed in Ads Manager, never automatically.</p>}
      {showEvidence && (
        <table className="evidence-table"><tbody>{rec.evidence.map((e, i) => (
          <tr key={i}><td>{e.label}</td><td className="num">{evidenceValue(e)}</td><td><SourceBadge source={e.source} /></td></tr>
        ))}</tbody></table>
      )}
    </article>
  );
}
