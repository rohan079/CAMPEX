import { useEffect, useState } from "react";
import { ChartCard, COLORS, RankBars } from "./charts";
import { money, num, pct } from "./format";
import { getAttributionSummary, getCampaignReport, getKpis, labelize, listAgents, listProjects, rangeForDays } from "./measurementApi";
import { ClientRequired, ErrorBanner, KpiCard, SourceBadge, errorText } from "./measurementUi";
import type { Agent, AttributionSummary, CampaignReport, KpiReport, Metric, Project } from "./measurementTypes";

function formatMetric(metric: Metric, currency: string): string {
  if (metric.value === null) return "—";
  if (metric.unit === "count") return num(metric.value);
  if (metric.key === "roas") return `${metric.value.toFixed(2)}×`;
  if (metric.unit === "ratio") return pct(metric.value);
  return money(metric.value, currency, metric.value >= 1_000_000);
}

const moneyOrDash = (value: number | null, currency: string) => (value === null ? "—" : money(value, currency, value >= 1_000_000));

/** Leads, campaigns and revenue under a chosen attribution model. */
export function AttributionPage({ clientId, days }: { clientId?: string; days: number }) {
  const [model, setModel] = useState<"first_touch" | "last_touch">("first_touch");
  const [groupBy, setGroupBy] = useState<"campaign" | "adset" | "ad">("campaign");
  const [summary, setSummary] = useState<AttributionSummary | null>(null);
  const [report, setReport] = useState<CampaignReport | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!clientId) return;
    let active = true;
    const range = rangeForDays(days);
    Promise.all([getAttributionSummary(clientId, range), getCampaignReport(clientId, { ...range, model, group_by: groupBy })])
      .then(([nextSummary, nextReport]) => {
        if (!active) return;
        setSummary(nextSummary);
        setReport(nextReport);
        setError(null);
      })
      .catch((err) => { if (active) setError(errorText(err, "Could not load attribution")); });
    return () => { active = false; };
  }, [clientId, days, model, groupBy]);

  const currency = report?.currency ?? "INR";
  const known = report?.rows.filter((row) => row.key !== "unknown") ?? [];

  return (
    <ClientRequired clientId={clientId}>
      <div className="page-stack">
        <section className="page-intro">
          <div><p className="eyebrow">Attribution</p><h2>Which campaigns produce buyers.</h2>
            <p>First touch never changes after it is recorded; last touch follows the most recent campaign interaction.</p></div>
          <div className="filter-inline">
            <div className="segmented-tabs" role="tablist" aria-label="Attribution model">
              <button type="button" role="tab" aria-selected={model === "first_touch"} onClick={() => setModel("first_touch")}>First touch</button>
              <button type="button" role="tab" aria-selected={model === "last_touch"} onClick={() => setModel("last_touch")}>Last touch</button>
            </div>
            <label className="form-field compact"><span>Group by</span>
              <select aria-label="Group by" value={groupBy} onChange={(e) => setGroupBy(e.target.value as typeof groupBy)}>
                <option value="campaign">Campaign</option><option value="adset">Ad set</option><option value="ad">Ad</option>
              </select></label>
          </div>
        </section>
        <ErrorBanner error={error} onDismiss={() => setError(null)} />

        {summary && (
          <section className="metric-grid" aria-label="Attribution coverage">
            <KpiCard label="Leads in period" value={num(summary.total_leads)} source="campex_tracked" />
            <KpiCard label="Attributed to a campaign" value={num(summary.attributed_leads)} source="campex_tracked" />
            <KpiCard label="Unknown attribution" value={`${summary.unknown_percentage}%`} source="campex_tracked" note={`${num(summary.unknown_leads)} leads with no campaign evidence`} />
            <KpiCard label="First ≠ last touch" value={num(summary.first_last_differ)} source="campex_tracked" note="Leads that came back through another campaign" />
          </section>
        )}

        {report && (
          <>
            {!report.meta_spend_available && <p className="notice warn-notice">Meta spend unavailable: {report.meta_spend_note}. Cost and ROAS columns are blank rather than estimated.</p>}
            <section className="insight-grid">
              <ChartCard title={`Leads by ${groupBy}`} subtitle={labelize(report.model)} hasData={report.rows.some((r) => r.leads > 0)}>
                <RankBars data={report.rows.filter((r) => r.leads > 0).slice(0, 8).map((r) => ({ label: r.label, value: r.leads }))} color={COLORS.leads} name="Leads" />
              </ChartCard>
              <ChartCard title={`Confirmed revenue by ${groupBy}`} subtitle="Manually entered, confirmed sales" hasData={known.some((r) => r.confirmed_revenue > 0)}>
                <RankBars data={known.filter((r) => r.confirmed_revenue > 0).slice(0, 8).map((r) => ({ label: r.label, value: r.confirmed_revenue }))}
                  color={COLORS.ads} name="Confirmed revenue" format={(v) => money(v, currency, true)} />
              </ChartCard>
            </section>
            <section className="surface-card">
              <div className="section-title-row"><div><p className="step-label">ROI BY {groupBy.toUpperCase()}</p><h3>Leads to revenue</h3></div>
                <span className="source-legend"><SourceBadge source="crm_verified" /><SourceBadge source="manual_financial" /><SourceBadge source="meta_reported" /></span></div>
              {report.rows.length === 0 ? <p className="empty-state">No leads, sales or spend in this period.</p> : (
                <div className="table-wrap"><table>
                  <thead><tr><th>{labelize(groupBy)}</th><th className="num">Leads</th><th className="num">Qualified</th><th className="num">Visits done</th>
                    <th className="num">Bookings</th><th className="num">Confirmed revenue</th><th className="num">Meta spend</th><th className="num">CPL</th><th className="num">ROAS</th></tr></thead>
                  <tbody>{report.rows.map((row) => (
                    <tr key={row.key}>
                      <td><strong>{row.label}</strong>{row.key !== "unknown" && !row.key.startsWith("utm:") && <small>{row.key}</small>}</td>
                      <td className="num">{num(row.leads)}</td><td className="num">{num(row.qualified_leads)}</td>
                      <td className="num">{num(row.site_visits_completed)}</td><td className="num">{num(row.bookings)}</td>
                      <td className="num">{money(row.confirmed_revenue, currency)}</td>
                      <td className="num">{moneyOrDash(row.meta_spend, currency)}</td>
                      <td className="num">{moneyOrDash(row.cost_per_lead, currency)}</td>
                      <td className="num">{row.roas === null ? "—" : `${row.roas.toFixed(2)}×`}</td>
                    </tr>
                  ))}</tbody>
                </table></div>
              )}
              <p className="helper-text">Leads count the period's new leads under the selected touch. Bookings use the booking date, revenue the confirmation date, and spend Meta's own figures for the client's ad accounts. ROAS = confirmed revenue ÷ Meta spend.</p>
            </section>
          </>
        )}
      </div>
    </ClientRequired>
  );
}

