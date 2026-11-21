import { useCallback, useEffect, useState } from "react";
import { formatDay, formatWhen, money, num } from "./format";
import { cancelSale, confirmSale, createClientSale, getSale, labelize, listAgents, listClientSales, listProjects, updateSale } from "./measurementApi";
import { ClientRequired, ErrorBanner, KpiCard, errorText } from "./measurementUi";
import type { Agent, Project, SaleListPage, SaleRecord, SaleStatus } from "./measurementTypes";
import { actorName } from "./privacy";

const STATUSES: SaleStatus[] = ["NEW", "BOOKING_PENDING", "BOOKED", "PAYMENT_PENDING", "PARTIALLY_PAID", "FULLY_PAID", "CANCELLED", "REFUNDED"];
const CHANNELS = ["sales_team", "ad", "calling_agent", "referral", "walk_in", "other"];

export function SalesAttributionPage({ clientId, currency = "INR" }: { clientId?: string; currency?: string }) {
  const [page, setPage] = useState<SaleListPage | null>(null);
  const [statusFilter, setStatusFilter] = useState("");
  const [selected, setSelected] = useState<SaleRecord | null>(null);
  const [agents, setAgents] = useState<Agent[]>([]);
  const [projects, setProjects] = useState<Project[]>([]);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!clientId) return;
    try {
      setPage(await listClientSales(clientId, { status: statusFilter || undefined }));
      setError(null);
    } catch (err) {
      setError(errorText(err, "Could not load sales"));
    }
  }, [clientId, statusFilter]);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    if (!clientId) return;
    void listAgents(clientId).then(setAgents).catch(() => setAgents([]));
    void listProjects(clientId).then(setProjects).catch(() => setProjects([]));
  }, [clientId]);

  async function open(id: string) {
    try {
      setSelected(await getSale(id));
    } catch (err) {
      setError(errorText(err, "Could not load the sale"));
    }
  }

  const t = page?.totals;
  return (
    <ClientRequired clientId={clientId}>
      <div className="page-stack">
        <section className="page-intro">
          <div><p className="eyebrow">Sales attribution</p><h2>Bookings are not revenue until confirmed.</h2>
            <p>Sales are typed in by your team. Revenue counts only after a manager confirms money was collected.</p></div>
          <label className="form-field compact"><span>Status</span>
            <select aria-label="Status filter" value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)}>
              <option value="">All</option>{STATUSES.map((s) => <option key={s} value={s}>{labelize(s)}</option>)}</select></label>
        </section>
        <ErrorBanner error={error} onDismiss={() => setError(null)} />
        {t && (
          <section className="metric-grid kpi-grid" aria-label="Revenue summary">
            <KpiCard label="Bookings" value={num(t.bookings)} source="crm_verified" note={`${num(t.count)} sales recorded`} />
            <KpiCard label="Booking value" value={money(t.booking_value, currency, true)} source="manual_financial" note="Agreed value, not revenue" />
            <KpiCard label="Amount collected" value={money(t.amount_collected, currency, true)} source="manual_financial" />
            <KpiCard label="Outstanding" value={money(t.outstanding, currency, true)} source="manual_financial" />
            <KpiCard label="Confirmed revenue" value={money(t.confirmed_revenue, currency, true)} source="manual_financial" note="Used for ROAS" />
          </section>
        )}
        {clientId && <RecordSaleForm clientId={clientId} agents={agents} projects={projects} onSaved={(s) => { setSelected(s); void load(); }} onError={setError} />}
        <section className="surface-card">
          <div className="section-title-row"><div><p className="step-label">SALES</p><h3>Recorded sales</h3></div></div>
          {!page || page.sales.length === 0 ? <p className="empty-state">No sales recorded.</p> : (
            <div className="table-wrap"><table>
              <thead><tr><th>Customer</th><th>Campaign</th><th>Status</th><th className="num">Booking value</th><th className="num">Collected</th>
                <th className="num">Outstanding</th><th className="num">Confirmed revenue</th><th>Booked</th></tr></thead>
              <tbody>{page.sales.map((s) => (
                <tr key={s.id} className="click-row" onClick={() => void open(s.id)}>
                  <td><strong>{s.lead_name ?? "No lead"}</strong><small>{s.project_name ?? labelize(s.channel)}{s.legacy_record ? " · legacy record" : ""}</small></td>
                  <td>{s.campaign_id ?? "Unattributed"}</td>
                  <td><span className={`pill sale-${s.status.toLowerCase()}`}>{labelize(s.status)}</span></td>
                  <td className="num">{money(s.booking_value, currency)}</td><td className="num">{money(s.amount_collected, currency)}</td>
                  <td className="num">{money(s.outstanding_amount, currency)}</td><td className="num">{money(s.confirmed_revenue, currency)}</td>
                  <td>{formatDay(s.booking_date)}</td>
                </tr>
              ))}</tbody>
            </table></div>
          )}
        </section>
        {selected && <SalePanel sale={selected} currency={currency} onClose={() => setSelected(null)}
          onChanged={(s) => { setSelected(s); void load(); }} onError={setError} />}
      </div>
    </ClientRequired>
  );
}

