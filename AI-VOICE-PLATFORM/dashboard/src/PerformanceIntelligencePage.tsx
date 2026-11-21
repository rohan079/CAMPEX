import { useEffect, useState } from "react";
import { CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { AnomalyPanel } from "./AnomalyPanel";
import { AudiencePanel } from "./AudiencePanel";
import { BudgetPanel } from "./BudgetPanel";
import { ChartCard, COLORS } from "./charts";
import { CreativePanel } from "./CreativePanel";
import { ExperimentPanel } from "./ExperimentPanel";
import { askPerformance, completeDays, getOverview, getTrends, refreshPerformance } from "./intelligenceApi";
import type { AskResult, DataNote, Overview, PerfRow, Trends } from "./intelligenceTypes";
import { ClientRequired, ErrorBanner, KpiCard, errorText } from "./measurementUi";
import { RecommendationsPanel } from "./RecommendationsPanel";
import { formatDay, money, num, pct } from "./format";

type Tab = "overview" | "recommendations" | "budget" | "creative" | "audience" | "anomalies" | "experiments";
const TABS: { id: Tab; label: string }[] = [
  { id: "overview", label: "Overview" }, { id: "recommendations", label: "Recommendations" }, { id: "budget", label: "Budget" },
  { id: "creative", label: "Creative" }, { id: "audience", label: "Audience" }, { id: "anomalies", label: "Anomalies" },
  { id: "experiments", label: "Experiments" },
];

export const moneyOrDash = (v: number | null | undefined, currency: string) => (v === null || v === undefined ? "—" : money(v, currency));
export const pctOrDash = (v: number | null | undefined) => (v === null || v === undefined ? "—" : pct(v));
export const ratio = (v: number | null | undefined) => (v === null || v === undefined ? "—" : `${v.toFixed(2)}×`);

export function DataNotes({ notes }: { notes: DataNote[] }) {
  if (!notes.length) return null;
  return (
    <ul className="data-notes" aria-label="Data quality notes">
      {notes.map((n, i) => <li key={`${n.code}-${i}`} className={`data-note note-${n.severity}`}>{n.message}</li>)}
    </ul>
  );
}

export function PerformanceIntelligencePage({ clientId, days }: { clientId?: string; days: number }) {
  const [tab, setTab] = useState<Tab>("overview");
  const range = completeDays(days);
  return (
    <ClientRequired clientId={clientId}>
      <div className="page-stack">
        <section className="page-intro">
          <div><p className="eyebrow">Performance intelligence</p><h2>What happened, why, and what to do next.</h2>
            <p>Numbers are computed from Meta Insights and your CRM; AI only explains them. Nothing here changes live campaigns.</p></div>
        </section>
        <div className="segmented-tabs intel-tabs" role="tablist" aria-label="Performance intelligence sections">
          {TABS.map((t) => <button key={t.id} type="button" role="tab" aria-selected={tab === t.id} onClick={() => setTab(t.id)}>{t.label}</button>)}
        </div>
        {clientId && tab === "overview" && <PerformanceOverview clientId={clientId} from={range.from} to={range.to} />}
        {clientId && tab === "recommendations" && <RecommendationsPanel clientId={clientId} from={range.from} to={range.to} />}
        {clientId && tab === "budget" && <BudgetPanel clientId={clientId} from={range.from} to={range.to} />}
        {clientId && tab === "creative" && <CreativePanel clientId={clientId} from={range.from} to={range.to} />}
        {clientId && tab === "audience" && <AudiencePanel clientId={clientId} from={range.from} to={range.to} />}
        {clientId && tab === "anomalies" && <AnomalyPanel clientId={clientId} />}
        {clientId && tab === "experiments" && <ExperimentPanel clientId={clientId} />}
      </div>
    </ClientRequired>
  );
}

function change(value: number | null | undefined) {
  if (value === null || value === undefined) return "no comparison";
  const sign = value > 0 ? "+" : "";
  return `${sign}${Math.round(value * 100)}% vs previous period`;
}

export function PerformanceOverview({ clientId, from, to }: { clientId: string; from: string; to: string }) {
  const [overview, setOverview] = useState<Overview | null>(null);
  const [trends, setTrends] = useState<Trends | null>(null);
  const [campaignId, setCampaignId] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let active = true;
    Promise.all([getOverview(clientId, { from, to }, campaignId || undefined), getTrends(clientId, { from, to }, campaignId || undefined)])
      .then(([o, t]) => { if (active) { setOverview(o); setTrends(t); setError(null); } })
      .catch((err) => { if (active) setError(errorText(err, "Could not load performance")); });
    return () => { active = false; };
  }, [clientId, from, to, campaignId]);

  async function refresh() {
    setBusy(true);
    try {
      const result = await refreshPerformance(clientId);
      setNote(`Synced ${num(result.snapshots)} daily Meta rows and re-ran anomaly detection.`);
      const [o, t] = await Promise.all([getOverview(clientId, { from, to }, campaignId || undefined), getTrends(clientId, { from, to }, campaignId || undefined)]);
      setOverview(o);
      setTrends(t);
    } catch (err) {
      setError(errorText(err, "Could not refresh Meta data"));
    } finally {
      setBusy(false);
    }
  }

  const t: PerfRow | undefined = overview?.current.totals;
  const cur = overview?.current.currency ?? "INR";
  const ch = overview?.pct_change ?? {};
  return (
    <>
      <div className="filter-row" role="group" aria-label="Overview filters">
        <label className="form-field"><span>Campaign</span>
          <select aria-label="Campaign filter" value={campaignId} onChange={(e) => setCampaignId(e.target.value)}>
            <option value="">All campaigns</option>
            {overview?.current.rows.map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
          </select></label>
        <div className="filter-actions"><button type="button" className="secondary-button" disabled={busy} onClick={() => void refresh()}>{busy ? "Refreshing…" : "Refresh Meta data"}</button></div>
      </div>
      <ErrorBanner error={error} onDismiss={() => setError(null)} />
      {note && <p className="notice success-notice" role="status">{note}</p>}
      {overview && t && (
        <>
          <p className="helper-text">{formatDay(from)} – {formatDay(to)} · Meta conversions counted with {overview.current.attribution_windows}; CRM outcomes by first touch.</p>
          <DataNotes notes={overview.current.data_notes} />
          <section className="metric-grid kpi-grid" aria-label="Performance overview">
            <KpiCard label="Spend" value={money(t.spend, cur)} source="meta_reported" note={change(ch.spend)} />
            <KpiCard label="Impressions" value={num(t.impressions)} source="meta_reported" note={change(ch.impressions)} />
            <KpiCard label="Reach" value={t.reach === null ? "—" : num(t.reach)} source="meta_reported" note={t.reach === null ? "Not additive across accounts" : undefined} />
            <KpiCard label="Link CTR" value={pctOrDash(t.ctr)} source="derived" note={change(ch.ctr)} />
            <KpiCard label="CPC" value={moneyOrDash(t.cpc, cur)} source="derived" note={change(ch.cpc)} />
            <KpiCard label="CPM" value={moneyOrDash(t.cpm, cur)} source="derived" note={change(ch.cpm)} />
            <KpiCard label="Leads (Meta)" value={num(t.meta_leads)} source="meta_reported" note={change(ch.meta_leads)} />
            <KpiCard label="Qualified leads" value={num(t.qualified_leads)} source="crm_verified" note={change(ch.qualified_leads)} />
            <KpiCard label="Cost per qualified lead" value={moneyOrDash(t.cost_per_qualified_lead, cur)} source="derived" note={change(ch.cost_per_qualified_lead)} />
            <KpiCard label="Site visits" value={num(t.site_visits)} source="crm_verified" note={change(ch.site_visits)} />
            <KpiCard label="Bookings" value={num(t.bookings)} source="crm_verified" note={change(ch.bookings)} />
            <KpiCard label="Confirmed revenue" value={money(t.confirmed_revenue, cur)} source="manual_financial" note={change(ch.confirmed_revenue)} />
            <KpiCard label="ROAS" value={ratio(t.roas)} source="derived" note="Total confirmed revenue ÷ total spend" />
          </section>
          {trends && (
            <ChartCard title="Daily trend" subtitle="Meta spend and leads with CRM qualified leads; dashed points are provisional" hasData={trends.points.some((p) => p.spend > 0 || p.crm_leads > 0)}
              emptyText="No daily data yet. Use Refresh Meta data to sync.">
              <ResponsiveContainer width="100%" height={260}>
                <LineChart data={trends.points} margin={{ top: 8, right: 16, left: 0, bottom: 0 }}>
                  <CartesianGrid stroke={COLORS.grid} vertical={false} />
                  <XAxis dataKey="day" tickFormatter={formatDay} fontSize={12} />
                  <YAxis yAxisId="left" fontSize={12} />
                  <YAxis yAxisId="right" orientation="right" fontSize={12} />
                  <Tooltip />
                  <Legend />
                  <Line yAxisId="left" type="monotone" dataKey="spend" name="Spend (Meta)" stroke={COLORS.ads} dot={false} />
                  <Line yAxisId="right" type="monotone" dataKey="meta_leads" name="Leads (Meta)" stroke={COLORS.leads} dot={false} />
                  <Line yAxisId="right" type="monotone" dataKey="qualified_leads" name="Qualified (CRM)" stroke={COLORS.agent} dot={false} />
                </LineChart>
              </ResponsiveContainer>
            </ChartCard>
          )}
          <CampaignTable rows={overview.current.rows} currency={cur} />
        </>
      )}
      <AskPanel clientId={clientId} from={from} to={to} />
    </>
  );
}

