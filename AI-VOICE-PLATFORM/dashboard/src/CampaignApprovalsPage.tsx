import { useCallback, useEffect, useMemo, useState } from "react";
import {
  approveExecution, cancelExecution, checkAd, retryAd, createExecution, getExecution, listExecutions, publishExecution,
  reconcileExecution, rejectExecution, retryExecution, reviseExecution, activateExecution, pauseExecution, submitExecution, updateExecution, validateExecution,
} from "./executionApi";
import { LocationPicker } from "./LocationPicker";
import type {
  CampaignConfig, ExecutionAction, ExecutionDetail, ExecutionStatus, ExecutionSummary, ValidationReport,
} from "./executionTypes";
import { formatWhen } from "./format";
import { ClientRequired, ErrorBanner, errorText } from "./measurementUi";
import { actorName } from "./privacy";
import { getCreative, listCreatives } from "./creativeApi";
import type { CreativeSummary } from "./creativeTypes";

/** Phase 3 Approval Center + execution history. Every action is re-checked by the server. */

export const STATUS_LABEL: Record<ExecutionStatus, string> = {
  DRAFT: "Draft",
  READY_FOR_REVIEW: "Ready for review",
  PENDING_APPROVAL: "Awaiting approval",
  APPROVED: "Approved",
  EXECUTING: "Publishing",
  PUBLISHED_PAUSED: "Published · paused",
  ACTIVE: "Active",
  FAILED: "Failed",
  CANCELLED: "Cancelled",
};

const STATUS_TONE: Record<ExecutionStatus, string> = {
  DRAFT: "neutral", READY_FOR_REVIEW: "neutral", PENDING_APPROVAL: "warn", APPROVED: "live", EXECUTING: "live",
  PUBLISHED_PAUSED: "good", ACTIVE: "good", FAILED: "bad", CANCELLED: "neutral",
};

export function ExecutionStatusPill({ status }: { status: ExecutionStatus }) {
  return <span className={`pill tone-${STATUS_TONE[status]}`}>{STATUS_LABEL[status]}</span>;
}

type Tab = "action" | "drafts" | "published" | "all";
const TABS: { id: Tab; label: string; statuses?: ExecutionStatus[] }[] = [
  { id: "action", label: "Needs action", statuses: ["READY_FOR_REVIEW", "PENDING_APPROVAL", "APPROVED", "EXECUTING", "FAILED"] },
  { id: "drafts", label: "Drafts", statuses: ["DRAFT"] },
  { id: "published", label: "Published", statuses: ["PUBLISHED_PAUSED", "ACTIVE"] },
  { id: "all", label: "All" },
];

export function money(minor: number | null | undefined, currency = "INR"): string {
  if (minor === null || minor === undefined) return "—";
  return new Intl.NumberFormat("en-IN", { style: "currency", currency, maximumFractionDigits: 0 }).format(minor / 100);
}

