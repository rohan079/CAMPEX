import { useCallback, useEffect, useMemo, useState } from "react";
import { formatWhen, num } from "./format";
import { labelize, listAgents, listSiteVisits, rescheduleSiteVisit, updateSiteVisitStatus } from "./measurementApi";
import { ClientRequired, ErrorBanner, KpiCard, errorText } from "./measurementUi";
import type { Agent, SiteVisit } from "./measurementTypes";

const OUTCOMES = ["interested", "negotiation", "booked", "follow_up_needed", "not_interested"];

function startOfWeek(date: Date): Date {
  const d = new Date(date.getFullYear(), date.getMonth(), date.getDate());
  d.setDate(d.getDate() - ((d.getDay() + 6) % 7));
  return d;
}

const isoDay = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;

export function SiteVisitsPage({ clientId }: { clientId?: string }) {
  const [weekStart, setWeekStart] = useState(() => startOfWeek(new Date()));
  const [visits, setVisits] = useState<SiteVisit[]>([]);
  const [agents, setAgents] = useState<Agent[]>([]);
  const [agentId, setAgentId] = useState("");
  const [selected, setSelected] = useState<SiteVisit | null>(null);
  const [error, setError] = useState<string | null>(null);

  const days = useMemo(() => Array.from({ length: 7 }, (_, i) => {
    const d = new Date(weekStart);
    d.setDate(d.getDate() + i);
    return d;
  }), [weekStart]);

  const load = useCallback(async () => {
    if (!clientId) return;
    try {
      const page = await listSiteVisits(clientId, { from: isoDay(days[0]), to: isoDay(days[6]), agent_id: agentId || undefined });
      setVisits(page.visits);
      setError(null);
    } catch (err) {
      setError(errorText(err, "Could not load site visits"));
    }
  }, [clientId, days, agentId]);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => { if (clientId) void listAgents(clientId).then(setAgents).catch(() => setAgents([])); }, [clientId]);

  const active = visits.filter((v) => v.status !== "RESCHEDULED");
  const completed = visits.filter((v) => v.status === "COMPLETED").length;
  const held = visits.filter((v) => ["COMPLETED", "NO_SHOW"].includes(v.status)).length;
  const booked = visits.filter((v) => v.outcome === "booked").length;

  return (
    <ClientRequired clientId={clientId}>
      <div className="page-stack">
        <section className="page-intro">
          <div><p className="eyebrow">Site visits</p><h2>Plan, confirm and close visits.</h2>
            <p>Schedule visits from a lead in the CRM pipeline. Completing a visit moves the lead forward automatically.</p></div>
          <div className="filter-inline">
            <button type="button" className="secondary-button" onClick={() => setWeekStart(new Date(weekStart.getTime() - 7 * 86400000))}>← Previous week</button>
            <button type="button" className="secondary-button" onClick={() => setWeekStart(startOfWeek(new Date()))}>This week</button>
            <button type="button" className="secondary-button" onClick={() => setWeekStart(new Date(weekStart.getTime() + 7 * 86400000))}>Next week →</button>
            <label className="form-field compact"><span>Agent</span><select aria-label="Agent" value={agentId} onChange={(e) => setAgentId(e.target.value)}>
              <option value="">All agents</option>{agents.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}</select></label>
          </div>
        </section>
        <ErrorBanner error={error} onDismiss={() => setError(null)} />
        <section className="metric-grid" aria-label="Visit conversion">
          <KpiCard label="Visits this week" value={num(active.length)} source="crm_verified" />
          <KpiCard label="Completed" value={num(completed)} source="crm_verified" />
          <KpiCard label="Show-up rate" value={held ? `${Math.round((completed / held) * 100)}%` : "—"} source="derived" note="Completed ÷ (completed + no-show)" />
          <KpiCard label="Booked at visit" value={num(booked)} source="crm_verified" note="Visits with outcome “booked”" />
        </section>

        <div className="visit-calendar" aria-label="Week calendar">
          {days.map((day) => {
            const dayVisits = active.filter((v) => isoDay(new Date(v.scheduled_at)) === isoDay(day));
            return (
              <section key={isoDay(day)} className="calendar-day" aria-label={day.toDateString()}>
                <header>{new Intl.DateTimeFormat("en-IN", { weekday: "short", day: "2-digit", month: "short" }).format(day)}</header>
                {dayVisits.map((v) => (
                  <button key={v.id} type="button" className={`visit-chip visit-${v.status.toLowerCase()}`} onClick={() => setSelected(v)}>
                    <strong>{new Intl.DateTimeFormat("en-IN", { hour: "2-digit", minute: "2-digit" }).format(new Date(v.scheduled_at))}</strong>
                    <span>{v.lead_name ?? v.lead_phone}</span><small>{labelize(v.status)}</small>
                  </button>
                ))}
              </section>
            );
          })}
        </div>

        <section className="surface-card">
          <div className="section-title-row"><div><p className="step-label">VISIT LIST</p><h3>This week</h3></div></div>
          {visits.length === 0 ? <p className="empty-state">No visits this week.</p> : (
            <div className="table-wrap"><table>
              <thead><tr><th>When</th><th>Customer</th><th>Project</th><th>Agent</th><th>Status</th><th>Outcome</th></tr></thead>
              <tbody>{visits.map((v) => (
                <tr key={v.id} className="click-row" onClick={() => setSelected(v)}>
                  <td>{formatWhen(v.scheduled_at)}</td><td><strong>{v.lead_name ?? "—"}</strong><small>{v.lead_phone}</small></td>
                  <td>{v.project_name ?? "—"}</td><td>{v.agent_name ?? "—"}</td>
                  <td>{labelize(v.status)}{v.customer_confirmed ? " ✓" : ""}</td><td>{labelize(v.outcome)}</td>
                </tr>
              ))}</tbody>
            </table></div>
          )}
        </section>

        {selected && <VisitPanel visit={selected} onClose={() => setSelected(null)}
          onChanged={(next) => { setSelected(next); void load(); }} onError={setError} />}
      </div>
    </ClientRequired>
  );
}