export function CampaignTable({ rows, currency }: { rows: PerfRow[]; currency: string }) {
  return (
    <section className="surface-card">
      <div className="section-title-row"><div><p className="step-label">CAMPAIGNS</p><h3>Spend to outcomes</h3></div></div>
      {rows.length === 0 ? <p className="empty-state">No Meta delivery in this period.</p> : (
        <div className="table-wrap"><table>
          <thead><tr><th>Campaign</th><th className="num">Spend</th><th className="num">CTR</th><th className="num">CPC</th>
            <th className="num">Leads (Meta)</th><th className="num">Qualified</th><th className="num">Cost / qualified</th>
            <th className="num">Visits</th><th className="num">Bookings</th><th className="num">ROAS</th></tr></thead>
          <tbody>{rows.map((r) => (
            <tr key={r.id}><td><strong>{r.name}</strong><small>{r.objective ?? ""}{r.flags.length ? ` · ${r.flags.join(", ").replaceAll("_", " ")}` : ""}</small></td>
              <td className="num">{money(r.spend, currency)}</td><td className="num">{pctOrDash(r.ctr)}</td><td className="num">{moneyOrDash(r.cpc, currency)}</td>
              <td className="num">{num(r.meta_leads)}</td><td className="num">{num(r.qualified_leads)}</td>
              <td className="num">{moneyOrDash(r.cost_per_qualified_lead, currency)}</td><td className="num">{num(r.site_visits)}</td>
              <td className="num">{num(r.bookings)}</td><td className="num">{ratio(r.roas)}</td></tr>
          ))}</tbody>
        </table></div>
      )}
    </section>
  );
}