export function CampaignApprovalsPage({ clientId, focusId, onFocusHandled }: {
  clientId?: string;
  focusId?: string | null;
  onFocusHandled?: () => void;
}) {
  const [items, setItems] = useState<ExecutionSummary[] | null>(null);
  const [tab, setTab] = useState<Tab>("action");
  const [openId, setOpenId] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!clientId) return;
    try {
      setItems(await listExecutions(clientId));
    } catch (err) {
      setError(errorText(err, "Could not load campaigns"));
    }
  }, [clientId]);

  useEffect(() => { setItems(null); void load(); }, [load]);
  useEffect(() => {
    if (focusId) { setOpenId(focusId); onFocusHandled?.(); }
  }, [focusId, onFocusHandled]);

  const visible = useMemo(() => {
    const statuses = TABS.find((t) => t.id === tab)?.statuses;
    return (items ?? []).filter((item) => !statuses || statuses.includes(item.status));
  }, [items, tab]);
  const counts = useMemo(() => Object.fromEntries(TABS.map((t) => [t.id,
    (items ?? []).filter((item) => !t.statuses || t.statuses.includes(item.status)).length])), [items]);

  return (
    <ClientRequired clientId={clientId}>
      <div className="page-stack">
        <section className="page-intro">
          <div>
            <p className="eyebrow">Approval center</p>
            <h2>Nothing reaches Meta without an approval.</h2>
            <p>Drafts are validated, approved against their exact configuration, then published as paused campaigns.</p>
          </div>
          <button type="button" className="primary-button" onClick={() => setCreating(true)}>＋ New draft</button>
        </section>
        <ErrorBanner error={error} onDismiss={() => setError(null)} />
        <div className="segmented-tabs" role="tablist" aria-label="Campaign filters">
          {TABS.map((t) => (
            <button key={t.id} type="button" role="tab" aria-selected={tab === t.id} onClick={() => setTab(t.id)}>
              {t.label} <span className="tab-count">{counts[t.id] ?? 0}</span>
            </button>
          ))}
        </div>
        {items === null ? <div className="loading-panel">Loading campaigns…</div>
          : visible.length === 0 ? (
            <div className="empty-state large"><span>✓</span><strong>{tab === "action" ? "Nothing needs your attention" : "No campaigns here yet"}</strong>
              <p>Create a draft here or send one from Campaign studio.</p></div>
          ) : (
            <div className="table-wrap">
              <table>
                <thead><tr><th>Campaign</th><th>Status</th><th>Meta</th><th>Updated</th><th>Owner</th></tr></thead>
                <tbody>
                  {visible.map((item) => (
                    <tr key={item.id} className="click-row" onClick={() => setOpenId(item.id)}>
                      <td><button type="button" className="row-link" onClick={(ev) => { ev.stopPropagation(); setOpenId(item.id); }}>
                        <strong>{item.name}</strong></button><small>{item.objective} · {item.ad_account_id}</small></td>
                      <td><ExecutionStatusPill status={item.status} />
                        {item.status === "EXECUTING" && item.publish_outcome === "unknown" && <small className="warn-text">Needs reconciliation</small>}</td>
                      <td>{item.meta_effective_status ?? item.meta_status ?? "—"}</td>
                      <td>{formatWhen(item.updated_at)}</td>
                      <td>{actorName(item.created_by)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
      </div>
      {openId && <ExecutionDrawer id={openId} onClose={() => setOpenId(null)} onChanged={() => void load()} />}
      {creating && clientId && (
        <NewDraftDrawer clientId={clientId} onClose={() => setCreating(false)}
          onCreated={(id) => { setCreating(false); setOpenId(id); void load(); }} />
      )}
    </ClientRequired>
  );
}

const STAGES: { label: string; statuses: ExecutionStatus[] }[] = [
  { label: "Draft", statuses: ["DRAFT", "READY_FOR_REVIEW"] },
  { label: "Approval", statuses: ["PENDING_APPROVAL"] },
  { label: "Approved", statuses: ["APPROVED"] },
  { label: "Publish", statuses: ["EXECUTING", "FAILED"] },
  { label: "Paused in Meta", statuses: ["PUBLISHED_PAUSED", "ACTIVE"] },
];

function Stepper({ status }: { status: ExecutionStatus }) {
  const current = STAGES.findIndex((s) => s.statuses.includes(status));
  return (
    <ol className="exec-stepper" aria-label="Progress">
      {STAGES.map((stage, i) => {
        const state = status === "CANCELLED" ? "todo" : i < current ? "done" : i === current ? (status === "FAILED" ? "failed" : "current") : "todo";
        return <li key={stage.label} className={`exec-step ${state}`} aria-current={state === "current" ? "step" : undefined}>
          <span className="exec-dot">{state === "done" ? "✓" : state === "failed" ? "!" : i + 1}</span><span>{stage.label}</span></li>;
      })}
    </ol>
  );
}

function ExecutionDrawer({ id, onClose, onChanged }: { id: string; onClose: () => void; onChanged: () => void }) {
  const [detail, setDetail] = useState<ExecutionDetail | null>(null);
  const [busy, setBusy] = useState<ExecutionAction | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [rejectNote, setRejectNote] = useState("");
  const [rejecting, setRejecting] = useState(false);
  const [confirmPublish, setConfirmPublish] = useState(false);
  const [confirmActivate, setConfirmActivate] = useState(false);
  const [editing, setEditing] = useState(false);

  const refresh = useCallback(async () => {
    try { setDetail(await getExecution(id)); } catch (err) { setError(errorText(err, "Could not load campaign")); }
  }, [id]);
  useEffect(() => { void refresh(); }, [refresh]);

  async function run(action: ExecutionAction, fn: () => Promise<unknown>, done: string) {
    setBusy(action); setError(null); setNote(null);
    try {
      const result = await fn();
      if (result && typeof result === "object" && "execution" in result) setDetail(result as ExecutionDetail);
      else await refresh();
      setNote(done);
      onChanged();
    } catch (err) {
      setError(errorText(err, "That action could not be completed"));
      await refresh();
    } finally {
      setBusy(null);
    }
  }

  const allowed = new Set(detail?.allowed_actions ?? []);
  const ex = detail?.execution;
  const validation: ValidationReport | null = detail?.last_validation ?? null;

  return (
    <div className="drawer-root">
      <button type="button" className="backdrop" aria-label="Close campaign" onClick={onClose} />
      <aside className="drawer crm-drawer exec-drawer" role="dialog" aria-modal="true" aria-labelledby="exec-title">
        <div className="drawer-head">
          <div><p className="eyebrow">Campaign execution</p><h2 id="exec-title">{ex?.name ?? "Loading…"}</h2>
            {ex && <div className="exec-head-meta"><ExecutionStatusPill status={ex.status} /><small>Version {ex.config_version}</small></div>}</div>
          <button type="button" className="refresh" onClick={onClose}>Close</button>
        </div>
        {!detail || !ex ? <p className="muted">{error ?? "Loading campaign…"}</p> : (
          <div className="drawer-body">
            <Stepper status={ex.status} />
            <ErrorBanner error={error} onDismiss={() => setError(null)} />
            {note && <p className="hint saved" role="status">{note}</p>}
            {ex.last_error && <p className={`notice ${ex.status === "PUBLISHED_PAUSED" ? "warn-notice" : "error"}`}>{ex.last_error}</p>}
            {detail.ad_status !== "not_requested" && <AdStatusBox detail={detail} busy={busy}
              onRetry={() => run("ad_retry", () => retryAd(id), "Retrying ad creation.")}
              onCheck={() => run("ad_check", () => checkAd(id), "Checked with Meta.")} />}
            {ex.status === "PUBLISHED_PAUSED" && (
              <section className="go-live" aria-label="Go live">
                <p className="notice success-notice">In Meta as <strong>paused</strong>. Nothing is delivering yet.
                  {allowed.has("activate") ? " Activate it here when you're ready; no need to open Ads Manager."
                    : detail.ad_status === "created" || detail.ad_status === "failed" ? "" : " Activate becomes available once the ads are created."}</p>
                {allowed.has("activate") && !confirmActivate && (
                  <button type="button" className="primary-button" disabled={busy !== null} onClick={() => setConfirmActivate(true)}>Activate campaign…</button>)}
                {confirmActivate && (
                  <ActivateConfirm detail={detail} busy={busy === "activate"} onBack={() => setConfirmActivate(false)}
                    onConfirm={() => { setConfirmActivate(false); void run("activate", () => activateExecution(id),
                      "Activated. Ads start delivering after Meta reviews them, usually within a day."); }} />)}
              </section>
            )}
            {ex.status === "ACTIVE" && (
              <section className="go-live" aria-label="Live">
                <p className="notice success-notice"><strong>Live.</strong> Spending up to {money(detail.config.daily_budget_minor)} a day once Meta has approved the ads.</p>
                {allowed.has("pause") && <button type="button" className="secondary-button" disabled={busy !== null}
                  onClick={() => { if (window.confirm("Pause this campaign in Meta? Delivery and spending stop.")) void run("pause", () => pauseExecution(id), "Paused in Meta. Nothing is delivering."); }}>
                  {busy === "pause" ? "Pausing…" : "Pause campaign"}</button>}
              </section>
            )}

            {editing ? (
              <ConfigForm clientId={ex.client_id} initial={detail.config} submitLabel="Save changes" busy={busy === "edit"}
                onCancel={() => setEditing(false)}
                onSubmit={(config) => run("edit", async () => { const r = await updateExecution(id, config); setEditing(false); return r; },
                  "Saved as a new version. Validate and submit it again for approval.")} />
            ) : (
              <>
                <section>
                  <div className="section-title-row"><h3>Configuration</h3>
                    {allowed.has("edit") && <button type="button" className="text-button" onClick={() => setEditing(true)}>Edit</button>}</div>
                  <ConfigFacts config={detail.config} />
                </section>

                {validation && (
                  <section className={`validation-box ${validation.valid ? "ok" : "bad"}`} aria-label="Validation">
                    <strong>{validation.valid ? "Validation passed" : `${validation.errors.length} issue${validation.errors.length === 1 ? "" : "s"} to fix`}</strong>
                    <ul>
                      {validation.errors.map((i) => <li key={i.field + i.code} className="issue-error">{i.message}</li>)}
                      {validation.warnings.map((i) => <li key={i.field + i.code} className="issue-warning">{i.message}</li>)}
                    </ul>
                  </section>
                )}

                <ActionBar allowed={allowed} busy={busy} status={ex.status}
                  onValidate={() => run("validate", () => validateExecution(id), "Validation finished.")}
                  onSubmit={() => run("submit", () => submitExecution(id), "Sent for approval. Approvers have been notified.")}
                  onApprove={() => run("approve", () => approveExecution(id), "Approved. You can now publish it as a paused campaign.")}
                  onReject={() => setRejecting(true)}
                  onPublish={() => setConfirmPublish(true)}
                  onRetry={() => run("retry", () => retryExecution(id), "Checked Meta first, then retried.")}
                  onReconcile={() => run("reconcile", () => reconcileExecution(id), "Checked with Meta.")}
                  onRevise={() => run("revise", async () => { const r = await reviseExecution(id); setEditing(true); return r; },
                    "Back in draft. Fix what Meta rejected, then validate and submit it for approval again.")}
                  onCancel={() => { if (window.confirm("Cancel this campaign draft? It will not be published.")) void run("cancel", () => cancelExecution(id), "Cancelled."); }} />

                {rejecting && (
                  <form className="decision-form" onSubmit={(ev) => { ev.preventDefault(); void run("reject", () => rejectExecution(id, rejectNote.trim()), "Returned to draft with your note."); setRejecting(false); }}>
                    <label className="form-field"><span>What needs to change?</span>
                      <textarea required maxLength={1000} rows={3} value={rejectNote} onChange={(ev) => setRejectNote(ev.target.value)} /></label>
                    <div className="row-actions"><button type="button" className="secondary-button" onClick={() => setRejecting(false)}>Back</button>
                      <button type="submit" className="danger-button" disabled={!rejectNote.trim()}>Request changes</button></div>
                  </form>
                )}

                {confirmPublish && (
                  <PublishConfirm detail={detail} busy={busy === "publish"} onBack={() => setConfirmPublish(false)}
                    onConfirm={() => { setConfirmPublish(false); void run("publish", () => publishExecution(id), "Publish finished. See the result above."); }} />
                )}
              </>
            )}

            <section>
              <h3>Approvals</h3>
              {detail.approvals.length === 0 ? <p className="empty-state">No decisions yet.</p> : (
                <ul className="timeline">
                  {detail.approvals.map((a) => (
                    <li key={a.id}><span className="timeline-type">{a.decision === "approved" ? "Approved" : "Changes requested"} · v{a.config_version}</span>
                      {a.note && <p>{a.note}</p>}
                      <small>{formatWhen(a.decided_at)} · {actorName(a.decided_by)}
                        {a.decision === "approved" && !a.valid ? " · no longer valid (configuration changed)" : ""}</small></li>
                  ))}
                </ul>
              )}
            </section>

            <section>
              <h3>Activity</h3>
              <ul className="timeline">
                {[...detail.steps].reverse().map((s, i) => (
                  <li key={`${s.at}-${i}`} className={`step-${s.outcome}`}><span className="timeline-type">{s.step} · {s.outcome}</span>
                    {s.detail && <p>{s.detail}</p>}
                    <small>{formatWhen(s.at)} · {actorName(s.actor)}</small></li>
                ))}
              </ul>
            </section>

            <details className="tech-details">
              <summary>Technical details</summary>
              <dl className="facts">
                <div><dt>Publish tag</dt><dd>{detail.publish_tag}</dd></div>
                <div><dt>Attempts</dt><dd>{detail.publish_attempts}</dd></div>
                <div><dt>Meta campaign</dt><dd>{ex.meta_campaign_id ?? "—"}</dd></div>
                <div><dt>Meta ad set</dt><dd>{detail.meta_adset_id ?? "—"}</dd></div>
                <div><dt>Meta status</dt><dd>{ex.meta_effective_status ?? ex.meta_status ?? "—"}</dd></div>
                <div><dt>Checked</dt><dd>{formatWhen(detail.meta_checked_at)}</dd></div>
              </dl>
            </details>
          </div>
        )}
      </aside>
    </div>
  );
}

const AD_STATUS: Record<string, [string, string]> = {
  pending: ["Waiting to start", "live"], uploading: ["Uploading media to Meta", "live"], processing: ["Meta is processing the video", "live"],
  creating: ["Creating the ad", "live"], created: ["Ad created (paused)", "good"], failed: ["Ad not created", "bad"], skipped: ["Ad not created by Campex", "neutral"],
};

function AdStatusBox({ detail, busy, onRetry, onCheck }: { detail: ExecutionDetail; busy: ExecutionAction | null; onRetry: () => void; onCheck: () => void }) {
  const [label, tone] = AD_STATUS[detail.ad_status] ?? [detail.ad_status, "neutral"];
  return (
    <section className="ad-status-box" aria-label="Ad creation">
      <div><span className="step-label">AD</span> <span className={`pill tone-${tone}`}>{label}</span>
        {detail.meta_ad_id && <small> · ad {detail.meta_ad_id}</small>}</div>
      {(detail.ads?.length ?? 0) > 1 ? (
        <ol className="ad-status-list" aria-label="Ads in this ad set">
          {detail.ads!.map((a) => {
            const [l, t] = AD_STATUS[a.status] ?? [a.status, "neutral"];
            return (
              <li key={a.position}><span>Ad {a.position}</span> <span className={`pill tone-${t}`}>{l}</span>
                {a.meta_ad_id && <small> · ad {a.meta_ad_id}</small>}
                {a.error && <p className="notice error">{a.error}</p>}</li>
            );
          })}
        </ol>
      ) : detail.ad_error && <p className={detail.ad_status === "failed" ? "notice error" : "muted"}>{detail.ad_error}</p>}
      {detail.ad_status === "processing" && <p className="muted">Meta usually needs a few minutes for a short video. Campex checks every 30 seconds.</p>}
      <div className="row-actions">
        {detail.allowed_actions.includes("ad_check") && <button type="button" className="secondary-button" disabled={busy !== null} onClick={onCheck}>Check now</button>}
        {detail.allowed_actions.includes("ad_retry") && <button type="button" className="primary-button" disabled={busy !== null} onClick={onRetry}>Retry ad creation</button>}
      </div>
    </section>
  );
}

function ActionBar(props: {
  allowed: Set<ExecutionAction>; busy: ExecutionAction | null; status: ExecutionStatus;
  onValidate: () => void; onSubmit: () => void; onApprove: () => void; onReject: () => void; onPublish: () => void;
  onRetry: () => void; onReconcile: () => void; onRevise: () => void; onCancel: () => void;
}) {
  const { allowed, busy } = props;
  const b = (action: ExecutionAction, label: string, onClick: () => void, cls = "secondary-button") =>
    allowed.has(action) && <button key={action} type="button" className={cls} disabled={busy !== null} onClick={onClick}>
      {busy === action ? "Working…" : label}</button>;
  const primary = allowed.has("publish") ? "publish" : allowed.has("approve") ? "approve" : allowed.has("submit") ? "submit"
    : allowed.has("revise") ? "revise" : allowed.has("retry") ? "retry" : allowed.has("reconcile") && props.status === "EXECUTING" ? "reconcile" : "validate";
  const cls = (a: ExecutionAction) => (a === primary ? "primary-button" : "secondary-button");
  return (
    <div className="exec-actions">
      {b("validate", "Validate", props.onValidate, cls("validate"))}
      {b("submit", "Submit for approval", props.onSubmit, cls("submit"))}
      {b("approve", "Approve", props.onApprove, cls("approve"))}
      {b("reject", "Request changes", props.onReject)}
      {b("publish", "Publish as paused…", props.onPublish, cls("publish"))}
      {b("revise", "Edit and resubmit", props.onRevise, cls("revise"))}
      {b("retry", "Check Meta & retry", props.onRetry, cls("retry"))}
      {b("reconcile", props.status === "EXECUTING" ? "Reconcile with Meta" : "Refresh Meta status", props.onReconcile, cls("reconcile"))}
      {b("cancel", "Cancel", props.onCancel, "text-button link-danger")}
    </div>
  );
}

function ActivateConfirm({ detail, busy, onBack, onConfirm }: { detail: ExecutionDetail; busy: boolean; onBack: () => void; onConfirm: () => void }) {
  const [ack, setAck] = useState(false);
  const c = detail.config;
  const created = (detail.ads ?? []).filter((a) => a.status === "created").length || 1;
  const failed = (detail.ads ?? []).filter((a) => a.status === "failed").length;
  return (
    <section className="publish-confirm" role="alertdialog" aria-labelledby="activate-confirm-title">
      <h3 id="activate-confirm-title">Activate in Meta</h3>
      <ul className="plain-list">
        <li>Turns on {created === 1 ? "the ad" : `${created} ads`}, the ad set and the campaign in <strong>{c.ad_account_id}</strong>.</li>
        <li>Spends up to <strong>{money(c.daily_budget_minor)}</strong> a day once Meta approves the ads (review usually takes up to 24 hours).</li>
        {failed > 0 && <li>{failed === 1 ? "1 ad was" : `${failed} ads were`} not created and won't run. Retry them first if you want them included.</li>}
        <li>You can pause it here at any time.</li>
      </ul>
      <label className="check-line"><input type="checkbox" checked={ack} onChange={(e) => setAck(e.target.checked)} />
        I approve starting delivery and spending for this campaign.</label>
      <div className="row-actions">
        <button type="button" className="secondary-button" onClick={onBack}>Back</button>
        <button type="button" className="primary-button" disabled={!ack || busy} onClick={onConfirm}>{busy ? "Activating…" : "Activate now"}</button>
      </div>
    </section>
  );
}

function PublishConfirm({ detail, busy, onBack, onConfirm }: { detail: ExecutionDetail; busy: boolean; onBack: () => void; onConfirm: () => void }) {
  const [ack, setAck] = useState(false);
  const c = detail.config;
  return (
    <section className="publish-confirm" role="alertdialog" aria-labelledby="publish-confirm-title">
      <h3 id="publish-confirm-title">Publish to Meta</h3>
      <ul className="plain-list">
        <li>Creates a campaign and ad set in <strong>{c.ad_account_id}</strong>, both <strong>paused</strong>.</li>
        <li>Daily budget <strong>{money(c.daily_budget_minor)}</strong> once someone activates it.</li>
        <li>Campex then creates the ads from the approved creatives, also paused. You activate the campaign here when ready.</li>
        <li>If Meta does not answer, Campex checks before retrying. It never creates the campaign twice.</li>
      </ul>
      <label className="check-line"><input type="checkbox" checked={ack} onChange={(e) => setAck(e.target.checked)} />
        I approve creating this paused campaign in Meta.</label>
      <div className="row-actions">
        <button type="button" className="secondary-button" onClick={onBack}>Back</button>
        <button type="button" className="primary-button" disabled={!ack || busy} onClick={onConfirm}>{busy ? "Publishing…" : "Publish paused campaign"}</button>
      </div>
    </section>
  );
}

function ConfigFacts({ config }: { config: CampaignConfig }) {
  return (
    <dl className="facts">
      <div><dt>Ad account</dt><dd>{config.ad_account_id}</dd></div>
      <div><dt>Objective</dt><dd>{config.objective}</dd></div>
      <div><dt>Platforms</dt><dd>{config.platforms.join(", ") || "—"}</dd></div>
      <div><dt>Location</dt><dd>{config.location || "—"}</dd></div>
      <div><dt>Ages</dt><dd>{config.age_min}–{config.age_max}</dd></div>
      <div><dt>Daily budget</dt><dd>{money(config.daily_budget_minor)}</dd></div>
      <div className="span-2"><dt>Headline</dt><dd>{config.headline || "—"}</dd></div>
      <div className="span-2"><dt>Primary text</dt><dd className="pre-wrap">{config.primary_text || "—"}</dd></div>
      <div><dt>Call to action</dt><dd>{config.cta?.replaceAll("_", " ").toLowerCase() || "—"}</dd></div>
      <div><dt>Destination</dt><dd className="break">{config.destination_url || "—"}</dd></div>
    </dl>
  );
}

const CTA_OPTIONS = ["LEARN_MORE", "SIGN_UP", "CONTACT_US", "MESSAGE_PAGE", "BOOK_NOW", "GET_QUOTE", "APPLY_NOW"];

export function ConfigForm({ clientId, initial, submitLabel, busy, onSubmit, onCancel }: {
  clientId?: string; initial: CampaignConfig; submitLabel: string; busy: boolean;
  onSubmit: (config: CampaignConfig) => void; onCancel: () => void;
}) {
  const [c, setC] = useState<CampaignConfig>(initial);
  const [budget, setBudget] = useState(String(Math.round((initial.daily_budget_minor ?? 0) / 100) || ""));
  const set = <K extends keyof CampaignConfig>(key: K, value: CampaignConfig[K]) => setC((prev) => ({ ...prev, [key]: value }));
  const [creatives, setCreatives] = useState<CreativeSummary[]>([]);
  useEffect(() => {
    if (!clientId) return;
    listCreatives(clientId, "APPROVED").then(setCreatives).catch(() => setCreatives([]));
  }, [clientId]);
  const locked = Boolean(c.creative_asset_id);
  async function attach(assetId: string) {
    if (!assetId) { setC((prev) => ({ ...prev, creative_asset_id: null, creative_version: null })); return; }
    const d = await getCreative(assetId);
    const approved = d.versions.find((v) => v.version === d.asset.approved_version);
    if (!approved) return;
    setC((prev) => ({ ...prev, creative_asset_id: assetId, creative_version: approved.version,
      headline: approved.headline ?? "", primary_text: approved.primary_text ?? "", description: approved.description ?? prev.description,
      cta: approved.cta ?? prev.cta }));
  }
  const togglePlatform = (p: string) => set("platforms", c.platforms.includes(p) ? c.platforms.filter((x) => x !== p) : [...c.platforms, p]);
  return (
    <form className="config-form" onSubmit={(ev) => { ev.preventDefault(); onSubmit({ ...c, daily_budget_minor: Math.round(Number(budget) * 100) }); }}>
      <div className="form-grid-2">
        <label className="form-field"><span>Campaign name</span><input required maxLength={140} value={c.name} onChange={(e) => set("name", e.target.value)} /></label>
        <label className="form-field"><span>Ad account</span><input required placeholder="act_1234567890" value={c.ad_account_id} onChange={(e) => set("ad_account_id", e.target.value)} /></label>
        <label className="form-field"><span>Objective</span>
          <select value={c.objective} onChange={(e) => set("objective", e.target.value as CampaignConfig["objective"])}>
            <option value="leads">Leads</option><option value="traffic">Traffic</option><option value="sales">Sales</option><option value="awareness">Awareness</option>
          </select></label>
        <label className="form-field"><span>Daily budget (₹)</span><input required type="number" min={1} step={1} value={budget} onChange={(e) => setBudget(e.target.value)} /></label>
        <LocationPicker clientId={clientId} required value={{ location: c.location, key: c.location_key ?? null, type: c.location_type ?? null }}
          onChange={(next) => setC((prev) => ({ ...prev, location: next.location, location_key: next.key, location_type: next.type }))} />
        <div className="form-field"><span>Ages</span><div className="age-range-fields">
          <input aria-label="Minimum age" type="number" min={18} max={65} value={c.age_min} onChange={(e) => set("age_min", Number(e.target.value))} />
          <input aria-label="Maximum age" type="number" min={18} max={65} value={c.age_max} onChange={(e) => set("age_max", Number(e.target.value))} /></div></div>
      </div>
      <fieldset className="event-checks"><legend>Platforms</legend>
        {["facebook", "instagram", "whatsapp_ads"].map((p) => (
          <label key={p} className="check-line"><input type="checkbox" checked={c.platforms.includes(p)} onChange={() => togglePlatform(p)} />{p.replace("_", " ")}</label>
        ))}
      </fieldset>
      {clientId && (
        <label className="form-field"><span>Approved creative <small>locks the copy to the approved version</small></span>
          <select value={c.creative_asset_id ?? ""} onChange={(e) => void attach(e.target.value)}>
            <option value="">None (copy not creative-reviewed)</option>
            {creatives.map((cr) => <option key={cr.id} value={cr.id}>{cr.name} · v{cr.approved_version}</option>)}
          </select></label>
      )}
      <label className="form-field"><span>Headline <small>{c.headline.length}/80</small></span><input required maxLength={80} readOnly={locked} value={c.headline} onChange={(e) => set("headline", e.target.value)} /></label>
      <label className="form-field"><span>Primary text <small>{c.primary_text.length}/1500</small></span>
        <textarea required maxLength={1500} rows={4} readOnly={locked} value={c.primary_text} onChange={(e) => set("primary_text", e.target.value)} /></label>
      <div className="form-grid-2">
        <label className="form-field"><span>Call to action</span>
          <select value={c.cta} onChange={(e) => set("cta", e.target.value)}>
            {CTA_OPTIONS.map((o) => <option key={o} value={o}>{o.replaceAll("_", " ").toLowerCase()}</option>)}
          </select></label>
        <label className="form-field"><span>Destination URL <small>required for traffic and sales</small></span>
          <input type="url" placeholder="https://" value={c.destination_url ?? ""} onChange={(e) => set("destination_url", e.target.value)} /></label>
      </div>
      <p className="targeting-note">Use only verified project details: no invented prices, approvals, possession dates or guaranteed returns.</p>
      <div className="row-actions">
        <button type="button" className="secondary-button" onClick={onCancel}>Cancel</button>
        <button type="submit" className="primary-button" disabled={busy}>{busy ? "Saving…" : submitLabel}</button>
      </div>
    </form>
  );
}

const BLANK: CampaignConfig = {
  ad_account_id: "", name: "", objective: "leads", platforms: ["facebook", "instagram"], location: "", age_min: 25,
  age_max: 55, daily_budget_minor: 50_000, headline: "", primary_text: "", cta: "LEARN_MORE", destination_url: "",
};

function NewDraftDrawer({ clientId, onClose, onCreated }: { clientId: string; onClose: () => void; onCreated: (id: string) => void }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [key] = useState(() => crypto.randomUUID());
  return (
    <div className="drawer-root">
      <button type="button" className="backdrop" aria-label="Close" onClick={onClose} />
      <aside className="drawer crm-drawer" role="dialog" aria-modal="true" aria-labelledby="new-draft-title">
        <div className="drawer-head"><div><p className="eyebrow">New campaign draft</p><h2 id="new-draft-title">Draft for approval</h2></div>
          <button type="button" className="refresh" onClick={onClose}>Close</button></div>
        <div className="drawer-body">
          <ErrorBanner error={error} onDismiss={() => setError(null)} />
          <ConfigForm clientId={clientId} initial={BLANK} submitLabel="Save draft" busy={busy} onCancel={onClose} onSubmit={async (config) => {
            setBusy(true); setError(null);
            try { onCreated((await createExecution(clientId, config, key)).execution.id); }
            catch (err) { setError(errorText(err, "Could not save the draft")); }
            finally { setBusy(false); }
          }} />
        </div>
      </aside>
    </div>
  );
}
