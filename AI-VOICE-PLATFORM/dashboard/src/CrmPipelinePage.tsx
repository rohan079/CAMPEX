import { useCallback, useEffect, useState } from "react";
import { CrmLeadDrawer } from "./CrmLeadDrawer";
import { formatWhen, money, num } from "./format";
import {
  createAgent, createManualLead, createProject, getPerformance, getPipeline, labelize, listAgents, listProjects, rangeForDays,
} from "./measurementApi";
import { ClientRequired, ErrorBanner, errorText } from "./measurementUi";
import type { Agent, AgentPerformance, LeadCard, Pipeline, Project } from "./measurementTypes";

export const STATUS_LABELS: Record<string, string> = {
  NEW: "New", CONTACTED: "Contacted", QUALIFIED: "Qualified", SITE_VISIT_SCHEDULED: "Site visit scheduled",
  SITE_VISIT_COMPLETED: "Site visit completed", NEGOTIATION: "Negotiation", WON: "Won", LOST: "Lost",
};

export function CrmPipelinePage({ clientId, days }: { clientId?: string; days: number }) {
  const [pipeline, setPipeline] = useState<Pipeline | null>(null);
  const [agents, setAgents] = useState<Agent[]>([]);
  const [projects, setProjects] = useState<Project[]>([]);
  const [filters, setFilters] = useState({ q: "", agent_id: "", source: "" });
  const [openLead, setOpenLead] = useState<string | null>(null);
  const [showNew, setShowNew] = useState(false);
  const [view, setView] = useState<"board" | "team">("board");
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!clientId) return;
    try {
      const [nextPipeline, nextAgents, nextProjects] = await Promise.all([
        getPipeline(clientId, filters), listAgents(clientId), listProjects(clientId),
      ]);
      setPipeline(nextPipeline);
      setAgents(nextAgents);
      setProjects(nextProjects);
      setError(null);
    } catch (err) {
      setError(errorText(err, "Could not load the pipeline"));
    }
  }, [clientId, filters]);

  useEffect(() => { void load(); }, [load]);

  return (
    <ClientRequired clientId={clientId}>
      <div className="page-stack">
        <section className="page-intro">
          <div><p className="eyebrow">CRM pipeline</p><h2>Every lead, one stage at a time.</h2>
            <p>The pipeline stage is separate from lead review (accept/reject) and from the calling status.</p></div>
          <div className="filter-inline">
            <div className="segmented-tabs" role="tablist" aria-label="CRM views">
              <button type="button" role="tab" aria-selected={view === "board"} onClick={() => setView("board")}>Board</button>
              <button type="button" role="tab" aria-selected={view === "team"} onClick={() => setView("team")}>Team</button>
            </div>
            <button type="button" className="primary-button" onClick={() => setShowNew((v) => !v)}>＋ New lead</button>
          </div>
        </section>
        <ErrorBanner error={error} onDismiss={() => setError(null)} />
        {showNew && clientId && (
          <NewLeadForm clientId={clientId} agents={agents} projects={projects}
            onCreated={(id) => { setShowNew(false); setOpenLead(id); void load(); }} onError={setError} />
        )}
        {view === "board" ? (
          <>
            <div className="filter-row" role="group" aria-label="Pipeline filters">
              <label className="form-field"><span>Search</span>
                <input type="search" aria-label="Search leads" value={filters.q} placeholder="Name, phone or email"
                  onChange={(e) => setFilters({ ...filters, q: e.target.value })} /></label>
              <label className="form-field"><span>Agent</span>
                <select aria-label="Agent filter" value={filters.agent_id} onChange={(e) => setFilters({ ...filters, agent_id: e.target.value })}>
                  <option value="">All agents</option>{agents.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
                </select></label>
              <label className="form-field"><span>Lead source</span>
                <select aria-label="Source filter" value={filters.source} onChange={(e) => setFilters({ ...filters, source: e.target.value })}>
                  <option value="">All sources</option>
                  {["ctwa", "whatsapp_referral", "whatsapp", "ad_popup", "website", "landing_page", "meta_lead_form", "manual", "unknown"]
                    .map((s) => <option key={s} value={s}>{labelize(s)}</option>)}
                </select></label>
            </div>
            <div className="kanban" aria-label="Pipeline board">
              {pipeline?.columns.map((column) => (
                <section key={column.status} className={`kanban-column stage-${column.status.toLowerCase()}`} aria-label={STATUS_LABELS[column.status]}>
                  <header><h3>{STATUS_LABELS[column.status]}</h3><span className="side-count">{num(column.count)}</span></header>
                  {column.leads.length === 0 ? <p className="empty-state">No leads</p> : column.leads.map((lead) => (
                    <LeadCardView key={lead.id} lead={lead} onOpen={() => setOpenLead(lead.id)} />
                  ))}
                  {column.count > column.leads.length && <p className="helper-text">+{num(column.count - column.leads.length)} more — refine the search</p>}
                </section>
              ))}
            </div>
          </>
        ) : clientId && (
          <TeamView clientId={clientId} days={days} agents={agents} projects={projects} onChanged={() => void load()} onError={setError} />
        )}
        {openLead && clientId && (
          <CrmLeadDrawer leadId={openLead} agents={agents} projects={projects} onClose={() => setOpenLead(null)} onChanged={() => void load()} />
        )}
      </div>
    </ClientRequired>
  );
}

