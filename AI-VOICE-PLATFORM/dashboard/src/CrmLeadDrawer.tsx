import { useCallback, useEffect, useState } from "react";
import { STATUS_LABELS } from "./CrmPipelinePage";
import { formatWhen } from "./format";
import {
  addActivity, addFollowUp, assignLead, changeLeadStatus, createSiteVisit, getCrmLead, getLeadAttribution, labelize,
  listActivities, recordLeadConsent, saveQualification, updateFollowUp,
} from "./measurementApi";
import { errorText } from "./measurementUi";
import type { Activity, Agent, CrmLeadDetail, LeadAttribution, PipelineStatus, Project, Qualification } from "./measurementTypes";
import { Sensitive, actorName, safeName } from "./privacy";

type Tab = "overview" | "qualification" | "activity";

export function CrmLeadDrawer({ leadId, agents, projects, onClose, onChanged }: {
  leadId: string; agents: Agent[]; projects: Project[]; onClose: () => void; onChanged: () => void;
}) {
  const [lead, setLead] = useState<CrmLeadDetail | null>(null);
  const [attribution, setAttribution] = useState<LeadAttribution | null>(null);
  const [activities, setActivities] = useState<Activity[]>([]);
  const [tab, setTab] = useState<Tab>("overview");
  const [error, setError] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const [detail, attr, timeline] = await Promise.all([getCrmLead(leadId), getLeadAttribution(leadId), listActivities(leadId)]);
      setLead(detail);
      setAttribution(attr);
      setActivities(timeline);
      setError(null);
    } catch (err) {
      setError(errorText(err, "Could not load this lead"));
    }
  }, [leadId]);

  useEffect(() => { void load(); }, [load]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => { if (event.key === "Escape") onClose(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  async function run(action: () => Promise<unknown>, done: string) {
    try {
      await action();
      setNote(done);
      await load();
      onChanged();
    } catch (err) {
      setError(errorText(err, "That change could not be saved"));
    }
  }

  async function move(status: PipelineStatus) {
    let lostReason: string | undefined;
    if (status === "LOST") {
      lostReason = window.prompt("Why was this lead lost?") ?? undefined;
      if (!lostReason?.trim()) return;
    }
    await run(() => changeLeadStatus(leadId, status, { lost_reason: lostReason }), `Moved to ${STATUS_LABELS[status]}.`);
  }

  return (
    <div className="drawer-root">
      <button type="button" className="backdrop" aria-label="Close lead" onClick={onClose} />
      <aside className="drawer crm-drawer" role="dialog" aria-modal="true" aria-labelledby="crm-lead-title">
        <div className="drawer-head">
          <div><p className="eyebrow">{lead ? STATUS_LABELS[lead.pipeline_status] : "Lead"}</p>
            <h2 id="crm-lead-title">{lead ? safeName(lead.name, lead.phone_number) : "Lead"}</h2></div>
          <button type="button" className="refresh" onClick={onClose}>Close</button>
        </div>
        {error && <p className="banner" role="alert">{error}</p>}
        {note && <p className="hint saved" role="status">{note}</p>}
        {!lead ? <p className="muted">Loading lead…</p> : (
          <div className="drawer-body">
            <div className="segmented-tabs" role="tablist" aria-label="Lead sections">
              {(["overview", "qualification", "activity"] as Tab[]).map((value) => (
                <button key={value} type="button" role="tab" aria-selected={tab === value} onClick={() => setTab(value)}>{labelize(value)}</button>
              ))}
            </div>

            {tab === "overview" && (
              <>
                <dl className="facts">
                  <div><dt>Phone</dt><dd><Sensitive kind="phone" value={lead.phone_number} /></dd></div>
                  <div><dt>Email</dt><dd><Sensitive kind="email" value={lead.email} /></dd></div>
                  <div><dt>Source</dt><dd>{labelize(attribution?.attribution_source ?? lead.source)}</dd></div>
                  <div><dt>Response time</dt><dd>{lead.response_time_minutes === null ? "Not contacted yet" : `${lead.response_time_minutes} min`}</dd></div>
                  <div><dt>Review</dt><dd>{labelize(lead.review_status)}</dd></div>
                  <div><dt>Ad-data consent</dt><dd>{labelize(lead.tracking_consent)}</dd></div>
                </dl>

                <section>
                  <h3>Move stage</h3>
                  <div className="chips" role="group" aria-label="Next stage">
                    {lead.allowed_next_statuses.map((status) => (
                      <button key={status} type="button" className="chip" onClick={() => void move(status)}>{STATUS_LABELS[status]}</button>
                    ))}
                  </div>
                  {lead.lost_reason && <p className="hint">Lost: {lead.lost_reason}</p>}
                </section>

                <section>
                  <h3>Assigned agent</h3>
                  <select aria-label="Assign agent" value={lead.assigned_agent_id ?? ""}
                    onChange={(e) => e.target.value && void run(() => assignLead(leadId, e.target.value), "Assignment saved.")}>
                    <option value="">Unassigned</option>
                    {agents.filter((a) => a.active || a.id === lead.assigned_agent_id).map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
                  </select>
                </section>

                <section>
                  <h3>Attribution</h3>
                  {!attribution?.first_touch ? <p className="empty">No campaign evidence (unknown).</p> : (
                    <dl className="facts">
                      <div><dt>First touch</dt><dd>{touchLabel(attribution.first_touch)}</dd></div>
                      <div><dt>Last touch</dt><dd>{attribution.last_touch ? touchLabel(attribution.last_touch) : "—"}</dd></div>
                      <div><dt>Touches</dt><dd>{attribution.touch_count}</dd></div>
                      <div><dt>Resolved by</dt><dd>{labelize(attribution.first_touch.resolution_method)}</dd></div>
                    </dl>
                  )}
                </section>

                <FollowUps lead={lead} onRun={run} />
                <ScheduleVisit leadId={leadId} agents={agents} projects={projects} onRun={run} />

                {lead.duplicates.length > 0 && (
                  <section>
                    <h3>Possible duplicates</h3>
                    <ul className="plain-list">{lead.duplicates.map((d) => <li key={d.id}>{safeName(d.name, d.phone_number)} · {STATUS_LABELS[d.pipeline_status] ?? d.pipeline_status}</li>)}</ul>
                  </section>
                )}

                <section>
                  <h3>Ad-data consent</h3>
                  <p className="hint">Record only consent the lead actually gave (e.g. on a signed form). CRM events reach Meta only with consent.</p>
                  <div className="chips">
                    <button type="button" className="chip" onClick={() => void run(() => recordLeadConsent(leadId, true), "Consent recorded.")}>Record consent given</button>
                    <button type="button" className="chip" onClick={() => void run(() => recordLeadConsent(leadId, false), "Withdrawal recorded.")}>Record refusal</button>
                  </div>
                </section>
              </>
            )}

            {tab === "qualification" && (
              <QualificationForm lead={lead} projects={projects}
                onSave={(body) => run(() => saveQualification(leadId, body), "Qualification saved.")} />
            )}

            {tab === "activity" && <ActivityTimeline activities={activities} history={lead.status_history}
              onAdd={(type, body) => run(() => addActivity(leadId, type, body), "Activity added.")} />}
          </div>
        )}
      </aside>
    </div>
  );
}

function touchLabel(t: NonNullable<LeadAttribution["first_touch"]>): string {
  const campaign = t.campaign_name ?? t.campaign_id ?? (t.utm_campaign ? `${t.utm_campaign} (UTM)` : null);
  return `${campaign ?? "No campaign"}${t.ad_id ? ` · ad ${t.ad_name ?? t.ad_id}` : ""} · ${labelize(t.source)} · ${formatWhen(t.touched_at)}`;
}

type Run = (action: () => Promise<unknown>, done: string) => Promise<void>;

function FollowUps({ lead, onRun }: { lead: CrmLeadDetail; onRun: Run }) {
  const [due, setDue] = useState("");
  const [channel, setChannel] = useState("call");
  return (
    <section>
      <h3>Follow-ups</h3>
      {lead.follow_ups.length === 0 ? <p className="empty">None scheduled.</p> : (
        <ul className="plain-list">{lead.follow_ups.map((f) => (
          <li key={f.id} className={f.overdue ? "overdue" : undefined}>
            {formatWhen(f.due_at)} · {f.channel} · {labelize(f.status)}{f.overdue ? " (overdue)" : ""}
            {f.status === "pending" && <button type="button" className="text-button" onClick={() => void onRun(() => updateFollowUp(f.id, "done"), "Follow-up done.")}>Mark done</button>}
          </li>
        ))}</ul>
      )}
      <form className="inline-form" onSubmit={(e) => {
        e.preventDefault();
        if (!due) return;
        void onRun(() => addFollowUp(lead.id, { due_at: new Date(due).toISOString(), channel }), "Follow-up scheduled.").then(() => setDue(""));
      }}>
        <input type="datetime-local" aria-label="Follow-up time" required value={due} onChange={(e) => setDue(e.target.value)} />
        <select aria-label="Follow-up channel" value={channel} onChange={(e) => setChannel(e.target.value)}>
          {["call", "whatsapp", "email", "meeting", "other"].map((c) => <option key={c}>{c}</option>)}
        </select>
        <button type="submit" className="secondary-button">Schedule</button>
      </form>
    </section>
  );
}

export function ScheduleVisit({ leadId, agents, projects, onRun }: { leadId: string; agents: Agent[]; projects: Project[]; onRun: Run }) {
  const [when, setWhen] = useState("");
  const [agentId, setAgentId] = useState("");
  const [projectId, setProjectId] = useState("");
  return (
    <section>
      <h3>Schedule a site visit</h3>
      <form className="inline-form" aria-label="Schedule site visit" onSubmit={(e) => {
        e.preventDefault();
        if (!when) return;
        void onRun(() => createSiteVisit(leadId, {
          scheduled_at: new Date(when).toISOString(), agent_id: agentId || undefined, project_id: projectId || undefined,
        }), "Site visit scheduled.").then(() => setWhen(""));
      }}>
        <input type="datetime-local" aria-label="Visit time" required value={when} onChange={(e) => setWhen(e.target.value)} />
        <select aria-label="Visit agent" value={agentId} onChange={(e) => setAgentId(e.target.value)}>
          <option value="">Lead's agent</option>{agents.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}</select>
        <select aria-label="Visit project" value={projectId} onChange={(e) => setProjectId(e.target.value)}>
          <option value="">Lead's project</option>{projects.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}</select>
        <button type="submit" className="secondary-button">Schedule visit</button>
      </form>
    </section>
  );
}

function QualificationForm({ lead, projects, onSave }: {
  lead: CrmLeadDetail; projects: Project[]; onSave: (body: Partial<Qualification> & { project_id?: string }) => Promise<void>;
}) {
  const q = lead.qualification;
  const [form, setForm] = useState({
    budget_min: q?.budget_min?.toString() ?? "", budget_max: q?.budget_max?.toString() ?? "",
    preferred_location: q?.preferred_location ?? "", property_type: q?.property_type ?? "",
    purchase_timeline: q?.purchase_timeline ?? "", bedrooms: q?.bedrooms?.toString() ?? "",
    financing_preference: q?.financing_preference ?? "", purpose: q?.purpose ?? "",
    genuine_interest: q?.genuine_interest ?? false, qualification_reason: q?.qualification_reason ?? "",
    project_id: lead.project_id ?? "",
  });
  const set = (key: keyof typeof form, value: string | boolean) => setForm({ ...form, [key]: value });
  const numOrNull = (v: string) => (v.trim() === "" ? null : Number(v));
  return (
    <section>
      {q && (
        <div className="score-box" aria-label="Lead score">
          <strong>{q.qualification_score}</strong><span>/ 100 qualification score</span>
          <ul className="plain-list">{q.score_breakdown.map((f) => (
            <li key={f.factor} className={f.matched ? "factor-yes" : "factor-no"}>{f.points}/{f.weight} · {f.label} — {f.reason}</li>
          ))}</ul>
        </div>
      )}
      <form aria-label="Qualification" onSubmit={(e) => {
        e.preventDefault();
        void onSave({
          budget_min: numOrNull(form.budget_min), budget_max: numOrNull(form.budget_max),
          preferred_location: form.preferred_location || null, property_type: form.property_type || null,
          purchase_timeline: form.purchase_timeline || null, bedrooms: numOrNull(form.bedrooms),
          financing_preference: form.financing_preference || null, purpose: form.purpose || null,
          genuine_interest: form.genuine_interest, qualification_reason: form.qualification_reason || null,
          project_id: form.project_id || undefined,
        });
      }}>
        <div className="form-grid-2">
          <label className="form-field"><span>Budget from</span><input type="number" min="0" value={form.budget_min} onChange={(e) => set("budget_min", e.target.value)} /></label>
          <label className="form-field"><span>Budget to</span><input type="number" min="0" value={form.budget_max} onChange={(e) => set("budget_max", e.target.value)} /></label>
          <label className="form-field"><span>Preferred location</span><input value={form.preferred_location} onChange={(e) => set("preferred_location", e.target.value)} /></label>
          <label className="form-field"><span>Property type</span><input value={form.property_type} onChange={(e) => set("property_type", e.target.value)} placeholder="Villa, plot, 3BHK…" /></label>
          <label className="form-field"><span>Purchase timeline</span><select value={form.purchase_timeline} onChange={(e) => set("purchase_timeline", e.target.value)}>
            <option value="">—</option>{["IMMEDIATE", "WITHIN_3_MONTHS", "WITHIN_6_MONTHS", "WITHIN_12_MONTHS", "LATER", "UNKNOWN"].map((v) => <option key={v} value={v}>{labelize(v)}</option>)}</select></label>
          <label className="form-field"><span>Bedrooms</span><input type="number" min="0" max="20" value={form.bedrooms} onChange={(e) => set("bedrooms", e.target.value)} /></label>
          <label className="form-field"><span>Financing</span><select value={form.financing_preference} onChange={(e) => set("financing_preference", e.target.value)}>
            <option value="">—</option>{["SELF_FUNDED", "HOME_LOAN", "LOAN_PREAPPROVED", "UNDECIDED"].map((v) => <option key={v} value={v}>{labelize(v)}</option>)}</select></label>
          <label className="form-field"><span>Purpose</span><select value={form.purpose} onChange={(e) => set("purpose", e.target.value)}>
            <option value="">—</option>{["RESIDENTIAL", "INVESTMENT", "BOTH", "UNKNOWN"].map((v) => <option key={v} value={v}>{labelize(v)}</option>)}</select></label>
          <label className="form-field"><span>Project</span><select value={form.project_id} onChange={(e) => set("project_id", e.target.value)}>
            <option value="">—</option>{projects.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}</select></label>
          <label className="form-field"><span>Qualification reason</span><input value={form.qualification_reason} onChange={(e) => set("qualification_reason", e.target.value)} /></label>
        </div>
        <label className="check-line"><input type="checkbox" checked={form.genuine_interest} onChange={(e) => set("genuine_interest", e.target.checked)} /> Genuine purchase interest confirmed</label>
        <div className="campaign-actions"><button type="submit" className="primary-button">Save qualification</button></div>
      </form>
    </section>
  );
}

function ActivityTimeline({ activities, history, onAdd }: {
  activities: Activity[]; history: CrmLeadDetail["status_history"]; onAdd: (type: string, body: string) => Promise<void>;
}) {
  const [type, setType] = useState("note");
  const [body, setBody] = useState("");
  return (
    <section>
      <form className="inline-form" aria-label="Add activity" onSubmit={(e) => {
        e.preventDefault();
        if (!body.trim()) return;
        void onAdd(type, body.trim()).then(() => setBody(""));
      }}>
        <select aria-label="Activity type" value={type} onChange={(e) => setType(e.target.value)}>
          {["note", "call", "whatsapp", "email", "meeting"].map((t) => <option key={t}>{t}</option>)}</select>
        <input aria-label="Activity text" value={body} placeholder="What happened?" onChange={(e) => setBody(e.target.value)} />
        <button type="submit" className="secondary-button">Add</button>
      </form>
      <ol className="timeline">
        {activities.map((a) => (
          <li key={a.id}><span className="timeline-type">{labelize(a.activity_type)}</span><p>{a.body}</p>
            <small>{formatWhen(a.occurred_at)}{a.actor_email ? ` · ${actorName(a.actor_email)}` : ""}</small></li>
        ))}
      </ol>
      {activities.length === 0 && <p className="empty">No activity yet.</p>}
      <h3>Stage history</h3>
      <ul className="plain-list">{history.map((h, i) => (
        <li key={i}>{h.from_status ? `${STATUS_LABELS[h.from_status]} → ` : ""}{STATUS_LABELS[h.to_status]} · {formatWhen(h.changed_at)}{h.reason ? ` · ${h.reason}` : ""}</li>
      ))}</ul>
    </section>
  );
}
