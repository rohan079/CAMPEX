import { useEffect, useState } from "react";
import { ChartCard, COLORS, RankBars } from "./charts";
import { money, num } from "./format";
import { getAudience, getPlacements } from "./intelligenceApi";
import type { AudienceReport, SegmentRow } from "./intelligenceTypes";
import { labelize } from "./measurementFormat";
import { ErrorBanner, errorText } from "./measurementUi";
import { DataNotes, moneyOrDash, pctOrDash } from "./PerformanceIntelligencePage";

const BREAKDOWNS = [["age,gender", "Age and gender"], ["age", "Age"], ["gender", "Gender"], ["region", "Region"], ["country", "Country"], ["device_platform", "Device"]];

export function AudiencePanel({ clientId, from, to }: { clientId: string; from: string; to: string }) {
  const [breakdown, setBreakdown] = useState("region");
  const [audience, setAudience] = useState<AudienceReport | null>(null);
  const [placements, setPlacements] = useState<AudienceReport | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    Promise.all([getAudience(clientId, { from, to }, breakdown), getPlacements(clientId, { from, to })])
      .then(([a, p]) => { if (active) { setAudience(a); setPlacements(p); setError(null); } })
      .catch((err) => { if (active) setError(errorText(err, "Could not load audience performance")); });
    return () => { active = false; };
  }, [clientId, from, to, breakdown]);

  const cur = audience?.currency ?? "INR";
  return (
    <>
      <div className="filter-row"><label className="form-field"><span>Breakdown</span>
        <select aria-label="Breakdown" value={breakdown} onChange={(e) => setBreakdown(e.target.value)}>
          {BREAKDOWNS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}</select></label></div>
      <ErrorBanner error={error} onDismiss={() => setError(null)} />
      {audience && (
        <>
          <DataNotes notes={audience.data_notes} />
          <section className="insight-grid">
            <ChartCard title="Leads by segment (Meta-reported)" subtitle={labelize(breakdown)} hasData={audience.segments.length > 0}>
              <RankBars data={audience.segments.slice(0, 10).map((s) => ({ label: s.label, value: s.meta_leads }))} color={COLORS.leads} name="Leads" />
            </ChartCard>
            <ChartCard title="Site visits by preferred location (CRM)" subtitle="From qualification answers" hasData={audience.crm_segments.some((s) => s.dimension === "preferred_location")}>
              <RankBars data={audience.crm_segments.filter((s) => s.dimension === "preferred_location").slice(0, 10)
                .map((s) => ({ label: s.segment ?? "unknown", value: s.site_visits }))} color={COLORS.agent} name="Site visits" />
            </ChartCard>
          </section>
          <SegmentTable title={`Audience: ${labelize(breakdown)}`} rows={audience.segments} currency={cur} />
          {audience.crm_segments.length > 0 && (
            <section className="surface-card"><p className="step-label">CRM-VERIFIED SEGMENTS</p><h3>Qualified leads, visits and bookings</h3>
              <div className="table-wrap"><table>
                <thead><tr><th>Dimension</th><th>Segment</th><th className="num">Leads</th><th className="num">Qualified</th><th className="num">Site visits</th><th className="num">Bookings</th><th className="num">Visit rate</th></tr></thead>
                <tbody>{audience.crm_segments.map((s, i) => (
                  <tr key={i}><td>{labelize(s.dimension)}</td><td>{s.segment ?? "—"}</td><td className="num">{num(s.leads)}</td><td className="num">{num(s.qualified_leads)}</td>
                    <td className="num">{num(s.site_visits)}</td><td className="num">{num(s.bookings)}</td><td className="num">{pctOrDash(s.site_visit_rate)}</td></tr>
                ))}</tbody>
              </table></div>
            </section>
          )}
          {audience.insights.length > 0 && (
            <section className="surface-card"><p className="step-label">INSIGHTS</p>
              <ul className="plain-list">{audience.insights.map((i, k) => <li key={k}>{i.message}</li>)}</ul></section>
          )}
        </>
      )}
      {placements && <SegmentTable title="Placements" rows={placements.segments} currency={cur} />}
    </>
  );
}

function SegmentTable({ title, rows, currency }: { title: string; rows: SegmentRow[]; currency: string }) {
  return (
    <section className="surface-card"><p className="step-label">META-REPORTED</p><h3>{title}</h3>
      {rows.length === 0 ? <p className="empty-state">No delivery for this breakdown.</p> : (
        <div className="table-wrap"><table>
          <thead><tr><th>Segment</th><th className="num">Spend</th><th className="num">CTR</th><th className="num">CPC</th><th className="num">Leads</th><th className="num">Cost / lead</th><th>Signal</th></tr></thead>
          <tbody>{rows.map((s) => (
            <tr key={s.label}><td>{s.label}</td><td className="num">{money(s.spend, currency)}</td><td className="num">{pctOrDash(s.ctr)}</td>
              <td className="num">{moneyOrDash(s.cpc, currency)}</td><td className="num">{num(s.meta_leads)}</td>
              <td className="num">{moneyOrDash(s.meta_cost_per_lead, currency)}</td><td>{s.flags.map(labelize).join(", ") || "—"}</td></tr>
          ))}</tbody>
        </table></div>
      )}
    </section>
  );
}
