import { CallsChart, ChartCard, COLORS, RevenueDayChart } from "./charts";
import { money, num, pct } from "./format";
import { RecentSales } from "./RecentSales";
import { SaleForm } from "./SaleForm";
import { SectionHead, Stat } from "./ui";
import type { Client, ClientAnalytics } from "./types";

export function AgentPage({
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
  const a = data.calling_agent;
  const cur = data.currency;

  return (
    <div className="stack">
      <SectionHead
        eyebrow="Section 2"
        text="Calls the voice agent placed, how many were answered, and the sales that followed."
      />
      <section className="stats" aria-label="Calling agent summary">
        <Stat label="Agent revenue" value={money(a.revenue, cur)} accent="agent" detail={data.client_name} />
        <Stat label="Agent sales" value={num(a.sales)} detail={`${pct(a.sale_rate)} of answered calls`} />
        <Stat label="Calls placed" value={num(a.calls)} detail="Queued and cancelled calls not counted" />
        <Stat label="Answered" value={pct(a.connected_rate)} detail={`${num(a.connected)} calls reached the lead`} />
      </section>

      <section className="split">
        <ChartCard title="Calls per day" subtitle="Answered and not answered, per day" hasData={a.calls > 0} emptyText="The agent has not placed a call in this period.">
          <CallsChart data={a.calls_by_day} />
        </ChartCard>
        <ChartCard title="Agent revenue by day" hasData={a.revenue > 0} emptyText="No calling agent sales in this period yet.">
          <RevenueDayChart data={a.sales_by_day} color={COLORS.agent} name="Agent revenue" currency={cur} />
        </ChartCard>
      </section>

      <RecentSales channel="calling_agent" clientId={clientId} clients={clients} data={data} onChanged={onChanged} />

      <section className="card">
        <div className="card-head">
          <h3>Record an agent sale</h3>
          <p>Record it on the lead for the most accurate numbers: open the lead from Leads and use "Record sale". Or enter it here for a client.</p>
        </div>
        <SaleForm clients={clients} clientId={clientId} channel="calling_agent" lockChannel onSaved={onChanged} />
      </section>
    </div>
  );
}