export function RecordSaleForm({ clientId, agents, projects, onSaved, onError }: {
  clientId: string; agents: Agent[]; projects: Project[]; onSaved: (s: SaleRecord) => void; onError: (e: string) => void;
}) {
  const empty = { lead_id: "", campaign_id: "", booking_value: "", amount_collected: "", status: "BOOKED", channel: "sales_team", agent_id: "", project_id: "", booking_date: "", note: "" };
  const [form, setForm] = useState(empty);
  const set = (key: keyof typeof form, value: string) => setForm({ ...form, [key]: value });
  async function submit(event: React.FormEvent) {
    event.preventDefault();
    try {
      const sale = await createClientSale(clientId, {
        lead_id: form.lead_id.trim() || undefined, campaign_id: form.campaign_id.trim() || undefined,
        booking_value: Number(form.booking_value), amount_collected: form.amount_collected ? Number(form.amount_collected) : undefined,
        status: form.status, channel: form.channel, agent_id: form.agent_id || undefined, project_id: form.project_id || undefined,
        booking_date: form.booking_date || undefined, note: form.note || undefined,
      });
      setForm(empty);
      onSaved(sale);
    } catch (err) {
      onError(errorText(err, "Could not record the sale"));
    }
  }
  return (
    <form className="surface-card" onSubmit={(e) => void submit(e)} aria-label="Record sale">
      <p className="step-label">RECORD SALE</p>
      <div className="form-grid-2">
        <label className="form-field"><span>Lead id <small>campaign comes from the lead's first touch</small></span>
          <input value={form.lead_id} onChange={(e) => set("lead_id", e.target.value)} placeholder="Optional" /></label>
        <label className="form-field"><span>Campaign id <small>overrides the lead's</small></span>
          <input value={form.campaign_id} onChange={(e) => set("campaign_id", e.target.value)} /></label>
        <label className="form-field"><span>Booking value</span>
          <input required type="number" min="0.01" step="0.01" value={form.booking_value} onChange={(e) => set("booking_value", e.target.value)} /></label>
        <label className="form-field"><span>Amount collected</span>
          <input type="number" min="0" step="0.01" value={form.amount_collected} onChange={(e) => set("amount_collected", e.target.value)} /></label>
        <label className="form-field"><span>Status</span><select value={form.status} onChange={(e) => set("status", e.target.value)}>
          {STATUSES.filter((s) => s !== "CANCELLED" && s !== "REFUNDED").map((s) => <option key={s} value={s}>{labelize(s)}</option>)}</select></label>
        <label className="form-field"><span>Channel</span><select value={form.channel} onChange={(e) => set("channel", e.target.value)}>
          {CHANNELS.map((c) => <option key={c} value={c}>{labelize(c)}</option>)}</select></label>
        <label className="form-field"><span>Agent</span><select value={form.agent_id} onChange={(e) => set("agent_id", e.target.value)}>
          <option value="">Lead's agent</option>{agents.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}</select></label>
        <label className="form-field"><span>Project</span><select value={form.project_id} onChange={(e) => set("project_id", e.target.value)}>
          <option value="">Lead's project</option>{projects.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}</select></label>
        <label className="form-field"><span>Booking date</span><input type="date" value={form.booking_date} onChange={(e) => set("booking_date", e.target.value)} /></label>
        <label className="form-field"><span>Note</span><input maxLength={500} value={form.note} onChange={(e) => set("note", e.target.value)} /></label>
      </div>
      <div className="campaign-actions"><button type="submit" className="primary-button">Record sale</button></div>
    </form>
  );
}

