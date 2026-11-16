const CHANNELS = [
  { id: "facebook", mark: "f", name: "Facebook Pages", need: "Meta app review, Page token, Page ID and publishing permissions.", tone: "facebook" },
  { id: "instagram", mark: "◎", name: "Instagram", need: "Professional account linked to a Facebook Page and Instagram Graph API permissions.", tone: "instagram" },
  { id: "whatsapp_business", mark: "◔", name: "WhatsApp Business", need: "WhatsApp Cloud API account, approved templates and messaging opt-in. This is messaging, not ad placement.", tone: "whatsapp" },
  { id: "google_ads", mark: "G", name: "Google Ads", need: "OAuth consent, developer token, customer ID and an approved Google Ads API access level.", tone: "google" },
  { id: "whatsapp_ads", mark: "↗", name: "Click-to-WhatsApp Ads", need: "These are Meta Ads campaigns with a WhatsApp destination; they use Meta Marketing API and an eligible WhatsApp number.", tone: "whatsapp-ads" },
];

export function ChannelsPage() {
  return (
    <div className="page-stack">
      <section className="page-intro">
        <div><p className="eyebrow">Workspace setup</p><h2>Connect your channels.</h2>
          <p>Publishing is enabled only after the server-side connector and required platform permissions are configured.</p></div>
        <span className="connector-summary"><i /> 0 connected</span>
      </section>

      <section className="connection-banner">
        <span className="connection-icon">↗</span>
        <div><strong>Publishing connectors are not configured yet.</strong><p>The current backend can collect leads and report reach, but it does not create live Meta or Google campaigns. Drafts remain local to this browser.</p></div>
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
