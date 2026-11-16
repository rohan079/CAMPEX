import { useCallback, useEffect, useState } from "react";
import { deleteSale, getSales } from "./api";
import { formatWhen, money } from "./format";
import type { Client, ClientAnalytics, Sale, SaleChannel } from "./types";

export function RecentSales({
  channel,
  clientId,
  clients,
  data,
  onChanged,
}: {
  channel: SaleChannel;
  clientId: string;
  clients: Client[];
  /** Reloading analytics gives a new object, which refreshes this list too. */
  data: ClientAnalytics;
  onChanged: () => void;
}) {
  const [sales, setSales] = useState<Sale[]>([]);
  const [total, setTotal] = useState(0);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const page = await getSales({ clientId: clientId || undefined, channel, limit: 10 });
      setSales(page.sales);
      setTotal(page.total);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not load the sales");
    }
  }, [clientId, channel]);

  useEffect(() => {
    void load();
  }, [load, data]);

  async function remove(sale: Sale) {
    if (!window.confirm(`Delete this sale of ${money(sale.amount, data.currency)}?`)) return;
    try {
      await deleteSale(sale.id);
      onChanged();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not delete the sale");
    }
  }

  const clientName = (id: string) => clients.find((c) => c.id === id)?.name ?? "";

  return (
    <section className="card table-card">
      <div className="card-head">
        <h3>Recent sales</h3>
        <p>{total === 0 ? "None yet." : `Latest ${sales.length} of ${total}.`}</p>
      </div>
      {error && <p className="banner" role="alert">{error}</p>}
      {sales.length === 0 ? (
        <p className="empty">Recorded sales will be listed here.</p>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>When</th>
                {!clientId && <th>Client</th>}
                <th>Lead</th>
                <th>Ad</th>
                <th>Note</th>
                <th className="num">Amount</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {sales.map((sale) => (
                <tr key={sale.id}>
                  <td>{formatWhen(sale.sold_at)}</td>
                  {!clientId && <td>{clientName(sale.client_id) || "—"}</td>}
                  <td>{sale.lead_name || "—"}</td>
                  <td>{sale.ad_id || "—"}</td>
                  <td className="clip">{sale.note || "—"}</td>
                  <td className="num">{money(sale.amount, data.currency)}</td>
                  <td className="num">
                    <button type="button" className="link-danger" onClick={() => void remove(sale)}>Delete</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
