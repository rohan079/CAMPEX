import { useCallback, useEffect, useMemo, useState } from "react";
import {
  approveAction, cancelAction, executeAction, getAction, listActions, previewAction, rejectAction,
  type ActionStatus, type CampaignAction,
} from "./actionApi";
import { formatWhen } from "./format";
import { ClientRequired, ErrorBanner, errorText } from "./measurementUi";
import { actorName } from "./privacy";

/** Phase 3 Optimization Action Center. Every change is previewed from Meta, approved, executed once and verified. */

const STATUS: Record<ActionStatus, [string, string]> = {
  PROPOSED: ["Awaiting approval", "warn"], APPROVED: ["Approved", "live"], EXECUTING: ["Applying", "live"],
  SUCCEEDED: ["Applied", "good"], FAILED: ["Failed", "bad"], REJECTED: ["Rejected", "neutral"], EXPIRED: ["Expired", "neutral"],
  CANCELLED: ["Cancelled", "neutral"],
};
const ACTION_LABEL = { pause: "Pause", resume: "Resume", set_daily_budget: "Change daily budget" } as const;

function inr(minor: unknown): string {
  const n = Number(minor);
  if (minor === null || minor === undefined || Number.isNaN(n)) return "—";
  return new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 0 }).format(n / 100);
}

function ActionStatusPill({ action }: { action: CampaignAction }) {
  const [label, tone] = STATUS[action.status];
  return <span className={`pill tone-${tone}`}>{action.status === "SUCCEEDED" && action.verification === "mismatch" ? "Check in Meta" : label}</span>;
}

export function describeChange(a: CampaignAction): string {
  if (a.action_type === "set_daily_budget") return `Daily budget ${inr(a.before_state.daily_budget)} → ${inr(a.proposed_after.daily_budget)}`;
  return `${ACTION_LABEL[a.action_type]} ${a.level === "adset" ? "ad set" : a.level}`;
}

type Tab = "open" | "done" | "all";

export function OptimizationActionsPage({ clientId }: { clientId?: string }) {
  const [items, setItems] = useState<CampaignAction[] | null>(null);
  const [tab, setTab] = useState<Tab>("open");
  const [openId, setOpenId] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!clientId) return;
    try { setItems(await listActions(clientId)); } catch (err) { setError(errorText(err, "Could not load changes")); }
  }, [clientId]);
  useEffect(() => { setItems(null); void load(); }, [load]);

  const visible = useMemo(() => (items ?? []).filter((a) => tab === "all"
    || (tab === "open" ? ["PROPOSED", "APPROVED", "EXECUTING", "FAILED"].includes(a.status) : ["SUCCEEDED", "REJECTED", "EXPIRED", "CANCELLED"].includes(a.status))), [items, tab]);

  return (
    <ClientRequired clientId={clientId}>
      <div className="page-stack">
        <section className="page-intro">
          <div><p className="eyebrow">Optimization actions</p>
            <h2>Approved changes, verified in Meta.</h2>
            <p>Pause, resume or adjust daily budgets. Each change shows the current state from Meta, needs approval, and is checked again after it runs.</p></div>
          <button type="button" className="primary-button" onClick={() => setCreating(true)}>＋ Prepare change</button>
        </section>
        <ErrorBanner error={error} onDismiss={() => setError(null)} />
        <div className="segmented-tabs" role="tablist" aria-label="Change filters">
          {(["open", "done", "all"] as Tab[]).map((t) => <button key={t} type="button" role="tab" aria-selected={tab === t} onClick={() => setTab(t)}>{t === "open" ? "Open" : t === "done" ? "History" : "All"}</button>)}
        </div>
        {items === null ? <div className="loading-panel">Loading changes…</div> : visible.length === 0 ? (
          <div className="empty-state large"><span>✓</span><strong>{tab === "open" ? "No changes waiting" : "Nothing here yet"}</strong>
            <p>Prepare a change here or from a recommendation in Performance AI.</p></div>
        ) : (
          <div className="table-wrap"><table>
            <thead><tr><th>Object</th><th>Change</th><th>Status</th><th>Source</th><th>Updated</th></tr></thead>
            <tbody>{visible.map((a) => (
              <tr key={a.id} className="click-row" onClick={() => setOpenId(a.id)}>
                <td><button type="button" className="row-link" onClick={(e) => { e.stopPropagation(); setOpenId(a.id); }}><strong>{a.object_name ?? a.object_id}</strong></button>
                  <small>{a.level === "adset" ? "Ad set" : a.level} · {a.object_id}</small></td>
                <td>{describeChange(a)}</td>
                <td><ActionStatusPill action={a} /></td>
                <td>{a.source === "recommendation" ? "Recommendation" : "Manual"}</td>
                <td>{formatWhen(a.executed_at ?? a.approved_at ?? a.created_at)}</td>
              </tr>))}</tbody>
          </table></div>
        )}
      </div>
      {openId && <ActionDrawer id={openId} onClose={() => setOpenId(null)} onChanged={() => void load()} />}
      {creating && clientId && <PrepareDrawer clientId={clientId} onClose={() => setCreating(false)} onCreated={(id) => { setCreating(false); setOpenId(id); void load(); }} />}
    </ClientRequired>
  );
}

