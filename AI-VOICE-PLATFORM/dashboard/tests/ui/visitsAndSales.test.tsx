import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ScheduleVisit } from "../../src/CrmLeadDrawer";
import * as api from "../../src/measurementApi";
import { RecordSaleForm, SalePanel } from "../../src/SalesAttributionPage";
import { VisitPanel } from "../../src/SiteVisitsPage";
import type { SaleRecord, SiteVisit } from "../../src/measurementTypes";

vi.mock("../../src/measurementApi", async () => (await import("./apiMock")).mockMeasurementApi());
const m = vi.mocked(api);

const visit: SiteVisit = {
  id: "v1", lead_id: "l1", lead_name: "Ravi", lead_phone: "+91", project_id: null, project_name: "Green Villas", agent_id: "a1",
  agent_name: "Asha", scheduled_at: "2026-11-21T05:30:00Z", duration_minutes: 60, status: "SCHEDULED", customer_confirmed: false,
  completed_at: null, outcome: null, follow_up_notes: null, cancellation_reason: null, rescheduled_from_id: null,
};

const sale: SaleRecord = {
  id: "s1", lead_id: "l1", lead_name: "Ravi", campaign_id: "1202", adset_id: null, ad_id: "9", project_id: null, project_name: null,
  agent_id: null, agent_name: null, channel: "sales_team", status: "BOOKED", booking_value: 7500000, amount_collected: 500000,
  outstanding_amount: 7000000, refunded_amount: 0, confirmed_revenue: 0, booking_date: "2026-11-20", sale_confirmed_at: null,
  cancellation_reason: null, note: null, legacy_record: false,
  history: [{ action: "created", from_status: null, to_status: "BOOKED", changes: null, reason: null, actor_email: "asha@a.test", changed_at: "2026-11-20T10:00:00Z" }],
};

afterEach(cleanup);
beforeEach(() => {
  m.createSiteVisit.mockResolvedValue(visit);
  m.updateSiteVisitStatus.mockResolvedValue({ ...visit, status: "COMPLETED" });
  m.rescheduleSiteVisit.mockResolvedValue({ ...visit, id: "v2", rescheduled_from_id: "v1" });
  m.createClientSale.mockResolvedValue(sale);
  m.updateSale.mockResolvedValue(sale);
  m.confirmSale.mockResolvedValue({ ...sale, status: "PARTIALLY_PAID", confirmed_revenue: 500000 });
  m.cancelSale.mockResolvedValue({ ...sale, status: "CANCELLED" });
});

describe("Site-visit scheduling", () => {
  it("schedules a visit with an ISO time", async () => {
    const run = vi.fn(async (action: () => Promise<unknown>) => { await action(); });
    render(<ScheduleVisit leadId="l1" agents={[{ id: "a1", name: "Asha", email: null, phone_number: null, active: true, open_leads: 0 }]} projects={[]} onRun={run} />);
    fireEvent.change(screen.getByLabelText("Visit time"), { target: { value: "2026-11-25T11:00" } });
    fireEvent.change(screen.getByLabelText("Visit agent"), { target: { value: "a1" } });
    fireEvent.submit(screen.getByRole("form", { name: "Schedule site visit" }));
    await waitFor(() => expect(m.createSiteVisit).toHaveBeenCalled());
    const [leadId, body] = m.createSiteVisit.mock.calls[0];
    expect(leadId).toBe("l1");
    expect(body.agent_id).toBe("a1");
    expect(body.scheduled_at).toBe(new Date("2026-11-25T11:00").toISOString());
  });

  it("records the outcome, reschedules and only cancels with a reason", async () => {
    const changed = vi.fn();
    render(<VisitPanel visit={visit} onClose={() => {}} onChanged={changed} onError={() => {}} />);
    fireEvent.change(screen.getByLabelText("Outcome"), { target: { value: "negotiation" } });
    fireEvent.change(screen.getByLabelText("Visit notes"), { target: { value: "Liked corner plot" } });
    fireEvent.submit(screen.getByRole("form", { name: "Visit outcome" }));
    await waitFor(() => expect(m.updateSiteVisitStatus).toHaveBeenCalledWith("v1",
      { status: "COMPLETED", outcome: "negotiation", follow_up_notes: "Liked corner plot" }));

    fireEvent.submit(screen.getByRole("form", { name: "Cancel visit" }));
    expect(m.updateSiteVisitStatus).toHaveBeenCalledTimes(1);
    fireEvent.change(screen.getByLabelText("Cancellation reason"), { target: { value: "Customer travelling" } });
    fireEvent.submit(screen.getByRole("form", { name: "Cancel visit" }));
    await waitFor(() => expect(m.updateSiteVisitStatus).toHaveBeenLastCalledWith("v1", { status: "CANCELLED", cancellation_reason: "Customer travelling" }));

    fireEvent.change(screen.getByLabelText("New time"), { target: { value: "2026-11-28T10:00" } });
    fireEvent.submit(screen.getByRole("form", { name: "Reschedule" }));
    await waitFor(() => expect(m.rescheduleSiteVisit).toHaveBeenCalled());
  });
});

describe("Sale entry and update", () => {
  it("records a sale with numeric amounts", async () => {
    const saved = vi.fn();
    render(<RecordSaleForm clientId="c1" agents={[]} projects={[]} onSaved={saved} onError={() => {}} />);
    fireEvent.change(screen.getByLabelText(/Lead id/), { target: { value: "l1" } });
    fireEvent.change(screen.getByLabelText("Booking value"), { target: { value: "7500000" } });
    fireEvent.change(screen.getByLabelText("Amount collected"), { target: { value: "500000" } });
    fireEvent.submit(screen.getByRole("form", { name: "Record sale" }));
    await waitFor(() => expect(m.createClientSale).toHaveBeenCalledWith("c1", expect.objectContaining({
      lead_id: "l1", booking_value: 7500000, amount_collected: 500000, status: "BOOKED", channel: "sales_team",
    })));
    expect(saved).toHaveBeenCalledWith(sale);
  });

  it("updates payment, confirms revenue, cancels with a reason and shows the audit trail", async () => {
    const changed = vi.fn();
    render(<SalePanel sale={sale} currency="INR" onClose={() => {}} onChanged={changed} onError={() => {}} />);
    expect(screen.getByText(/not confirmed/)).toBeTruthy();
    expect(screen.getByText("Created")).toBeTruthy();
    fireEvent.change(screen.getByLabelText("Sale status"), { target: { value: "PARTIALLY_PAID" } });
    fireEvent.change(screen.getByLabelText("Amount collected"), { target: { value: "1500000" } });
    fireEvent.submit(screen.getByRole("form", { name: "Update sale" }));
    await waitFor(() => expect(m.updateSale).toHaveBeenCalledWith("s1", expect.objectContaining({ status: "PARTIALLY_PAID", amount_collected: 1500000 })));
    fireEvent.click(screen.getByRole("button", { name: "Confirm revenue" }));
    await waitFor(() => expect(m.confirmSale).toHaveBeenCalledWith("s1", 1500000));
    fireEvent.submit(screen.getByRole("form", { name: "Cancel sale" }));
    expect(m.cancelSale).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText("Cancellation reason"), { target: { value: "Loan rejected" } });
    fireEvent.submit(screen.getByRole("form", { name: "Cancel sale" }));
    await waitFor(() => expect(m.cancelSale).toHaveBeenCalledWith("s1", "Loan rejected"));
  });
});