export function VisitPanel({ visit, onClose, onChanged, onError }: {
  visit: SiteVisit; onClose: () => void; onChanged: (v: SiteVisit | null) => void; onError: (e: string) => void;
}) {
  const [outcome, setOutcome] = useState("interested");
  const [notes, setNotes] = useState(visit.follow_up_notes ?? "");
  const [reason, setReason] = useState("");
  const [when, setWhen] = useState("");
  const open = visit.status === "SCHEDULED" || visit.status === "CONFIRMED";

  async function act(action: () => Promise<SiteVisit>) {
    try {
      onChanged(await action());
    } catch (err) {
      onError(errorText(err, "Could not update the visit"));
    }
  }

  return (
    <section className="surface-card" aria-label="Visit detail">
      <div className="section-title-row"><div><p className="step-label">SITE VISIT</p>
        <h3>{visit.lead_name ?? visit.lead_phone} · {formatWhen(visit.scheduled_at)}</h3></div>
        <button type="button" className="secondary-button" onClick={onClose}>Close</button></div>
      <dl className="facts">
        <div><dt>Status</dt><dd>{labelize(visit.status)}</dd></div>
        <div><dt>Customer confirmed</dt><dd>{visit.customer_confirmed ? "Yes" : "No"}</dd></div>
        <div><dt>Project</dt><dd>{visit.project_name ?? "—"}</dd></div>
        <div><dt>Agent</dt><dd>{visit.agent_name ?? "—"}</dd></div>
        {visit.cancellation_reason && <div><dt>Reason</dt><dd>{visit.cancellation_reason}</dd></div>}
      </dl>
      {open ? (
        <div className="visit-actions">
          {visit.status === "SCHEDULED" && (
            <button type="button" className="secondary-button" onClick={() => void act(() => updateSiteVisitStatus(visit.id, { status: "CONFIRMED" }))}>Customer confirmed</button>
          )}
          <form className="inline-form" aria-label="Visit outcome" onSubmit={(e) => {
            e.preventDefault();
            void act(() => updateSiteVisitStatus(visit.id, { status: "COMPLETED", outcome, follow_up_notes: notes || undefined }));
          }}>
            <select aria-label="Outcome" value={outcome} onChange={(e) => setOutcome(e.target.value)}>
              {OUTCOMES.map((o) => <option key={o} value={o}>{labelize(o)}</option>)}</select>
            <input aria-label="Visit notes" value={notes} placeholder="Follow-up notes" onChange={(e) => setNotes(e.target.value)} />
            <button type="submit" className="primary-button">Mark completed</button>
          </form>
          <button type="button" className="secondary-button" onClick={() => void act(() => updateSiteVisitStatus(visit.id, { status: "NO_SHOW" }))}>No-show</button>
          <form className="inline-form" aria-label="Reschedule" onSubmit={(e) => {
            e.preventDefault();
            if (when) void act(() => rescheduleSiteVisit(visit.id, new Date(when).toISOString(), reason || undefined));
          }}>
            <input type="datetime-local" aria-label="New time" required value={when} onChange={(e) => setWhen(e.target.value)} />
            <button type="submit" className="secondary-button">Reschedule</button>
          </form>
          <form className="inline-form" aria-label="Cancel visit" onSubmit={(e) => {
            e.preventDefault();
            if (reason.trim()) void act(() => updateSiteVisitStatus(visit.id, { status: "CANCELLED", cancellation_reason: reason.trim() }));
          }}>
            <input aria-label="Cancellation reason" value={reason} placeholder="Reason (needed to cancel)" onChange={(e) => setReason(e.target.value)} />
            <button type="submit" className="link-danger">Cancel visit</button>
          </form>
        </div>
      ) : (
        <p className="helper-text">{labelize(visit.outcome) !== "—" ? `Outcome: ${labelize(visit.outcome)}. ` : ""}{visit.follow_up_notes ?? ""}</p>
      )}
    </section>
  );
}
