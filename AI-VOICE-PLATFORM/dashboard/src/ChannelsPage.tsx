import { useCallback, useEffect, useState } from "react";
import { ApiError, disconnectMeta, getMetaConnection } from "./api";
import type { MetaConnectionStatus } from "./api";

const CHANNELS = [
  { id: "instagram", mark: "◎", name: "Instagram", need: "Professional account linked to a Facebook Page and Instagram Graph API permissions.", tone: "instagram" },
  { id: "whatsapp_business", mark: "◔", name: "WhatsApp Business", need: "WhatsApp Cloud API account, approved templates and messaging opt-in. This is messaging, not ad placement.", tone: "whatsapp" },
  { id: "google_ads", mark: "G", name: "Google Ads", need: "OAuth consent, developer token, customer ID and an approved Google Ads API access level.", tone: "google" },
  { id: "whatsapp_ads", mark: "↗", name: "Click-to-WhatsApp Ads", need: "These are Meta Ads campaigns with a WhatsApp destination; they use Meta Marketing API and an eligible WhatsApp number.", tone: "whatsapp-ads" },
];

export function ChannelsPage() {
  const [metaConnection, setMetaConnection] = useState<MetaConnectionStatus | null>(null);
  const [metaLoading, setMetaLoading] = useState(true);
  const [metaBusy, setMetaBusy] = useState(false);
  const [metaError, setMetaError] = useState<string | null>(null);
  const [metaNotice, setMetaNotice] = useState<string | null>(null);
  const [metaNoticeError, setMetaNoticeError] = useState(false);

  const refreshMetaConnection = useCallback(async () => {
    setMetaLoading(true);
    setMetaError(null);
    try {
      setMetaConnection(await getMetaConnection());
    } catch (err) {
      setMetaError(err instanceof ApiError ? err.message : "Could not load Meta connection status");
      setMetaConnection(null);
    } finally {
      setMetaLoading(false);
    }
  }, []);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const result = params.get("meta");
    const reason = params.get("reason");
    if (result === "connected") {
      setMetaNotice("Facebook account connected successfully.");
      setMetaNoticeError(false);
    } else if (result === "error") {
      setMetaNotice(reason === "permissions"
        ? "Meta did not grant all requested permissions. Review the requested permissions and reconnect."
        : reason === "access_denied"
          ? "Facebook authorization was cancelled."
          : "Facebook could not complete the connection. Reconnect and try again.");
      setMetaNoticeError(true);
    }
    if (result) {
      params.delete("meta");
      params.delete("reason");
      const query = params.toString();
      window.history.replaceState({}, "", `${window.location.pathname}${query ? `?${query}` : ""}${window.location.hash}`);
    }
    void refreshMetaConnection();
  }, [refreshMetaConnection]);

  async function disconnectFacebook() {
    setMetaBusy(true);
    setMetaError(null);
    try {
      await disconnectMeta();
      setMetaConnection({ connected: false, pages: [], ad_accounts: [] });
      setMetaNotice("Facebook account disconnected.");
      setMetaNoticeError(false);
    } catch (err) {
      setMetaError(err instanceof Error ? err.message : "Could not disconnect Facebook");
    } finally {
      setMetaBusy(false);
    }
  }

  return (
    <div className="page-stack">
      <section className="page-intro">
        <div><p className="eyebrow">Workspace setup</p><h2>Connect your channels.</h2>
          <p>Publishing is enabled only after the server-side connector and required platform permissions are configured.</p></div>
        <span className="connector-summary"><i /> {metaConnection?.connected ? "1 connected" : "0 connected"}</span>
      </section>

      <section className="connection-banner">
        <span className="connection-icon">↗</span>
        <div><strong>Publishing connectors are not configured yet.</strong><p>The current backend can collect leads and report reach, but it does not create live Meta or Google campaigns. Drafts remain local to this browser.</p></div>
      </section>

      <section className="card meta-connection-panel">
        <div className="card-head">
          <h3>Facebook account</h3>
          <p>Connect CAMPEX to retrieve the Pages and ad accounts granted to your admin account.</p>
        </div>
        {metaNotice && <p className={`meta-notice${metaNoticeError ? " error" : ""}`} role={metaNoticeError ? "alert" : "status"}>{metaNotice}</p>}
        {metaError && <p className="banner" role="alert">{metaError}</p>}
        {metaLoading ? <p className="hint">Checking Facebook connection…</p> : metaConnection?.connected ? <>
          <div className="meta-account-summary">
            <span className="channel-mark facebook">f</span>
            <div><strong>{metaConnection.meta_user_name || "Facebook account"}</strong>
              <small>{metaConnection.expired ? "Access expired — reconnect required" : `Connected${metaConnection.token_expires_at ? ` · expires ${new Date(metaConnection.token_expires_at).toLocaleDateString()}` : ""}`}</small>
            </div>
            <div className="meta-actions">
              <button type="button" className="secondary-button" onClick={() => window.location.assign("/api/oauth/meta/login")}>Reconnect</button>
              <button type="button" className="secondary-button" disabled={metaBusy} onClick={() => void disconnectFacebook()}>{metaBusy ? "Disconnecting…" : "Disconnect"}</button>
            </div>
          </div>
          <div className="meta-assets-grid">
            <div><h4>Facebook Pages ({metaConnection.pages.length})</h4>
              {metaConnection.pages.length ? <ul>{metaConnection.pages.map((page) => <li key={page.id}><strong>{page.name}</strong><small>{page.category || page.id}</small></li>)}</ul> : <p className="hint">No Pages were returned for the granted permissions.</p>}
            </div>
            <div><h4>Ad accounts ({metaConnection.ad_accounts.length})</h4>
              {metaConnection.ad_accounts.length ? <ul>{metaConnection.ad_accounts.map((account) => <li key={account.id}><strong>{account.name}</strong><small>{account.account_id || account.id}{account.currency ? ` · ${account.currency}` : ""}</small></li>)}</ul> : <p className="hint">No ad accounts were returned for the granted permissions.</p>}
            </div>
          </div>
        </> : <div className="meta-connect-actions">
          <p className="hint">The Meta token remains encrypted in the backend and is never sent to this page.</p>
          <button type="button" className="primary-button" onClick={() => window.location.assign("/api/oauth/meta/login")}>Connect Facebook</button>
        </div>}
      </section>

      <div className="connection-grid">
        {CHANNELS.map((channel) => (
          <article className="connection-card" key={channel.id}>
            <div className="connection-card-top"><span className={`channel-mark ${channel.tone}`}>{channel.mark}</span><span className="not-connected"><i /> Not connected</span></div>
            <h3>{channel.name}</h3>
            <p>{channel.need}</p>
            <button type="button" className="secondary-button" disabled title="Provider OAuth and server-side credentials are not configured">Setup required</button>
          </article>
        ))}
      </div>

      <section className="surface-card setup-checklist">
        <p className="step-label">SAFE PUBLISHING CHECKLIST</p>
        <h3>What must be configured before live publishing</h3>
        <ol>
          <li>OAuth/token storage and account IDs in the backend secret store—not in the React app.</li>
          <li>Provider-specific creative, image hosting, ad review, spend limits and status callbacks.</li>
          <li>Consent, audience targeting, and platform policies reviewed for every campaign destination.</li>
          <li>Connector tests against a test account before enabling “Publish”.</li>
        </ol>
      </section>
    </div>
  );
}
