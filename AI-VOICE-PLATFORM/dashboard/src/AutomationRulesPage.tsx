import { useCallback, useEffect, useState } from "react";
import {
  createRule, getAutomationSettings, listRuleExecutions, listRules, runRule, setRuleEnabled, updateRule,
  type Level, type Rule, type RuleExecution, type RuleInput, type RuleType,
} from "./automationApi";
import { formatWhen } from "./format";
import { ClientRequired, ErrorBanner, errorText } from "./measurementUi";

/** Phase 3 automation rules: monitor → recommend → prepare for approval → (gated) pre-approved pause. */

export const RULE_TYPES: Record<RuleType, { label: string; threshold: string | null; unit: string; sample: string | null; help: string }> = {
  spend_over_limit: { label: "Spend above daily limit", threshold: "Daily spend limit (₹)", unit: "₹", sample: null, help: "Warns when yesterday's total spend passes the limit. Yesterday's figures can still be revised by Meta." },
  ctr_decline: { label: "CTR decline", threshold: "Decline that triggers (%)", unit: "%", sample: "Minimum impressions in each window", help: "Compares the last N settled days with the N before. Needs enough impressions in both." },
  cpl_above_threshold: { label: "Cost per lead too high", threshold: "Cost per Meta lead above (₹)", unit: "₹", sample: "Minimum leads in the window", help: "Uses Meta-reported leads. Waits for enough leads before deciding." },
  delivery_issue: { label: "Delivery problem", threshold: null, unit: "", sample: null, help: "Alerts when Meta reports a campaign as disapproved, with issues, or pending billing." },
};

export const LEVELS: Record<Level, { label: string; short: string; help: string }> = {
  MONITOR: { label: "1 · Monitor", short: "Monitor", help: "Record results only." },
  RECOMMEND: { label: "2 · Recommend", short: "Recommend", help: "Notify the team when it triggers." },
  APPROVAL_REQUIRED: { label: "3 · Prepare for approval", short: "Needs approval", help: "Prepare a pause in Optimization actions; a person approves and applies it." },
  PREAPPROVED: { label: "4 · Pre-approved pause", short: "Pre-approved", help: "Pause one campaign automatically, within a daily cap. Reversible; never raises budgets or activates." },
};

const OUTCOME: Record<RuleExecution["outcome"], [string, string]> = {
  triggered: ["Triggered", "warn"], not_triggered: ["OK", "good"], insufficient_data: ["Not enough data", "neutral"],
  cooldown: ["Cooldown", "neutral"], error: ["Error", "bad"],
};
const RESULT: Record<NonNullable<RuleExecution["result"]>, string> = {
  recorded: "Recorded", notified: "Team notified", action_prepared: "Pause prepared", action_applied: "Paused automatically", action_blocked: "No change prepared",
};

