import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { CrmLeadDrawer } from "../../src/CrmLeadDrawer";
import { CrmPipelinePage } from "../../src/CrmPipelinePage";
import * as api from "../../src/measurementApi";
import type { CrmLeadDetail, Pipeline } from "../../src/measurementTypes";

vi.mock("../../src/measurementApi", async () => (await import("./apiMock")).mockMeasurementApi());
const m = vi.mocked(api);

const pipeline: Pipeline = {
  total: 3,
  columns: ["NEW", "CONTACTED", "QUALIFIED", "SITE_VISIT_SCHEDULED", "SITE_VISIT_COMPLETED", "NEGOTIATION", "WON", "LOST"].map((status) => ({
    status: status as Pipeline["columns"][number]["status"],
    count: status === "NEW" ? 2 : status === "QUALIFIED" ? 1 : 0,
    leads: status === "NEW" ? [
      { id: "l1", name: "Ravi", phone_number: "+919876543210", pipeline_status: "NEW", review_status: "pending", source: "ctwa",
        attribution_source: "ctwa", campaign_label: "Diwali villas", assigned_agent_id: null, assigned_agent_name: null, project_id: null,
        qualification_score: 80, next_follow_up_at: null, created_at: "2026-11-20T10:00:00Z" },
      { id: "l2", name: "Sita", phone_number: "+919876543211", pipeline_status: "NEW", review_status: "rejected", source: "manual",
        attribution_source: "manual", campaign_label: null, assigned_agent_id: "a1", assigned_agent_name: "Asha", project_id: null,
        qualification_score: null, next_follow_up_at: "2020-01-01T00:00:00Z", created_at: "2026-11-20T10:00:00Z" },
    ] : status === "QUALIFIED" ? [
      { id: "l3", name: "Kiran", phone_number: "+919876543212", pipeline_status: "QUALIFIED", review_status: "accepted", source: "website",
        attribution_source: "website", campaign_label: "Plots", assigned_agent_id: "a1", assigned_agent_name: "Asha", project_id: null,
        qualification_score: 65, next_follow_up_at: null, created_at: "2026-11-20T10:00:00Z" },
    ] : [],
  })),
};

const detail: CrmLeadDetail = {
  id: "l1", client_id: "c1", name: "Ravi", phone_number: "+919876543210", email: null, source: "ctwa", review_status: "pending",
  pipeline_status: "NEW", allowed_next_statuses: ["CONTACTED", "QUALIFIED", "SITE_VISIT_SCHEDULED", "LOST"], lost_reason: null,
  assigned_agent_id: null, assigned_agent_name: null, project_id: null, created_at: "2026-11-20T10:00:00Z", response_time_minutes: null,
  qualification: { budget_min: 6000000, budget_max: 8000000, preferred_location: "Guntur", property_type: null, purchase_timeline: "IMMEDIATE",
    bedrooms: 3, financing_preference: "HOME_LOAN", purpose: "RESIDENTIAL", genuine_interest: true, qualification_score: 80,
    score_breakdown: [
      { factor: "budget_matches_project", label: "Budget matches project pricing", weight: 25, points: 25, matched: true, reason: "Budget overlaps project price range" },
      { factor: "financing_ready", label: "Financing is arranged", weight: 10, points: 5, matched: false, reason: "Financing: home loan" },
    ], qualification_reason: null },
  follow_ups: [], status_history: [{ from_status: null, to_status: "NEW", reason: null, changed_by: "x", changed_at: "2026-11-20T10:00:00Z" }],
  duplicates: [{ id: "l9", name: "Ravi K", phone_number: "9876543210", pipeline_status: "LOST" }], tracking_consent: "unknown",
};

const agents = [{ id: "a1", name: "Asha", email: null, phone_number: null, active: true, open_leads: 2 }];

afterEach(cleanup);

beforeEach(() => {
  m.getPipeline.mockResolvedValue(pipeline);
  m.listAgents.mockResolvedValue(agents);
  m.listProjects.mockResolvedValue([]);
  m.getCrmLead.mockResolvedValue(detail);
  m.getLeadAttribution.mockResolvedValue({ lead_id: "l1", attribution_source: "ctwa", touch_count: 2,
    first_touch: { id: "t1", source: "ctwa", resolution_method: "meta_referral", campaign_id: null, campaign_name: null, adset_id: null,
      ad_id: "120200009", ad_name: null, utm_source: null, utm_campaign: null, fbclid_present: false, fbc_present: false,
      landing_page: null, touched_at: "2026-11-01T10:00:00Z" },
    last_touch: null, history: [] });
  m.listActivities.mockResolvedValue([{ id: "x1", activity_type: "call", body: "Spoke about villas", actor_email: "asha@a.test", occurred_at: "2026-11-20T10:00:00Z" }]);
  m.changeLeadStatus.mockResolvedValue({ ...detail, pipeline_status: "CONTACTED" });
  m.assignLead.mockResolvedValue({ ...detail, assigned_agent_id: "a1" });
  m.addActivity.mockResolvedValue({ id: "x2", activity_type: "note", body: "n", actor_email: null, occurred_at: "" });
});