const FIELDS: { key: string; label: string; money?: boolean }[] = [
  { key: "status", label: "Status" }, { key: "effective_status", label: "Delivery" }, { key: "daily_budget", label: "Daily budget", money: true },
];

function StateCompare({ action }: { action: CampaignAction }) {
  const cell = (state: Record<string, unknown> | null, f: { key: string; money?: boolean }) =>
    !state || state[f.key] === undefined || state[f.key] === null ? "—" : f.money ? inr(state[f.key]) : String(state[f.key]);
  const changed = (key: string) => key in action.proposed_after;
  return (
    <div className="table-wrap"><table className="compare-table">
      <thead><tr><th>Field</th><th>Before (Meta)</th><th>Proposed</th><th>After (Meta)</th></tr></thead>
      <tbody>{FIELDS.map((f) => (
        <tr key={f.key} className={changed(f.key) ? "changed" : undefined}>
          <td>{f.label}</td><td>{cell(action.before_state, f)}</td>
          <td>{changed(f.key) ? <strong>{cell(action.proposed_after, f)}</strong> : <span className="muted">unchanged</span>}</td>
          <td>{cell(action.actual_after, f)}</td>
        </tr>))}</tbody>
    </table></div>
  );
}

function ActionDrawer({ id, onClose, onChanged }: { id: string; onClose: () => void; onChanged: () => void }) {
  const [action, setAction] = useState<CampaignAction | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [ack, setAck] = useState(false);
  useEffect(() => { getAction(id).then(setAction).catch((err) => setError(errorText(err, "Could not load change"))); }, [id]);

  async function run(name: string, fn: () => Promise<CampaignAction>) {
    setBusy(name); setError(null);
    try { setAction(await fn()); setAck(false); onChanged(); } catch (err) { setError(errorText(err, "That action could not be completed")); } finally { setBusy(null); }
  }
  const allowed = new Set(action?.allowed_actions ?? []);
  const resume = action?.action_type === "resume";
  return (
    <div className="drawer-root">
      <button type="button" className="backdrop" aria-label="Close change" onClick={onClose} />
      <aside className="drawer crm-drawer exec-drawer" role="dialog" aria-modal="true" aria-labelledby="action-title">
        <div className="drawer-head">
          <div><p className="eyebrow">{action ? describeChange(action) : "Change"}</p><h2 id="action-title">{action?.object_name ?? action?.object_id ?? "Loading…"}</h2>
            {action && <div className="exec-head-meta"><ActionStatusPill action={action} /><small>{action.level === "adset" ? "Ad set" : action.level} · {action.ad_account_id}</small></div>}</div>
          <button type="button" className="refresh" onClick={onClose}>Close</button>
        </div>
        {!action ? <p className="muted">{error ?? "Loading…"}</p> : (
          <div className="drawer-body">
            <ErrorBanner error={error} onDismiss={() => setError(null)} />
            {action.reason && <p className="notice note-info">{action.reason}</p>}
            {action.last_error && <p className="notice error">{action.last_error}</p>}
            {action.status === "SUCCEEDED" && action.verification === "verified" && <p className="notice success-notice">Applied and verified: Meta now reports the proposed values.</p>}
            {action.verification === "mismatch" && <p className="notice warn-notice">Meta accepted the request but does not show the expected values. Check the object in Ads Manager.</p>}
            {action.verification === "unverified" && <p className="notice warn-notice">Applied, but Meta could not be read back to confirm it.</p>}
            <StateCompare action={action} />
            {action.guardrails.length > 0 && (
              <ul className="guardrail-list">{action.guardrails.map((g) => <li key={g.code} className={`issue-${g.severity}`}>{g.message}</li>)}</ul>
            )}
            {["PROPOSED", "APPROVED"].includes(action.status) && <p className="targeting-note">Preview from {formatWhen(action.created_at)}; it expires {formatWhen(action.expires_at)}. If Meta changes before it runs, Campex stops instead of applying an outdated decision.</p>}
            <div className="exec-actions">
              {allowed.has("approve") && <button type="button" className="primary-button" disabled={busy !== null} onClick={() => void run("approve", () => approveAction(id))}>{busy === "approve" ? "Approving…" : "Approve"}</button>}
              {allowed.has("reject") && <button type="button" className="secondary-button" disabled={busy !== null} onClick={() => void run("reject", () => rejectAction(id))}>Reject</button>}
              {allowed.has("execute") && <>
                <label className="check-line"><input type="checkbox" checked={ack} onChange={(e) => setAck(e.target.checked)} />
                  {resume ? "I understand this starts delivery and spending now." : "Apply this change to the live Meta object."}</label>
                <button type="button" className="primary-button" disabled={!ack || busy !== null} onClick={() => void run("execute", () => executeAction(id))}>
                  {busy === "execute" ? "Applying…" : action.status === "FAILED" ? "Check Meta & retry" : "Apply in Meta"}</button>
              </>}
              {allowed.has("cancel") && <button type="button" className="text-button link-danger" disabled={busy !== null} onClick={() => void run("cancel", () => cancelAction(id))}>Cancel</button>}
            </div>
            <section><h3>History</h3>
              <ul className="timeline">{[...action.events].reverse().map((e, i) => (
                <li key={i} className={`step-${e.outcome}`}><span className="timeline-type">{e.event} · {e.outcome}</span>{e.detail && <p>{e.detail}</p>}
                  <small>{formatWhen(e.at)} · {actorName(e.actor)}</small></li>))}</ul></section>
          </div>
        )}
      </aside>
    </div>
  );
}