function LeadCardView({ lead, onOpen }: { lead: LeadCard; onOpen: () => void }) {
  const overdue = lead.next_follow_up_at && new Date(lead.next_follow_up_at).getTime() < Date.now();
  return (
    <button type="button" className="lead-card" onClick={onOpen} aria-label={`Open ${lead.name}`}>
      <span className="lead-card-top"><strong>{lead.name}</strong>
        {lead.qualification_score !== null && <span className="score-chip" title="Qualification score">{lead.qualification_score}</span>}</span>
      <small>{lead.campaign_label ?? labelize(lead.attribution_source)}</small>
      <small>{lead.assigned_agent_name ?? "Unassigned"}{lead.review_status === "rejected" ? " · rejected in review" : ""}</small>
      {lead.next_follow_up_at && <small className={overdue ? "overdue" : undefined}>Follow-up {formatWhen(lead.next_follow_up_at)}</small>}
    </button>
  );
}

function NewLeadForm({ clientId, agents, projects, onCreated, onError }: {
  clientId: string; agents: Agent[]; projects: Project[]; onCreated: (id: string) => void; onError: (e: string) => void;
}) {
  const [form, setForm] = useState({ phone_number: "", name: "", email: "", agent_id: "", project_id: "", note: "" });
  const [saving, setSaving] = useState(false);
  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setSaving(true);
    try {
      const lead = await createManualLead(clientId, {
        phone_number: form.phone_number, name: form.name || undefined, email: form.email || undefined,
        agent_id: form.agent_id || undefined, project_id: form.project_id || undefined, note: form.note || undefined,
      });
      onCreated(lead.id);
    } catch (err) {
      onError(errorText(err, "Could not create the lead"));
    } finally {
      setSaving(false);
    }
  }
  return (
    <form className="surface-card" onSubmit={(e) => void submit(e)} aria-label="New lead">
      <p className="step-label">MANUAL LEAD</p>
      <div className="form-grid-2">
        <label className="form-field"><span>Phone</span><input required value={form.phone_number} onChange={(e) => setForm({ ...form, phone_number: e.target.value })} /></label>
        <label className="form-field"><span>Name</span><input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></label>
        <label className="form-field"><span>Email</span><input type="email" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} /></label>
        <label className="form-field"><span>Assign to</span><select value={form.agent_id} onChange={(e) => setForm({ ...form, agent_id: e.target.value })}>
          <option value="">Nobody yet</option>{agents.filter((a) => a.active).map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}</select></label>
        <label className="form-field"><span>Project</span><select value={form.project_id} onChange={(e) => setForm({ ...form, project_id: e.target.value })}>
          <option value="">—</option>{projects.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}</select></label>
        <label className="form-field"><span>Note</span><input value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} /></label>
      </div>
      <div className="campaign-actions"><button type="submit" className="primary-button" disabled={saving}>{saving ? "Saving…" : "Create lead"}</button></div>
      <p className="helper-text">Manual leads never receive automated WhatsApp messages or calls.</p>
    </form>
  );
}

