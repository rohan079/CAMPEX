import { useCallback, useEffect, useState } from "react";
import { formatWhen } from "./format";
import { detectAnomalies, explainAnomaly, listAnomalies, updateAnomaly } from "./intelligenceApi";
import type { Anomaly } from "./intelligenceTypes";
import { labelize } from "./measurementFormat";
import { ErrorBanner, errorText } from "./measurementUi";

const fmt = (metric: string, v: number | null) => {
  if (v === null) return "—";
  if (metric === "ctr" || metric === "conversion_rate") return `${(v * 100).toFixed(2)}%`;
  return Number(v).toLocaleString("en-IN", { maximumFractionDigits: 2 });
};

export function AnomalyPanel({ clientId }: { clientId: string }) {
  const [items, setItems] = useState<Anomaly[]>([]);
  const [status, setStatus] = useState("open");
  const [error, setError] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setItems(await listAnomalies(clientId, status || undefined));
    } catch (err) {
      setError(errorText(err, "Could not load anomalies"));
    }
  }, [clientId, status]);
  useEffect(() => { void load(); }, [load]);

  async function act(promise: Promise<unknown>, done: string) {
    try {
      await promise;
      setNote(done);
      await load();
    } catch (err) {
      setError(errorText(err, "That action failed"));
    }
  }

  return (
    <>
      <div className="filter-row">
        <label className="form-field"><span>Status</span>
          <select aria-label="Anomaly status" value={status} onChange={(e) => setStatus(e.target.value)}>
            {["open", "acknowledged", "resolved", "dismissed", ""].map((s) => <option key={s} value={s}>{s ? labelize(s) : "All"}</option>)}</select></label>
        <div className="filter-actions"><button type="button" className="secondary-button" onClick={() => void act(detectAnomalies(clientId), "Detection finished.")}>Run detection</button></div>
      </div>
      <ErrorBanner error={error} onDismiss={() => setError(null)} />
      {note && <p className="notice success-notice" role="status">{note}</p>}
      {items.length === 0 ? <p className="empty-state">No anomalies with this status.</p> : (
        <div className="rec-grid">{items.map((a) => (
          <article key={a.id} className={`surface-card anomaly-card sev-${a.severity.toLowerCase()}`} aria-label={`${a.severity} ${a.metric}`}>
            <div className="rec-head"><span className={`pill sev-pill-${a.severity.toLowerCase()}`}>{a.severity}</span>
              <span className="pill">{labelize(a.status)}</span>{a.occurrences > 1 && <span className="pill">Seen {a.occurrences}×</span>}</div>
            <h3>{labelize(a.metric)} · {a.campaign_name ?? "Account"}</h3>
            <p className="anomaly-change">{fmt(a.metric, a.previous_value)} → {fmt(a.metric, a.current_value)}
              {a.pct_change !== null && <strong> ({a.pct_change > 0 ? "+" : ""}{Math.round(a.pct_change * 100)}%)</strong>}</p>
            <p>{a.detection_reason}</p>
            <p><strong>Suggested action:</strong> {a.suggested_action}</p>
            {a.ai_explanation && <p className="ai-explanation"><strong>AI explanation:</strong> {a.ai_explanation}</p>}
            <small>Last detected {formatWhen(a.last_detected_at)}</small>
            {(a.status === "open" || a.status === "acknowledged") && (
              <div className="visit-actions">
                {!a.ai_explanation && <button type="button" className="text-button" onClick={() => void act(explainAnomaly(a.id), "Explanation added.")}>Explain with AI</button>}
                {a.status === "open" && <button type="button" className="secondary-button" onClick={() => void act(updateAnomaly(a.id, "acknowledged"), "Acknowledged.")}>Acknowledge</button>}
                <button type="button" className="primary-button" onClick={() => void act(updateAnomaly(a.id, "resolved"), "Resolved; similar alerts pause for the cooldown period.")}>Resolve</button>
                <button type="button" className="secondary-button" onClick={() => void act(updateAnomaly(a.id, "dismissed"), "Dismissed.")}>Dismiss</button>
              </div>
            )}
          </article>
        ))}</div>
      )}
    </>
  );
}