function PrepareDrawer({ clientId, onClose, onCreated }: { clientId: string; onClose: () => void; onCreated: (id: string) => void }) {
  const [actionType, setActionType] = useState<CampaignAction["action_type"]>("pause");
  const [level, setLevel] = useState<CampaignAction["level"]>("ad");
  const [objectId, setObjectId] = useState("");
  const [budget, setBudget] = useState("");
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  return (
    <div className="drawer-root">
      <button type="button" className="backdrop" aria-label="Close" onClick={onClose} />
      <aside className="drawer crm-drawer" role="dialog" aria-modal="true" aria-labelledby="prepare-title">
        <div className="drawer-head"><div><p className="eyebrow">Optimization action</p><h2 id="prepare-title">Prepare a change</h2></div>
          <button type="button" className="refresh" onClick={onClose}>Close</button></div>
        <form className="drawer-body config-form" onSubmit={async (e) => {
          e.preventDefault(); setBusy(true); setError(null);
          try {
            onCreated((await previewAction(clientId, { action_type: actionType, level: actionType === "set_daily_budget" && level === "ad" ? "adset" : level,
              object_id: objectId.trim(), daily_budget_minor: actionType === "set_daily_budget" ? Math.round(Number(budget) * 100) : undefined,
              reason: reason.trim() || undefined })).id);
          } catch (err) { setError(errorText(err, "Could not prepare the change")); } finally { setBusy(false); }
        }}>
          <ErrorBanner error={error} onDismiss={() => setError(null)} />
          <div className="form-grid-2">
            <label className="form-field"><span>Change</span>
              <select value={actionType} onChange={(e) => setActionType(e.target.value as CampaignAction["action_type"])}>
                <option value="pause">Pause</option><option value="resume">Resume</option><option value="set_daily_budget">Change daily budget</option></select></label>
            <label className="form-field"><span>Level</span>
              <select value={level} onChange={(e) => setLevel(e.target.value as CampaignAction["level"])}>
                {actionType !== "set_daily_budget" && <option value="ad">Ad</option>}<option value="adset">Ad set</option><option value="campaign">Campaign</option></select></label>
            <label className="form-field"><span>Meta ID</span><input required inputMode="numeric" pattern="[0-9]+" value={objectId} onChange={(e) => setObjectId(e.target.value)} placeholder="120200000000000" /></label>
            {actionType === "set_daily_budget" && <label className="form-field"><span>New daily budget (₹)</span><input required type="number" min={1} value={budget} onChange={(e) => setBudget(e.target.value)} /></label>}
          </div>
          <label className="form-field"><span>Why <small>optional</small></span><textarea rows={2} maxLength={2000} value={reason} onChange={(e) => setReason(e.target.value)} /></label>
          <p className="targeting-note">Campex reads the current state from Meta and checks the client's budget policy. Nothing changes until someone approves and applies it.</p>
          <div className="row-actions"><button type="button" className="secondary-button" onClick={onClose}>Cancel</button>
            <button type="submit" className="primary-button" disabled={busy || !objectId.trim()}>{busy ? "Reading Meta…" : "Preview change"}</button></div>
        </form>
      </aside>
    </div>
  );
}