function TeamView({ clientId, days, agents, projects, onChanged, onError }: {
  clientId: string; days: number; agents: Agent[]; projects: Project[]; onChanged: () => void; onError: (e: string) => void;
}) {
  const [rows, setRows] = useState<AgentPerformance[]>([]);
  const [currency, setCurrency] = useState("INR");
  const [agentName, setAgentName] = useState("");
  const [agentEmail, setAgentEmail] = useState("");
  const [project, setProject] = useState({ name: "", location: "", min_price: "", max_price: "" });

  useEffect(() => {
    void getPerformance(clientId, rangeForDays(days)).then((r) => { setRows(r.agents); setCurrency(r.currency); })
      .catch((err) => onError(errorText(err, "Could not load agent performance")));
  }, [clientId, days, agents, onError]);

  async function addAgent(event: React.FormEvent) {
    event.preventDefault();
    try {
      await createAgent(clientId, { name: agentName, email: agentEmail || undefined });
      setAgentName("");
      setAgentEmail("");
      onChanged();
    } catch (err) {
      onError(errorText(err, "Could not add the agent"));
    }
  }

  async function addProject(event: React.FormEvent) {
    event.preventDefault();
    try {
      await createProject(clientId, {
        name: project.name, location: project.location || undefined,
        min_price: project.min_price ? Number(project.min_price) : undefined,
        max_price: project.max_price ? Number(project.max_price) : undefined,
      });
      setProject({ name: "", location: "", min_price: "", max_price: "" });
      onChanged();
    } catch (err) {
      onError(errorText(err, "Could not add the project"));
    }
  }

  return (
    <>
      <section className="surface-card">
        <div className="section-title-row"><div><p className="step-label">SALES AGENT PERFORMANCE</p><h3>Leads created in the last {days} days</h3></div></div>
        {rows.length === 0 ? <p className="empty-state">Add sales agents to track their performance.</p> : (
          <div className="table-wrap"><table>
            <thead><tr><th>Agent</th><th className="num">Assigned</th><th className="num">Contacted</th><th className="num">Qualified</th>
              <th className="num">Visits done</th><th className="num">Bookings</th><th className="num">Confirmed revenue</th>
              <th className="num">Avg. first response</th><th className="num">Overdue follow-ups</th></tr></thead>
            <tbody>{rows.map((row) => (
              <tr key={row.agent_id}><td><strong>{row.agent_name}</strong></td><td className="num">{num(row.assigned_leads)}</td>
                <td className="num">{num(row.contacted_leads)}</td><td className="num">{num(row.qualified_leads)}</td>
                <td className="num">{num(row.site_visits_completed)}</td><td className="num">{num(row.bookings)}</td>
                <td className="num">{money(row.confirmed_revenue, currency)}</td>
                <td className="num">{row.avg_first_response_minutes === null ? "—" : `${Math.round(row.avg_first_response_minutes)} min`}</td>
                <td className="num">{num(row.overdue_follow_ups)}</td></tr>
            ))}</tbody>
          </table></div>
        )}
      </section>
      <section className="insight-grid">
        <form className="surface-card" onSubmit={(e) => void addAgent(e)} aria-label="Add agent">
          <p className="step-label">ADD SALES AGENT</p>
          <label className="form-field"><span>Name</span><input required value={agentName} onChange={(e) => setAgentName(e.target.value)} /></label>
          <label className="form-field"><span>Email</span><input type="email" value={agentEmail} onChange={(e) => setAgentEmail(e.target.value)} /></label>
          <div className="campaign-actions"><button type="submit" className="secondary-button">Add agent</button></div>
          <p className="helper-text">{agents.length} agent(s) on this client.</p>
        </form>
        <form className="surface-card" onSubmit={(e) => void addProject(e)} aria-label="Add project">
          <p className="step-label">ADD PROJECT / PROPERTY</p>
          <label className="form-field"><span>Name</span><input required value={project.name} onChange={(e) => setProject({ ...project, name: e.target.value })} /></label>
          <label className="form-field"><span>Location</span><input value={project.location} onChange={(e) => setProject({ ...project, location: e.target.value })} /></label>
          <div className="form-grid-2">
            <label className="form-field"><span>Min price</span><input type="number" min="0" value={project.min_price} onChange={(e) => setProject({ ...project, min_price: e.target.value })} /></label>
            <label className="form-field"><span>Max price</span><input type="number" min="0" value={project.max_price} onChange={(e) => setProject({ ...project, max_price: e.target.value })} /></label>
          </div>
          <div className="campaign-actions"><button type="submit" className="secondary-button">Add project</button></div>
          <p className="helper-text">{projects.length} project(s). Pricing and location feed the lead score.</p>
        </form>
      </section>
    </>
  );
}
