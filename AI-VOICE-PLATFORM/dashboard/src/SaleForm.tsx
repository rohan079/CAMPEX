import { useState } from "react";
import type { FormEvent } from "react";
import { recordSale } from "./api";
import type { Client, SaleChannel } from "./types";

export interface AdOption {
  id: string;
  name: string;
}

/**
 * Sales are entered by hand: the platform sees leads and calls, not purchases.
 * With a lead the sale inherits the lead's client and ad; otherwise a client is needed.
 */
export function SaleForm({
  clients,
  clientId,
  channel,
  lockChannel = false,
  leadId,
  ads = [],
  onSaved,
}: {
  clients: Client[];
  clientId?: string;
  channel: SaleChannel;
  lockChannel?: boolean;
  leadId?: string;
  ads?: AdOption[];
  onSaved: () => void;
}) {
  const [pickedClient, setPickedClient] = useState("");
  const [pickedChannel, setPickedChannel] = useState<SaleChannel>(channel);
  const [amount, setAmount] = useState("");
  const [adId, setAdId] = useState("");
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  const needsClient = !leadId && !clientId;
  const effectiveClient = clientId || pickedClient;

  async function submit(event: FormEvent) {
    event.preventDefault();
    const value = Number(amount);
    if (!Number.isFinite(value) || value <= 0) {
      setError("Enter the sale amount, more than zero.");
      return;
    }
    if (!leadId && !effectiveClient) {
      setError("Choose which client this sale belongs to.");
      return;
    }
    setBusy(true);
    setError(null);
    setSaved(false);
    try {
      await recordSale({
        lead_id: leadId,
        client_id: leadId ? undefined : effectiveClient,
        ad_id: !leadId && adId.trim() ? adId.trim() : undefined,
        channel: pickedChannel,
        amount: value,
        note: note.trim() || undefined,
      });
      setAmount("");
      setNote("");
      setAdId("");
      setSaved(true);
      onSaved();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not record the sale");
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="sale-form" onSubmit={(event) => void submit(event)}>
      <div className="form-grid">
        {needsClient && (
          <label className="field">
            <span>Client</span>
            <select value={pickedClient} onChange={(event) => setPickedClient(event.target.value)} required>
              <option value="">Choose a client</option>
              {clients.map((client) => (
                <option key={client.id} value={client.id}>{client.name}</option>
              ))}
            </select>
          </label>
        )}
        {!lockChannel && (
          <label className="field">
            <span>Came from</span>
            <select value={pickedChannel} onChange={(event) => setPickedChannel(event.target.value as SaleChannel)}>
              <option value="ad">Ad</option>
              <option value="calling_agent">Calling agent</option>
            </select>
          </label>
        )}
        <label className="field">
          <span>Amount</span>
          <input
            inputMode="decimal"
            value={amount}
            onChange={(event) => setAmount(event.target.value)}
            placeholder="e.g. 4500"
            required
          />
        </label>
        {!leadId && (
          <label className="field">
            <span>Ad (optional)</span>
            <input
              list="sale-ads"
              value={adId}
              onChange={(event) => setAdId(event.target.value)}
              placeholder="Ad id"
            />
            <datalist id="sale-ads">
              {ads.map((ad) => (
                <option key={ad.id} value={ad.id}>{ad.name}</option>
              ))}
            </datalist>
          </label>
        )}
        <label className="field grow">
          <span>Note (optional)</span>
          <input value={note} onChange={(event) => setNote(event.target.value)} maxLength={500} />
        </label>
        <button type="submit" className="primary" disabled={busy}>
          {busy ? "Saving…" : "Record sale"}
        </button>
      </div>
      {error && <p className="banner" role="alert">{error}</p>}
      {saved && <p className="hint saved" role="status">Sale recorded.</p>}
    </form>
  );
}