describe("CRM Kanban", () => {
  it("renders every stage with counts and lead cards", async () => {
    render(<CrmPipelinePage clientId="c1" days={30} />);
    const board = await screen.findByLabelText("Pipeline board");
    await within(board).findByText("Ravi");
    expect(within(board).getAllByRole("region")).toHaveLength(8);
    const newColumn = within(board).getByRole("region", { name: "New" });
    expect(within(newColumn).getByText("2")).toBeTruthy();
    expect(within(newColumn).getByText("Diwali villas")).toBeTruthy();
    expect(within(newColumn).getByText(/rejected in review/)).toBeTruthy();
    expect(within(board).getByRole("region", { name: "Qualified" }).textContent).toContain("Kiran");
  });

  it("searches and filters by agent", async () => {
    render(<CrmPipelinePage clientId="c1" days={30} />);
    await screen.findAllByText("Ravi");
    fireEvent.change(screen.getByLabelText("Search leads"), { target: { value: "98765" } });
    await waitFor(() => expect(m.getPipeline).toHaveBeenLastCalledWith("c1", expect.objectContaining({ q: "98765" })));
    fireEvent.change(screen.getByLabelText("Agent filter"), { target: { value: "a1" } });
    await waitFor(() => expect(m.getPipeline).toHaveBeenLastCalledWith("c1", expect.objectContaining({ agent_id: "a1" })));
  });

  it("opens the lead drawer from a card", async () => {
    render(<CrmPipelinePage clientId="c1" days={30} />);
    fireEvent.click(await screen.findByRole("button", { name: "Open Ravi" }));
    expect(await screen.findByRole("dialog")).toBeTruthy();
    await waitFor(() => expect(m.getCrmLead).toHaveBeenCalledWith("l1"));
  });
});

describe("Lead detail drawer", () => {
  it("moves the stage, assigns an agent and shows attribution and duplicates", async () => {
    const changed = vi.fn();
    render(<CrmLeadDrawer leadId="l1" agents={agents} projects={[]} onClose={() => {}} onChanged={changed} />);
    expect(await screen.findByText(/ad 120200009/)).toBeTruthy();
    expect(screen.getByText(/Ravi K/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Contacted" }));
    await waitFor(() => expect(m.changeLeadStatus).toHaveBeenCalledWith("l1", "CONTACTED", { lost_reason: undefined }));
    fireEvent.change(screen.getByLabelText("Assign agent"), { target: { value: "a1" } });
    await waitFor(() => expect(m.assignLead).toHaveBeenCalledWith("l1", "a1"));
    expect(changed).toHaveBeenCalled();
  });

  it("requires a reason to mark a lead lost", async () => {
    vi.spyOn(window, "prompt").mockReturnValue("");
    render(<CrmLeadDrawer leadId="l1" agents={agents} projects={[]} onClose={() => {}} onChanged={() => {}} />);
    fireEvent.click(await screen.findByRole("button", { name: "Lost" }));
    expect(m.changeLeadStatus).not.toHaveBeenCalled();
  });

  it("explains the qualification score and logs activity", async () => {
    render(<CrmLeadDrawer leadId="l1" agents={agents} projects={[]} onClose={() => {}} onChanged={() => {}} />);
    fireEvent.click(await screen.findByRole("tab", { name: "Qualification" }));
    expect(screen.getByText("80")).toBeTruthy();
    expect(screen.getByText(/25\/25 · Budget matches project pricing/)).toBeTruthy();
    expect(screen.getByText(/5\/10 · Financing is arranged — Financing: home loan/)).toBeTruthy();
    fireEvent.click(screen.getByRole("tab", { name: "Activity" }));
    expect(screen.getByText("Spoke about villas")).toBeTruthy();
    fireEvent.change(screen.getByLabelText("Activity text"), { target: { value: "Sent brochure" } });
    fireEvent.submit(screen.getByRole("form", { name: "Add activity" }));
    await waitFor(() => expect(m.addActivity).toHaveBeenCalledWith("l1", "note", "Sent brochure"));
  });
});
