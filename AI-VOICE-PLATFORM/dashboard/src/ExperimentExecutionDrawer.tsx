import { useCallback, useEffect, useState } from "react";
import { listCreatives } from "./creativeApi";
import type { CreativeSummary } from "./creativeTypes";
import {
  followUpExperiment, getExperimentExecution, launchExperiment, linkVariantCreative, type ExperimentExecution,
} from "./experimentExecutionApi";
import { ErrorBanner, errorText } from "./measurementUi";

/** Phase 3: run a Phase 2 experiment in Meta through approvals, and act on its result only when it is reliable. */

const PHASES = [
  { id: "setup", label: "Ready check" },
  { id: "awaiting_activation", label: "Approve launch" },
  { id: "measuring", label: "Measuring" },
  { id: "completed", label: "Result" },
];
const ACTION_STATUS: Record<string, string> = {
  PROPOSED: "awaiting approval", APPROVED: "approved, not applied", EXECUTING: "applying", SUCCEEDED: "applied", FAILED: "failed",
  REJECTED: "rejected", EXPIRED: "expired", CANCELLED: "cancelled",
};

export function ExperimentExecutionDrawer({ clientId, experimentId, name, onClose, onChanged }: {
  clientId: string; experimentId: string; name: string; onClose: () => void; onChanged: () => void;
}) {
  const [x, setX] = useState<ExperimentExecution | null>(null);
  const [creatives, setCreatives] = useState<CreativeSummary[]>([]);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try { setX(await getExperimentExecution(experimentId)); } catch (err) { setError(errorText(err, "Could not check the experiment")); }
  }, [experimentId]);
  useEffect(() => { void load(); listCreatives(clientId, "APPROVED").then(setCreatives).catch(() => setCreatives([])); }, [load, clientId]);

  async function run(name: string, fn: () => Promise<ExperimentExecution>) {
    setBusy(name); setError(null);
    try { setX(await fn()); onChanged(); } catch (err) { setError(errorText(err, "That action could not be completed")); } finally { setBusy(null); }
  }

  const phaseIndex = x ? Math.max(0, PHASES.findIndex((p) => p.id === x.phase)) : 0;
  const blocking = x?.checks.filter((c) => c.severity === "error") ?? [];
  const pendingActivation = x?.actions.filter((a) => a.purpose === "activate" && !["SUCCEEDED", "REJECTED", "CANCELLED", "EXPIRED"].includes(a.status)) ?? [];
  const interim = x?.progress.interim as Record<string, unknown> | null | undefined;

  return (
    <div className="drawer-root">
      <button type="button" className="backdrop" aria-label="Close experiment" onClick={onClose} />
      <aside className="drawer crm-drawer exec-drawer" role="dialog" aria-modal="true" aria-labelledby="xp-title">
        <div className="drawer-head"><div><p className="eyebrow">Experiment execution</p><h2 id="xp-title">{name}</h2></div>
          <button type="button" className="refresh" onClick={onClose}>Close</button></div>
        {!x ? <p className="muted">{error ?? "Checking variants in Meta…"}</p> : (
          <div className="drawer-body">
            <ol className="exec-stepper four" aria-label="Progress">
              {PHASES.map((p, i) => {
                const state = i < phaseIndex ? "done" : i === phaseIndex ? "current" : "todo";
                return <li key={p.id} className={`exec-step ${state}`} aria-current={state === "current" ? "step" : undefined}>
                  <span className="exec-dot">{state === "done" ? "✓" : i + 1}</span><span>{p.label}</span></li>;
              })}
            </ol>
            <ErrorBanner error={error} onDismiss={() => setError(null)} />
            {x.progress.guidance && <p className="notice note-info">{x.progress.guidance}</p>}

            <section><h3>Variants</h3>
              <div className="variant-grid">{x.variants.map((v) => (
                <div key={v.variant_id} className="variant-card">
                  <span className="timeline-type">{v.role}</span><strong>{v.name}</strong>
                  <small>{v.level === "adset" ? "Ad set" : "Ad"} {v.object_id}{v.object_name ? ` · ${v.object_name}` : ""}</small>
                  <span className={`pill tone-${v.status === "ACTIVE" ? "good" : v.status === "PAUSED" ? "warn" : "neutral"}`}>{v.status ? v.status.toLowerCase() : "unknown"}</span>
                  {x.experiment_status === "draft" ? (
                    <label className="form-field"><span>Approved creative</span>
                      <select value={v.creative_asset_id ?? ""} disabled={busy !== null}
                        onChange={(e) => void run("link", () => linkVariantCreative(experimentId, v.variant_id, e.target.value || null))}>
                        <option value="">None</option>{creatives.map((c) => <option key={c.id} value={c.id}>{c.name} · v{c.approved_version}</option>)}
                      </select></label>
                  ) : v.creative_name && <small>Creative: {v.creative_name} v{v.creative_version}</small>}
                </div>))}</div>
            </section>

            {x.checks.length > 0 && <ul className="guardrail-list" aria-label="Readiness checks">
              {x.checks.map((c, i) => <li key={i} className={`issue-${c.severity === "info" ? "warning" : c.severity}`}>{c.message}</li>)}</ul>}

            {x.experiment_status === "running" && (
              <section className="progress-box" aria-label="Progress">
                <div><span>Days</span><strong>{x.progress.days_elapsed ?? 0}{x.progress.days_planned ? ` / ${x.progress.days_planned}` : ""}</strong></div>
                <div><span>Minimum sample</span><strong>{x.progress.sample_reached ? "reached" : `not yet (${x.progress.min_sample_size.toLocaleString("en-IN")})`}</strong></div>
                {interim && typeof interim.explanation === "string" && <p className="muted">Interim: {interim.explanation}</p>}
              </section>
            )}

            {x.actions.length > 0 && <section><h3>Linked changes</h3>
              <ul className="timeline">{x.actions.map((a) => (
                <li key={a.action_id}><span className="timeline-type">{a.purpose === "activate" ? `Resume ${a.variant_role}` : `Pause losing ${a.variant_role}`}</span>
                  <p>{ACTION_STATUS[a.status] ?? a.status}{a.verification ? ` · ${a.verification}` : ""}</p></li>))}</ul>
              {pendingActivation.length > 0 && <p className="targeting-note">Approve and apply these in Optimization actions, then come back and press Launch again to start measuring.</p>}
            </section>}

            <div className="exec-actions">
              {x.allowed_actions.includes("launch") && (
                <button type="button" className="primary-button" disabled={busy !== null || blocking.length > 0}
                  title={blocking.length ? "Fix the blocking checks first" : undefined}
                  onClick={() => void run("launch", () => launchExperiment(experimentId))}>
                  {busy === "launch" ? "Checking Meta…" : x.phase === "awaiting_activation" ? "Check & start measuring" : "Launch experiment"}</button>
              )}
              {x.allowed_actions.includes("follow_up") && (
                <button type="button" className="primary-button" disabled={busy !== null} onClick={() => void run("follow_up", () => followUpExperiment(experimentId))}>
                  {busy === "follow_up" ? "Preparing…" : "Prepare pause of losing variant"}</button>
              )}
              <button type="button" className="secondary-button" disabled={busy !== null} onClick={() => void load()}>Refresh from Meta</button>
            </div>
            <p className="targeting-note">Campex never declares a winner from too little data and never replaces a live ad by itself. Launching and pausing are changes you approve in Optimization actions.</p>
          </div>
        )}
      </aside>
    </div>
  );
}
