import { ChartCard, COLORS, RankBars, RevenueDayChart } from "./charts";
import { money, num, pct } from "./format";
import { RecentSales } from "./RecentSales";
import { SaleForm } from "./SaleForm";
import { SectionHead, Stat } from "./ui";
import type { Client, ClientAnalytics } from "./types";

export function AdSalesPage({
  data,
  clients,
  clientId,
  onChanged,
}: {
  data: ClientAnalytics;
  clients: Client[];
  clientId: string;
  onChanged: () => void;
}) {
  const cur = data.currency;
  const ads = data.ad_sales;
  const average = ads.sales > 0 ? ads.revenue / ads.sales : 0;
  const rows = [...ads.by_ad].sort((a, b) => b.revenue - a.revenue || b.leads - a.leads);

  return (
    <div className="stack">
      <SectionHead
        eyebrow="Section 1"
        text="Sales from people who came in through an ad, by day and by ad."
      />
      <section className="stats" aria-label="Ad sales summary">
        <Stat label="Ad revenue" value={money(ads.revenue, cur)} accent="ads" detail={data.client_name} />
        <Stat label="Ad sales" value={num(ads.sales)} detail="Recorded in this period" />
        <Stat label="Average sale" value={money(average, cur)} detail="Revenue per ad sale" />
        <Stat label="Ad leads" value={num(data.summary.ad_leads)} detail={`${pct(ads.sales / Math.max(data.summary.ad_leads, 1))} turned into a sale`} />
      </section>

      <section className="split">
        <ChartCard title="Ad revenue by day" hasData={ads.revenue > 0} emptyText="No ad sales in this period yet.">
          <RevenueDayChart data={ads.by_day} color={COLORS.ads} name="Ad revenue" currency={cur} />
        </ChartCard>
        <ChartCard title="Revenue by ad" subtitle="Highest first" hasData={rows.some((r) => r.revenue > 0)} emptyText="No sale is linked to an ad yet. Give the sale an ad id, or record it on a lead that came from an ad.">
          <RankBars
            data={rows.filter((r) => r.revenue > 0).slice(0, 8).map((r) => ({ label: r.ad_name, value: r.revenue }))}
            color={COLORS.ads}
            name="Revenue"
            format={(v) => money(v, cur)}
          />
        </ChartCard>
      </section>

      <section className="card table-card">
        <div className="card-head">
          <h3>Every ad</h3>
          <p>Leads, sales and reach for each ad in this period.</p>
        </div>
        {rows.length === 0 ? (
          <p className="empty">No ads yet. Ads appear once a lead, sale or reach row carries an ad id.</p>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Ad</th>
                  <th className="num">Reach</th>
                  <th className="num">Leads</th>
                  <th className="num">Lead rate</th>
                  <th className="num">Sales</th>
                  <th className="num">Revenue</th>
                  <th className="num">Agent sales</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.ad_id}>
                    <td>
                      <strong>{row.ad_name}</strong>
                      {row.ad_name !== row.ad_id && <small>{row.ad_id}</small>}
                    </td>
                    <td className="num">{row.reach > 0 ? num(row.reach) : "—"}</td>
                    <td className="num">{num(row.leads)}</td>
                    <td className="num">{row.reach > 0 ? pct(row.lead_rate) : "—"}</td>
                    <td className="num">{num(row.sales)}</td>
                    <td className="num">{money(row.revenue, cur)}</td>
                    <td className="num">{num(row.agent_sales)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <p className="hint">Agent sales are sales the calling agent closed from that ad's leads. They are counted in the Calling agent section, not in ad revenue.</p>
      </section>

      <RecentSales channel="ad" clientId={clientId} clients={clients} data={data} onChanged={onChanged} />

      <section className="card">
        <div className="card-head">
          <h3>Record an ad sale</h3>
          <p>Sales are entered by hand. Pick the ad so it shows in the table above.</p>
        </div>
        <SaleForm
          clients={clients}
          clientId={clientId}
          channel="ad"
          lockChannel
          ads={rows.map((r) => ({ id: r.ad_id, name: r.ad_name }))}
          onSaved={onChanged}
        />
      </section>
    </div>
  );
}