export function SalePanel({ sale, currency, onClose, onChanged, onError }: {
  sale: SaleRecord; currency: string; onClose: () => void; onChanged: (s: SaleRecord) => void; onError: (e: string) => void;
}) {
  const [status, setStatus] = useState<SaleStatus>(sale.status);
  const [collected, setCollected] = useState(String(sale.amount_collected));
  const [refunded, setRefunded] = useState(String(sale.refunded_amount));
  const [reason, setReason] = useState("");
  const closed = sale.status === "CANCELLED" || sale.status === "REFUNDED";

  async function act(action: () => Promise<SaleRecord>) {
    try {
      onChanged(await action());
    } catch (err) {
      onError(errorText(err, "Could not update the sale"));
    }
  }

  return (
    <section className="surface-card" aria-label="Sale detail">
      <div className="section-title-row"><div><p className="step-label">SALE</p><h3>{sale.lead_name ?? "Sale"} · {labelize(sale.status)}</h3></div>
        <button type="button" className="secondary-button" onClick={onClose}>Close</button></div>
      <dl className="facts">
        <div><dt>Booking value</dt><dd>{money(sale.booking_value, currency)}</dd></div>
        <div><dt>Confirmed revenue</dt><dd>{money(sale.confirmed_revenue, currency)}{sale.sale_confirmed_at ? ` · ${formatWhen(sale.sale_confirmed_at)}` : " · not confirmed"}</dd></div>
        <div><dt>Campaign / ad</dt><dd>{sale.campaign_id ?? "—"}{sale.ad_id ? ` / ${sale.ad_id}` : ""}</dd></div>
        <div><dt>Agent</dt><dd>{sale.agent_name ?? "—"}</dd></div>
      </dl>
      {!closed && (
        <form className="inline-form" aria-label="Update sale" onSubmit={(e) => {
          e.preventDefault();
          void act(() => updateSale(sale.id, { status, amount_collected: Number(collected), refunded_amount: Number(refunded), reason: reason || undefined }));
        }}>
          <select aria-label="Sale status" value={status} onChange={(e) => setStatus(e.target.value as SaleStatus)}>
            {STATUSES.filter((s) => s !== "CANCELLED").map((s) => <option key={s} value={s}>{labelize(s)}</option>)}</select>
          <label>Collected <input aria-label="Amount collected" type="number" min="0" step="0.01" value={collected} onChange={(e) => setCollected(e.target.value)} /></label>
          <label>Refunded <input aria-label="Refunded amount" type="number" min="0" step="0.01" value={refunded} onChange={(e) => setRefunded(e.target.value)} /></label>
          <button type="submit" className="secondary-button">Save changes</button>
        </form>
      )}
      <div className="visit-actions">
        {!closed && !sale.sale_confirmed_at && (
          <button type="button" className="primary-button" onClick={() => void act(() => confirmSale(sale.id, Number(collected)))}>Confirm revenue</button>
        )}
        {!closed && (
          <form className="inline-form" aria-label="Cancel sale" onSubmit={(e) => {
            e.preventDefault();
            if (reason.trim()) void act(() => cancelSale(sale.id, reason.trim()));
          }}>
            <input aria-label="Cancellation reason" value={reason} placeholder="Reason (needed to cancel)" onChange={(e) => setReason(e.target.value)} />
            <button type="submit" className="link-danger">Cancel sale</button>
          </form>
        )}
      </div>
      <h4>Audit history</h4>
      <ol className="timeline">{(sale.history ?? []).map((h, i) => (
        <li key={i}><span className="timeline-type">{labelize(h.action)}</span>
          <p>{h.from_status && h.to_status && h.from_status !== h.to_status ? `${labelize(h.from_status)} → ${labelize(h.to_status)}` : labelize(h.to_status)}
            {h.changes ? ` · ${Object.keys(h.changes).filter((k) => k !== "status").join(", ")}` : ""}{h.reason ? ` · ${h.reason}` : ""}</p>
          <small>{formatWhen(h.changed_at)}{h.actor_email ? ` · ${actorName(h.actor_email)}` : ""}</small></li>
      ))}</ol>
    </section>
  );
}
