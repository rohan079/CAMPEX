import { ChartCard, ClientCompareChart, LeadsAndSalesChart, RevenueByChannelChart } from "./charts";
import { money, num, pct } from "./format";
import { Stat } from "./ui";
import type { ClientAnalytics, Comparison } from "./types";

export function OverviewPage({
  data,
  comparison,
  allClients,
  onPickClient,
}: {
  data: ClientAnalytics;
  comparison: Comparison | null;
  allClients: boolean;
  onPickClient: (id: string) => void;
}) {
  const s = data.summary;
  const cur = data.currency;
  const rows = comparison?.clients ?? [];

  return (
    <div className="stack">
      <section className="stats" aria-label="Summary">
        <Stat label="Leads" value={num(s.leads)} detail={`${num(s.ad_leads)} from the ad popup`} accent="leads" />
        <Stat label="Revenue" value={money(s.revenue, cur)} detail={`${num(s.sales)} ${s.sales === 1 ? "sale" : "sales"}`} />
        <Stat
          label="From ads"
          value={money(s.ad_revenue, cur)}
          detail={`${num(s.ad_sales)} ${s.ad_sales === 1 ? "sale" : "sales"}`}
          accent="ads"
        />
        <Stat
          label="From calling agent"
          value={money(s.agent_revenue, cur)}
          detail={`${num(s.agent_sales)} ${s.agent_sales === 1 ? "sale" : "sales"}`}
          accent="agent"
        />
      </section>

      <section className="split">
        <ChartCard
          title="Leads and sales"
          subtitle={`${data.client_name} · bars are leads, the line is sales`}
          hasData={s.leads > 0 || s.sales > 0}
        >
          <LeadsAndSalesChart leads={data.leads_by_day} sales={data.sales_by_day} />
        </ChartCard>
        <ChartCard
          title="Revenue by channel"
          subtitle="Ads and the calling agent, per day"
          hasData={s.revenue > 0}
          emptyText="No sales recorded in this period. Record one from the Ad sales or Calling agent section."
        >
          <RevenueByChannelChart ads={data.ad_sales.by_day} agent={data.calling_agent.sales_by_day} currency={cur} />
        </ChartCard>
      </section>

      <section className="stats three" aria-label="Rates">
        <Stat label="Lead to sale" value={pct(s.lead_to_sale_rate)} detail="Sales divided by leads" />
        <Stat
          label="Calls answered"
          value={pct(data.calling_agent.connected_rate)}
          detail={`${num(data.calling_agent.connected)} of ${num(data.calling_agent.calls)} calls`}
        />
        <Stat label="People reached" value={num(s.reach)} detail="Imported ad reach, not additive across days" />
      </section>

      {allClients && (
        <>
          <section className="split">
            <ChartCard
              title="Clients compared"
              subtitle="Leads and sales in this period"
              hasData={rows.some((r) => r.leads > 0 || r.sales > 0)}
            >
              <ClientCompareChart data={rows.map((r) => ({ client: r.client_name, leads: r.leads, sales: r.sales }))} />
            </ChartCard>
            <section className="card table-card">
              <div className="card-head">
                <h3>Every client</h3>
                <p>Open a client to see its own graphs.</p>
              </div>
              {rows.length === 0 ? (
                <p className="empty">No clients yet. Add one under Clients.</p>
              ) : (
                <div className="table-wrap">
                  <table>
                    <thead>
                      <tr>
                        <th>Client</th>
                        <th className="num">Leads</th>
                        <th className="num">Sales</th>
                        <th className="num">Revenue</th>
                        <th className="num">Reach</th>
                      </tr>
                    </thead>
                    <tbody>
                      {rows.map((row) => (
                        <tr key={row.client_id} className="click-row" onClick={() => onPickClient(row.client_id)}>
                          <td><strong>{row.client_name}</strong></td>
                          <td className="num">{num(row.leads)}</td>
                          <td className="num">{num(row.sales)}</td>
                          <td className="num">{money(row.revenue, cur)}</td>
                          <td className="num">{num(row.reach)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </section>
          </section>
          {comparison && comparison.clients.length > 0 && (
            <p className="hint">All figures use one currency ({cur}). Reach is summed per client and is not de-duplicated.</p>
          )}
        </>
      )}
    </div>
  );
}
