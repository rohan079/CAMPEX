import { useCallback, useEffect, useState } from "react";
import { getLeadReviewStats, getLeads, updateLeadReview } from "./api";
import { formatDay, num } from "./format";
import { LanguagePill, StatusPill } from "./pills";
import type { Lead, LeadReviewStats, LeadReviewStatus } from "./types";
import { maskPhone, safeName } from "./privacy";

const PAGE_SIZE = 25;
type Filter = "all" | LeadReviewStatus;

export function LeadReviewPage({ clientId }: { clientId?: string }) {
  const [stats, setStats] = useState<LeadReviewStats>({ pending: 0, accepted: 0, rejected: 0 });
  const [leads, setLeads] = useState<Lead[]>([]);
  const [total, setTotal] = useState(0);
  const [filter, setFilter] = useState<Filter>("pending");
  const [query, setQuery] = useState("");
  const [debouncedQuery, setDebouncedQuery] = useState("");
  const [offset, setOffset] = useState(0);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);

  useEffect(() => {
    const timer = window.setTimeout(() => setDebouncedQuery(query.trim()), 250);
    return () => window.clearTimeout(timer);
  }, [query]);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [page, nextStats] = await Promise.all([
        getLeads({
          clientId,
          reviewStatus: filter === "all" ? "" : filter,
          q: debouncedQuery,
          limit: PAGE_SIZE,
          offset,
        }),
        getLeadReviewStats(clientId),
      ]);
      setLeads(page.leads);
      setTotal(page.total);
      setStats(nextStats);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not load lead reviews");
    } finally {
      setLoading(false);
    }
  }, [clientId, filter, debouncedQuery, offset, refreshKey]);

  useEffect(() => { void load(); }, [load]);

  async function decide(lead: Lead, decision: LeadReviewStatus) {
    if (decision === "rejected" && !window.confirm(`Reject ${safeName(lead.first_name, lead.phone_number)}? This cancels queued calls; it does not delete the lead.`)) return;
    setBusyId(lead.id);
    setError(null);
    setNotice(null);
    try {
      await updateLeadReview(lead.id, decision);
      setNotice(`${safeName(lead.first_name, lead.phone_number)} marked ${decision}.`);
      setRefreshKey((value) => value + 1);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not update the lead review");
    } finally {
      setBusyId(null);
    }
  }

  function chooseFilter(value: Filter) {
    setFilter(value);
    setOffset(0);
  }

  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const currentPage = Math.floor(offset / PAGE_SIZE) + 1;

  return (
    <div className="page-stack">
      <section className="page-intro">
        <div><p className="eyebrow">Lead operations</p><h2>Review every lead.</h2>
          <p>Accept qualified leads or reject them from future follow-up. Consent remains a separate requirement.</p></div>
        <span className="review-total"><strong>{num(total)}</strong><small>{filter === "all" ? "matching leads" : `${filter} leads`}</small></span>
      </section>

      <section className="review-stats-grid" aria-label="Lead decisions">
        <ReviewCount label="Pending review" value={stats.pending} tone="pending" active={filter === "pending"} onClick={() => chooseFilter("pending")} />
        <ReviewCount label="Accepted" value={stats.accepted} tone="accepted" active={filter === "accepted"} onClick={() => chooseFilter("accepted")} />
        <ReviewCount label="Rejected" value={stats.rejected} tone="rejected" active={filter === "rejected"} onClick={() => chooseFilter("rejected")} />
      </section>

      <section className="surface-card review-table-card">
        <div className="review-toolbar">
          <div className="review-filters" role="group" aria-label="Review filter">
            {(["all", "pending", "accepted", "rejected"] as Filter[]).map((value) => (
              <button type="button" key={value} aria-pressed={filter === value} onClick={() => chooseFilter(value)}>
                {value === "all" ? "All leads" : value[0].toUpperCase() + value.slice(1)}
              </button>
            ))}
          </div>
          <label className="search-box"><span className="search-icon">⌕</span><input value={query} onChange={(event) => { setQuery(event.target.value); setOffset(0); }} placeholder="Search name or phone" /></label>
        </div>

        {error && <p className="notice error" role="alert">{error}</p>}
        {notice && <p className="notice success" role="status">{notice}</p>}
        {loading ? <p className="empty-state">Loading lead reviews…</p> : leads.length === 0 ? (
          <div className="empty-state large"><span>✓</span><strong>Nothing to review</strong><p>Try another filter or wait for the next lead.</p></div>
        ) : (
          <div className="table-wrap"><table className="review-table"><thead><tr>
            <th>Lead</th><th>Campaign</th><th>Language</th><th>Lead status</th><th>Decision</th><th>Added</th><th>Actions</th>
          </tr></thead><tbody>
            {leads.map((lead) => {
              const review = lead.review_status ?? "pending";
              return <tr key={lead.id}>
                <td><strong>{safeName(lead.first_name, lead.phone_number)}</strong><small className="masked">{maskPhone(lead.phone_number)}</small></td>
                <td><strong>{lead.ad_headline || lead.campaign_id || "Organic / WhatsApp"}</strong><small>{lead.source.replaceAll("_", " ")}</small></td>
                <td><LanguagePill code={lead.preferred_language} /></td>
                <td><StatusPill status={lead.status} /></td>
                <td><span className={`decision-pill ${review}`}>{review}</span></td>
                <td>{formatDay(lead.created_at)}</td>
                <td><div className="decision-actions">
                  <button type="button" className="accept-button" disabled={busyId === lead.id || review === "accepted"} onClick={() => void decide(lead, "accepted")}>{busyId === lead.id ? "Saving…" : "Accept"}</button>
                  <button type="button" className="reject-button" disabled={busyId === lead.id || review === "rejected"} onClick={() => void decide(lead, "rejected")}>Reject</button>
                </div></td>
              </tr>;
            })}
          </tbody></table></div>
        )}

        <div className="pagination-bar"><span>{total ? `${offset + 1}–${Math.min(offset + leads.length, total)} of ${num(total)}` : "0 leads"}</span>
          <div><button type="button" disabled={offset === 0 || loading} onClick={() => setOffset(Math.max(0, offset - PAGE_SIZE))}>Previous</button>
            <span>Page {currentPage} of {pages}</span>
            <button type="button" disabled={offset + PAGE_SIZE >= total || loading} onClick={() => setOffset(offset + PAGE_SIZE)}>Next</button></div>
        </div>
      </section>

      <p className="consent-note"><span>i</span><span><strong>Consent is separate from lead acceptance.</strong> Accepting a lead never grants phone-call permission. The call scheduler still requires a stored `CALL` consent.</span></p>
    </div>
  );
}

function ReviewCount({ label, value, tone, active, onClick }: { label: string; value: number; tone: string; active: boolean; onClick: () => void }) {
  return <button type="button" className={`review-count ${tone}${active ? " active" : ""}`} onClick={onClick}>
    <span>{label}</span><strong>{num(value)}</strong><small>View leads <b>↗</b></small>
  </button>;
}
