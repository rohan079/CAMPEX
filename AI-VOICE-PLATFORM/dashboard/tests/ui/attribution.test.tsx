import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AttributionPage } from "../../src/AttributionPage";
import * as api from "../../src/measurementApi";

vi.mock("../../src/measurementApi", async () => (await import("./apiMock")).mockMeasurementApi());
const m = vi.mocked(api);

afterEach(cleanup);

describe("Attribution filters", () => {
  beforeEach(() => {
    m.getAttributionSummary.mockResolvedValue({ total_leads: 20, attributed_leads: 15, unknown_leads: 5, unknown_percentage: 25,
      first_last_differ: 3, by_source: [], by_method: [] });
    m.getCampaignReport.mockResolvedValue({ model: "first_touch", group_by: "campaign", currency: "INR", meta_spend_available: false,
      meta_spend_note: "No Meta ad accounts are mapped to this client", rows: [
        { key: "1202", campaign_id: "1202", label: "Diwali villas", leads: 15, qualified_leads: 6, site_visits_scheduled: 4,
          site_visits_completed: 3, bookings: 1, booking_value: 7500000, confirmed_revenue: 1500000, amount_collected: 1500000,
          meta_spend: null, meta_impressions: null, cost_per_lead: null, cost_per_qualified_lead: null, cost_per_site_visit: null,
          cost_per_booking: null, roas: null, roi: null },
        { key: "unknown", campaign_id: null, label: "Unknown / direct", leads: 5, qualified_leads: 0, site_visits_scheduled: 0,
          site_visits_completed: 0, bookings: 0, booking_value: 0, confirmed_revenue: 0, amount_collected: 0, meta_spend: null,
          meta_impressions: null, cost_per_lead: null, cost_per_qualified_lead: null, cost_per_site_visit: null, cost_per_booking: null,
          roas: null, roi: null },
      ] });
  });

  it("shows unknown attribution share and never fakes spend", async () => {
    render(<AttributionPage clientId="c1" days={30} />);
    expect(await screen.findByText("25%")).toBeTruthy();
    expect(screen.getAllByText("Diwali villas").length).toBeGreaterThan(0);
    expect(screen.getByText(/Meta spend unavailable/)).toBeTruthy();
    expect(screen.getAllByText("—").length).toBeGreaterThan(0);
  });

  it("switches model and grouping", async () => {
    render(<AttributionPage clientId="c1" days={30} />);
    await screen.findByText("25%");
    fireEvent.click(screen.getByRole("tab", { name: "Last touch" }));
    await waitFor(() => expect(m.getCampaignReport).toHaveBeenLastCalledWith("c1", expect.objectContaining({ model: "last_touch" })));
    fireEvent.change(screen.getByLabelText("Group by"), { target: { value: "ad" } });
    await waitFor(() => expect(m.getCampaignReport).toHaveBeenLastCalledWith("c1", expect.objectContaining({ model: "last_touch", group_by: "ad" })));
  });

  it("asks for a client before showing tenant data", () => {
    render(<AttributionPage days={30} />);
    expect(screen.getByText(/Choose a client/)).toBeTruthy();
    expect(m.getAttributionSummary).not.toHaveBeenCalled();
  });
});