export function AutomationRulesPage({ clientId }: { clientId?: string }) {
  const [rules, setRules] = useState<Rule[] | null>(null);
  const [runs, setRuns] = useState<RuleExecution[]>([]);
  const [level4, setLevel4] = useState(false);
  const [editing, setEditing] = useState<Rule | "new" | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!clientId) return;
    try {
      const [r, e] = await Promise.all([listRules(clientId), listRuleExecutions(clientId)]);
      setRules(r); setRuns(e);
    } catch (err) { setError(errorText(err, "Could not load automation rules")); }
  }, [clientId]);
  useEffect(() => { setRules(null); void load(); }, [load]);
  useEffect(() => { getAutomationSettings().then((s) => setLevel4(s.preapproved_actions_enabled)).catch(() => setLevel4(false)); }, []);

  async function act(id: string, fn: () => Promise<unknown>) {
    setBusy(id); setError(null);
    try { await fn(); await load(); } catch (err) { setError(errorText(err, "That action could not be completed")); } finally { setBusy(null); }
  }

  return (
    <ClientRequired clientId={clientId}>
      <div className="page-stack">
        <section className="page-intro">
          <div><p className="eyebrow">Automation rules</p><h2>Watch campaigns without handing over the controls.</h2>
            <p>Rules check Meta data on a schedule. They can record, notify, or prepare a pause for approval. They never raise budgets or switch campaigns on.</p></div>
          <button type="button" className="primary-button" onClick={() => setEditing("new")}>＋ New rule</button>
        </section>
        <ErrorBanner error={error} onDismiss={() => setError(null)} />
        <p className={`notice ${level4 ? "warn-notice" : "note-info"}`}>{level4
          ? "Level 4 is enabled on this server: pre-approved rules can pause a single campaign automatically, within their daily cap."
          : "Level 4 (pre-approved pausing) is off on this server. Rules prepare changes for a person to approve."}</p>

        {rules === null ? <div className="loading-panel">Loading rules…</div> : rules.length === 0 ? (
          <div className="empty-state large"><span>⏱</span><strong>No rules yet</strong><p>Start with a monitor-only rule, then raise its level once you trust it.</p></div>
        ) : (
          <div className="rule-grid">{rules.map((rule) => (
            <article key={rule.id} className={`surface-card rule-card${rule.enabled ? "" : " disabled"}`} aria-label={rule.name}>
              <header><div><h3>{rule.name}</h3><small>{RULE_TYPES[rule.rule_type].label}{rule.campaign_id ? ` · campaign ${rule.campaign_id}` : " · all campaigns"}</small></div>
                <label className="switch"><input type="checkbox" role="switch" checked={rule.enabled} disabled={busy === rule.id}
                  aria-label={`${rule.enabled ? "Disable" : "Enable"} ${rule.name}`} onChange={(e) => void act(rule.id, () => setRuleEnabled(rule.id, e.target.checked))} /><span /></label></header>
              <div className="rule-meta">
                <span className={`pill level-${rule.execution_level.toLowerCase()}`}>{LEVELS[rule.execution_level].short}</span>
                {rule.threshold !== null && <span className="pill tone-neutral">{RULE_TYPES[rule.rule_type].unit === "%" ? `≥ ${rule.threshold}%` : `> ₹${Number(rule.threshold).toLocaleString("en-IN")}`}</span>}
                <span className="pill tone-neutral">{rule.observation_days}d · {rule.frequency}</span>
              </div>
              <small>Last run {rule.last_evaluated_at ? formatWhen(rule.last_evaluated_at) : "never"}{rule.last_outcome ? ` · ${OUTCOME[rule.last_outcome as RuleExecution["outcome"]]?.[0] ?? rule.last_outcome}` : ""}</small>
              <div className="row-actions">
                <button type="button" className="text-button" onClick={() => setEditing(rule)}>Edit</button>
                <button type="button" className="secondary-button" disabled={busy === rule.id} onClick={() => void act(rule.id, () => runRule(rule.id))}>{busy === rule.id ? "Running…" : "Run now"}</button>
              </div>
            </article>))}</div>
        )}

        <section><div className="section-title-row"><div><p className="step-label">HISTORY</p><h3>Rule runs</h3></div></div>
          {runs.length === 0 ? <p className="empty-state">No runs yet.</p> : (
            <div className="table-wrap"><table>
              <thead><tr><th>When</th><th>Rule</th><th>Campaign</th><th>Outcome</th><th>What happened</th></tr></thead>
              <tbody>{runs.slice(0, 50).map((e) => (
                <tr key={e.id}><td>{formatWhen(e.evaluated_at)}<small>{e.triggered_by === "scheduler" ? "Scheduled" : "Run now"}</small></td>
                  <td>{e.rule_name}</td><td>{e.object_name ?? e.object_id ?? "—"}</td>
                  <td><span className={`pill tone-${OUTCOME[e.outcome][1]}`}>{OUTCOME[e.outcome][0]}</span>{e.result && e.result !== "recorded" && <small>{RESULT[e.result]}</small>}</td>
                  <td className="clip"><span className="clamp">{e.message}</span></td></tr>))}</tbody>
            </table></div>
          )}
        </section>
      </div>
      {editing && clientId && <RuleDrawer clientId={clientId} rule={editing === "new" ? null : editing} level4={level4}
        onClose={() => setEditing(null)} onSaved={() => { setEditing(null); void load(); }} />}
    </ClientRequired>
  );
}