/** The measurement KPI dashboard with provenance on every number. */
export function MeasurementPage({ clientId, days }: { clientId?: string; days: number }) {
  const [report, setReport] = useState<KpiReport | null>(null);
  const [agents, setAgents] = useState<Agent[]>([]);
  const [projects, setProjects] = useState<Project[]>([]);
  const [filters, setFilters] = useState({ campaign_id: "", agent_id: "", project_id: "", ad_account_id: "" });
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!clientId) return;
    void listAgents(clientId).then(setAgents).catch(() => setAgents([]));
    void listProjects(clientId).then(setProjects).catch(() => setProjects([]));
  }, [clientId]);

  useEffect(() => {
    if (!clientId) return;
    let active = true;
    getKpis(clientId, { ...rangeForDays(days), ...filters })
      .then((next) => { if (active) { setReport(next); setError(null); } })
      .catch((err) => { if (active) setError(errorText(err, "Could not load KPIs")); });
    return () => { active = false; };
  }, [clientId, days, filters]);

  return (
    <ClientRequired clientId={clientId}>
      <div className="page-stack">
        <section className="page-intro">
          <div><p className="eyebrow">Measurement</p><h2>From spend to confirmed revenue.</h2>
            <p>Each figure shows whether it is Meta-reported, tracked by Campex, verified in the CRM or entered by hand.</p></div>
        </section>
        <div className="filter-row surface-card" role="group" aria-label="KPI filters">
          <label className="form-field"><span>Campaign id</span><input value={filters.campaign_id} onChange={(e) => setFilters({ ...filters, campaign_id: e.target.value.trim() })} placeholder="All campaigns" /></label>
          <label className="form-field"><span>Sales agent</span><select value={filters.agent_id} onChange={(e) => setFilters({ ...filters, agent_id: e.target.value })}>
            <option value="">All agents</option>{agents.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}</select></label>
          <label className="form-field"><span>Project</span><select value={filters.project_id} onChange={(e) => setFilters({ ...filters, project_id: e.target.value })}>
            <option value="">All projects</option>{projects.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}</select></label>
          <label className="form-field"><span>Ad account</span><select value={filters.ad_account_id} onChange={(e) => setFilters({ ...filters, ad_account_id: e.target.value })}>
            <option value="">All mapped</option>{report?.ad_account_ids.map((id) => <option key={id}>{id}</option>)}</select></label>
        </div>
        <ErrorBanner error={error} onDismiss={() => setError(null)} />
        {report && (
          <>
            {!report.meta_spend_available && <p className="notice warn-notice">Meta spend unavailable: {report.meta_spend_note}. Cost metrics and ROAS stay blank.</p>}
            <section className="metric-grid kpi-grid" aria-label="Key performance indicators">
              {report.metrics.map((metric) => (
                <KpiCard key={metric.key} label={metric.label} value={formatMetric(metric, report.currency)} source={metric.source} note={metric.note} />
              ))}
            </section>
            <section className="insight-grid">
              <ChartCard title="Lead funnel" subtitle="Leads created in the period and how far they got" hasData={report.funnel[0]?.count > 0}>
                <RankBars data={report.funnel.map((step) => ({ label: step.stage, value: step.count }))} color={COLORS.agent} name="Leads" />
              </ChartCard>
              <ChartCard title="Tracked events" subtitle="Campex conversion events" hasData={report.tracked_events.length > 0}>
                <RankBars data={report.tracked_events.map((row) => ({ label: row.event_name, value: row.count }))} color={COLORS.leads} name="Events" />
              </ChartCard>
            </section>
          </>
        )}
      </div>
    </ClientRequired>
  );
}