const QUESTIONS = [
  "Which campaign is performing best?", "Which campaign generates the most qualified leads?", "Why has my cost per lead increased?",
  "Which ad should I test next?", "Should I increase or reduce my campaign budget?", "What happened to performance over the last seven days?",
];

export function AskPanel({ clientId, from, to }: { clientId: string; from: string; to: string }) {
  const [question, setQuestion] = useState("");
  const [comparison, setComparison] = useState<"previous_period" | "previous_month">("previous_period");
  const [answer, setAnswer] = useState<AskResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  async function ask(q: string) {
    if (!q.trim()) return;
    setBusy(true);
    setError(null);
    try {
      setAnswer(await askPerformance(clientId, q.trim(), { from, to }, comparison));
    } catch (err) {
      setError(errorText(err, "The AI analyst is unavailable"));
    } finally {
      setBusy(false);
    }
  }
  return (
    <section className="surface-card ask-panel" aria-label="Ask about performance">
      <p className="step-label">ASK THE ANALYST</p><h3>Questions about this period</h3>
      <div className="chips">{QUESTIONS.map((q) => <button key={q} type="button" className="chip" onClick={() => { setQuestion(q); void ask(q); }}>{q}</button>)}</div>
      <form className="inline-form" onSubmit={(e) => { e.preventDefault(); void ask(question); }}>
        <input aria-label="Question" value={question} maxLength={1000} placeholder="Compare this month with last month…" onChange={(e) => setQuestion(e.target.value)} />
        <select aria-label="Compare with" value={comparison} onChange={(e) => setComparison(e.target.value as typeof comparison)}>
          <option value="previous_period">vs previous period</option><option value="previous_month">vs previous month</option>
        </select>
        <button type="submit" className="primary-button" disabled={busy}>{busy ? "Thinking…" : "Ask"}</button>
      </form>
      <ErrorBanner error={error} />
      {answer && (
        <div className="ask-answer">
          {!answer.grounded && <p className="notice warn-notice">This answer mentions figures that are not in Campex's data ({answer.unsupported_numbers.join(", ")}). Treat them as unverified.</p>}
          <p>{answer.answer}</p>
          {answer.data_gaps.length > 0 && <details><summary>Data caveats</summary><ul>{answer.data_gaps.map((g, i) => <li key={i}>{g}</li>)}</ul></details>}
          <small>AI-generated{answer.grounded ? " · figures checked against Campex data" : ""} · {formatDay(answer.from)}–{formatDay(answer.to)} vs {formatDay(answer.comparison_from)}–{formatDay(answer.comparison_to)}</small>
        </div>
      )}
    </section>
  );
}