function RuleDrawer({ clientId, rule, level4, onClose, onSaved }: { clientId: string; rule: Rule | null; level4: boolean; onClose: () => void; onSaved: () => void }) {
  const [form, setForm] = useState<RuleInput>(() => rule ? { ...rule, campaign_id: rule.campaign_id ?? "" } : {
    name: "", rule_type: "cpl_above_threshold", threshold: 1500, observation_days: 7, min_sample: 5, frequency: "daily",
    severity: "warning", notify_team: true, execution_level: "MONITOR", cooldown_hours: 24, max_auto_actions_per_day: 1, campaign_id: "",
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const set = <K extends keyof RuleInput>(k: K, v: RuleInput[K]) => setForm((f) => ({ ...f, [k]: v }));
  const type = RULE_TYPES[form.rule_type];
  const canPrepare = form.rule_type === "ctr_decline" || form.rule_type === "cpl_above_threshold";
  const levels = (Object.keys(LEVELS) as Level[]).filter((l) => (l === "MONITOR" || l === "RECOMMEND" || canPrepare) && (l !== "PREAPPROVED" || level4));

  return (
    <div className="drawer-root">
      <button type="button" className="backdrop" aria-label="Close" onClick={onClose} />
      <aside className="drawer crm-drawer" role="dialog" aria-modal="true" aria-labelledby="rule-title">
        <div className="drawer-head"><div><p className="eyebrow">Automation rule</p><h2 id="rule-title">{rule ? "Edit rule" : "New rule"}</h2></div>
          <button type="button" className="refresh" onClick={onClose}>Close</button></div>
        <form className="drawer-body config-form" onSubmit={async (e) => {
          e.preventDefault(); setBusy(true); setError(null);
          const body: RuleInput = { ...form, campaign_id: form.campaign_id?.trim() || null, threshold: type.threshold ? Number(form.threshold) : null };
          try { if (rule) await updateRule(rule.id, body); else await createRule(clientId, body); onSaved(); }
          catch (err) { setError(errorText(err, "Could not save the rule")); } finally { setBusy(false); }
        }}>
          <ErrorBanner error={error} onDismiss={() => setError(null)} />
          <label className="form-field"><span>Name</span><input required maxLength={160} value={form.name} onChange={(e) => set("name", e.target.value)} /></label>
          <label className="form-field"><span>Condition</span>
            <select value={form.rule_type} onChange={(e) => { const t = e.target.value as RuleType; setForm((f) => ({ ...f, rule_type: t,
              execution_level: t === "ctr_decline" || t === "cpl_above_threshold" ? f.execution_level : (f.execution_level === "MONITOR" ? "MONITOR" : "RECOMMEND"),
              threshold: t === "ctr_decline" ? 30 : t === "spend_over_limit" ? 1000 : t === "cpl_above_threshold" ? 1500 : null,
              min_sample: t === "ctr_decline" ? 1000 : t === "cpl_above_threshold" ? 5 : 0 })); }}>
              {(Object.keys(RULE_TYPES) as RuleType[]).map((t) => <option key={t} value={t}>{RULE_TYPES[t].label}</option>)}</select></label>
          <p className="targeting-note">{type.help}</p>
          <div className="form-grid-2">
            {type.threshold && <label className="form-field"><span>{type.threshold}</span><input required type="number" min={type.unit === "%" ? 1 : 1} max={type.unit === "%" ? 100 : undefined} step="any"
              value={form.threshold ?? ""} onChange={(e) => set("threshold", e.target.value === "" ? null : Number(e.target.value))} /></label>}
            {type.sample && <label className="form-field"><span>{type.sample}</span><input type="number" min={0} value={form.min_sample ?? 0} onChange={(e) => set("min_sample", Number(e.target.value))} /></label>}
            {form.rule_type !== "delivery_issue" && form.rule_type !== "spend_over_limit" && <label className="form-field"><span>Observation window (days)</span>
              <input type="number" min={1} max={28} value={form.observation_days ?? 7} onChange={(e) => set("observation_days", Number(e.target.value))} /></label>}
            <label className="form-field"><span>Campaign ID <small>blank = all campaigns</small></span><input inputMode="numeric" pattern="[0-9]*" value={form.campaign_id ?? ""} onChange={(e) => set("campaign_id", e.target.value)} /></label>
            <label className="form-field"><span>Check</span><select value={form.frequency} onChange={(e) => set("frequency", e.target.value as "hourly" | "daily")}><option value="daily">Daily</option><option value="hourly">Hourly</option></select></label>
            <label className="form-field"><span>Severity</span><select value={form.severity} onChange={(e) => set("severity", e.target.value as "info" | "warning" | "critical")}><option value="info">Info</option><option value="warning">Warning</option><option value="critical">Critical</option></select></label>
            <label className="form-field"><span>Cooldown (hours)</span><input type="number" min={1} max={168} value={form.cooldown_hours ?? 24} onChange={(e) => set("cooldown_hours", Number(e.target.value))} /></label>
          </div>
          <fieldset className="level-picker"><legend>What should the rule do?</legend>
            {levels.map((l) => (
              <label key={l} className={`level-option${form.execution_level === l ? " selected" : ""}`}>
                <input type="radio" name="level" checked={form.execution_level === l} onChange={() => set("execution_level", l)} />
                <span><strong>{LEVELS[l].label}</strong><small>{LEVELS[l].help}</small></span></label>))}
            {!canPrepare && <p className="targeting-note">Only CTR and cost-per-lead rules can prepare a pause.</p>}
            {canPrepare && !level4 && <p className="targeting-note">Level 4 is disabled on this server.</p>}
          </fieldset>
          {form.execution_level === "PREAPPROVED" && <label className="form-field"><span>Automatic pauses per day (max)</span>
            <input type="number" min={1} max={5} value={form.max_auto_actions_per_day ?? 1} onChange={(e) => set("max_auto_actions_per_day", Number(e.target.value))} /></label>}
          <label className="check-line"><input type="checkbox" checked={form.notify_team ?? true} onChange={(e) => set("notify_team", e.target.checked)} />Notify owners and managers in the app</label>
          <div className="row-actions"><button type="button" className="secondary-button" onClick={onClose}>Cancel</button>
            <button type="submit" className="primary-button" disabled={busy}>{busy ? "Saving…" : "Save rule"}</button></div>
        </form>
      </aside>
    </div>
  );
}
