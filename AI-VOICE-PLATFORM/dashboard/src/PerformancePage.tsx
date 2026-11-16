import { useState } from "react";
import { ChartCard, COLORS, LeadsAndSalesChart, RankBars } from "./charts";
import { money, num, pct } from "./format";
import { ReachPage } from "./ReachPage";
import type { ClientAnalytics, LeadReviewStats } from "./types";

type Tab = "overview" | "reach";

export function PerformancePage({
  data,
  reviewStats,
  clientId,
  onChanged,
}: {
  data: ClientAnalytics;
  reviewStats: LeadReviewStats;
  clientId: string;
  onChanged: () => void;
}) {
  const [tab, setTab] = useState<Tab>("overview");
  const reviewed = reviewStats.accepted + reviewStats.rejected;
  const acceptanceRate = reviewed ? reviewStats.accepted / reviewed : 0;

  return (
    <div className="page-stack">
      <section className="page-intro">
        <div><p className="eyebrow">Measurement</p><h2>Performance that tells a story.</h2>
          <p>Track reach, lead quality and outcomes for {data.client_name}.</p></div>
        <div className="segmented-tabs" role="tablist" aria-label="Performance sections">
          <button type="button" role="tab" aria-selected={tab === "overview"} onClick={() => setTab("overview")}>Overview</button>
          <button type="button" role="tab" aria-selected={tab === "reach"} onClick={() => setTab("reach")}>Ad reach</button>
        </div>
      </section>

      <section className="metric-grid" aria-label="Campaign performance">
        <Metric label="People reached" value={num(data.reach.total_reach)} detail="Imported reach for this period" icon="◉" tone="violet" />
        <Metric label="Leads captured" value={num(data.summary.leads)} detail={`${num(data.summary.ad_leads)} from paid campaigns`} icon="↗" tone="blue" />
        <Metric label="Accepted leads" value={num(reviewStats.accepted)} detail={`${pct(acceptanceRate)} of reviewed leads`} icon="✓" tone="green" />
        <Metric label="Rejected leads" value={num(reviewStats.rejected)} detail={`${num(reviewStats.pending)} still need review`} icon="×" tone="amber" />
      </section>

      {tab === "overview" ? (
        <>
          <section className="insight-grid">
            <ChartCard title="Lead flow" subtitle={`${data.client_name} · daily leads and recorded sales`} hasData={data.summary.leads > 0 || data.summary.sales > 0}>
              <LeadsAndSalesChart leads={data.leads_by_day} sales={data.sales_by_day} />
            </ChartCard>
            <ChartCard title="Where attention lands" subtitle="Top locations by imported ad reach" hasData={data.reach.by_location.length > 0}
              emptyText="Import a regional reach report from the Ad reach tab to see location performance.">
              <RankBars data={data.reach.by_location.slice(0, 8).map((row) => ({ label: row.location, value: row.reach }))}
                color={COLORS.leads} name="People reached" />
            </ChartCard>
          </section>

          <section className="surface-card review-summary">
            <div className="section-title-row">
              <div><p className="step-label">LEAD QUALITY</p><h3>Acceptance and rejection</h3></div>
              <span className="review-rate">{reviewed ? pct(acceptanceRate) : "—"}<small>acceptance rate</small></span>
            </div>
            <div className="review-bar" aria-label={`${reviewStats.accepted} accepted, ${reviewStats.rejected} rejected, ${reviewStats.pending} pending`}>
              <span className="review-bar-accepted" style={{ width: `${reviewed ? (reviewStats.accepted / reviewed) * 100 : 0}%` }} />
              <span className="review-bar-rejected" style={{ width: `${reviewed ? (reviewStats.rejected / reviewed) * 100 : 0}%` }} />
            </div>
            <div className="review-legend">
              <span><i className="legend-dot accepted" />Accepted <b>{num(reviewStats.accepted)}</b></span>
              <span><i className="legend-dot rejected" />Rejected <b>{num(reviewStats.rejected)}</b></span>
              <span><i className="legend-dot pending" />Pending review <b>{num(reviewStats.pending)}</b></span>
            </div>
            <p className="helper-text">Acceptance is an operator’s lead-quality decision; it does not replace recorded call consent.</p>
          </section>

          <section className="surface-card ad-performance">
            <div className="section-title-row"><div><p className="step-label">CAMPAIGN BREAKDOWN</p><h3>Reach to lead</h3></div>
              <span className="muted-label">{data.days}-day window</span></div>
            {data.reach.by_ad.length === 0 ? <p className="empty-state">No ad reach has been imported for this period yet.</p> : (
              <div className="table-wrap"><table><thead><tr><th>Ad</th><th className="num">Reach</th><th className="num">Leads</th><th className="num">Lead rate</th><th className="num">Recorded revenue</th></tr></thead>
                <tbody>{data.reach.by_ad.map((ad) => <tr key={ad.ad_id}><td><strong>{ad.ad_name}</strong></td><td className="num">{num(ad.reach)}</td><td className="num">{num(ad.leads)}</td><td className="num">{ad.reach ? pct(ad.lead_rate) : "—"}</td><td className="num">{money(data.ad_sales.by_ad.find((row) => row.ad_id === ad.ad_id)?.revenue ?? 0, data.currency)}</td></tr>)}</tbody>
              </table></div>
            )}
          </section>
        </>
      ) : (
        <ReachPage data={data} clientId={clientId} onChanged={onChanged} />
      )}
    </div>
  );
}

function Metric({ label, value, detail, icon, tone }: { label: string; value: string; detail: string; icon: string; tone: string }) {
  return <article className="metric-card"><span className={`metric-icon ${tone}`}>{icon}</span><p>{label}</p><strong>{value}</strong><small>{detail}</small></article>;
}
