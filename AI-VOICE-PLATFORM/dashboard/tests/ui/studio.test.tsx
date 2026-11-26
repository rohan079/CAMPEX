import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as exec from "../../src/executionApi";
import * as measurement from "../../src/measurementApi";
import * as creativeApi from "../../src/creativeApi";
import { CampaignStudio, studioIssues, withUtm } from "../../src/CampaignStudio";
import type { ExecutionDetail } from "../../src/executionTypes";

vi.mock("../../src/api", () => ({
  ApiError: class ApiError extends Error { constructor(message: string, readonly status: number) { super(message); } },
  getMetaConnection: vi.fn(async () => ({ connected: true, expired: false, ad_accounts: [{ id: "act_555", name: "Green Fields Ads", currency: "INR" }],
    pages: [{ id: "p1", name: "Green Fields Realty" }] })),
  assistCampaign: vi.fn(),
}));
vi.mock("../../src/creativeApi", () => ({ listCreatives: vi.fn(async () => []), getCreative: vi.fn(), creativeImage: vi.fn(async () => new Blob(["x"])) }));
vi.mock("../../src/intelligenceApi", () => ({ getBudgetPolicy: vi.fn(async () => ({ daily_spend_limit: 3000, target_cost_per_qualified_lead: 2000 })) }));
vi.mock("../../src/executionApi", () => ({ listExecutions: vi.fn(async () => []), createExecution: vi.fn(), getExecution: vi.fn(),
  updateExecution: vi.fn(), validateExecution: vi.fn(), submitExecution: vi.fn(), searchMetaLocations: vi.fn(async () => ({ locations: [] })) }));
vi.mock("../../src/measurementApi", () => ({ getAdAccounts: vi.fn(), setAdAccounts: vi.fn() }));
const m = vi.mocked(exec);
const ma = vi.mocked(measurement);
const mc = vi.mocked(creativeApi);

const client = { id: "c1", name: "Green Fields" } as Parameters<typeof CampaignStudio>[0]["client"];

function detail(status: ExecutionDetail["execution"]["status"]): ExecutionDetail {
  return { execution: { id: "e1", status, config_version: 1 } as ExecutionDetail["execution"] } as ExecutionDetail;
}

const base = { name: "Villas", objective: "leads" as const, adAccountId: "act_555", pageId: "p1", location: "Guntur", locationKey: "1035921", locationType: "city" as const, ageMin: 25, ageMax: 55,
  placements: ["facebook" as const], language: "en" as const, dailyBudget: "1000", headline: "Villas near Guntur", body: "Book a visit.",
  description: "", cta: "LEARN_MORE", url: "", utm: true, creativeId: "cr1", creativeVersion: 1, creativeImageId: null, creativeVideoId: null, extraAds: [] };

beforeEach(() => {
  m.listExecutions.mockResolvedValue([]);
  ma.getAdAccounts.mockResolvedValue({ ad_account_ids: ["act_555"] });
});
afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe("studio rules", () => {
  it("passes a complete lead campaign and flags policy and URL problems", () => {
    expect(studioIssues(base, null, null).filter((i) => i.level === "error")).toEqual([]);
    const over = studioIssues({ ...base, dailyBudget: "5000" }, { daily_spend_limit: 3000 } as never, null);
    expect(over.some((i) => i.step === "budget" && i.level === "error")).toBe(true);
    const traffic = studioIssues({ ...base, objective: "traffic" }, null, null);
    expect(traffic.some((i) => i.text.includes("destination URL"))).toBe(true);
    expect(studioIssues({ ...base, url: "http://x.com" }, null, null).some((i) => i.text.includes("https"))).toBe(true);
    expect(studioIssues({ ...base, locationKey: null }, null, null).some((i) => i.step === "audience" && i.level === "warning")).toBe(true);
    expect(studioIssues({ ...base, creativeId: null }, null, null).some((i) => i.level === "warning" && i.text.includes("creative review"))).toBe(true);
  });

  it("adds UTM tags without overwriting existing ones", () => {
    expect(withUtm("https://greenfields.example/villas", "Green Fields | Leads")).toBe(
      "https://greenfields.example/villas?utm_source=meta&utm_medium=paid_social&utm_campaign=green-fields-leads");
    expect(withUtm("https://x.example/?utm_source=fb", "A")).toContain("utm_source=fb");
  });
});

describe("Campaign studio", () => {
  it("links an unlinked ad account to the client from the Goal step", async () => {
    ma.getAdAccounts.mockResolvedValue({ ad_account_ids: ["act_111"] });
    ma.setAdAccounts.mockResolvedValue({ ad_account_ids: ["act_111", "act_555"] });
    render(<CampaignStudio client={client} onOpenChannels={() => undefined} onOpenApprovals={() => undefined} />);
    const link = await screen.findByRole("button", { name: "Link to Green Fields" });
    fireEvent.click(link);
    await waitFor(() => expect(ma.setAdAccounts).toHaveBeenCalledWith("c1", ["act_111", "act_555"]));
    expect(await screen.findByText(/Green Fields Ads is now linked to Green Fields/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Link to Green Fields" })).toBeNull();
  });

  it("walks the steps, forecasts budget against policy and saves a server draft", async () => {
    m.createExecution.mockResolvedValue(detail("DRAFT"));
    render(<CampaignStudio client={client} onOpenChannels={() => {}} />);
    fireEvent.change(screen.getByLabelText("Campaign name"), { target: { value: "Villas | Leads" } });
    fireEvent.click(screen.getByRole("radio", { name: /Traffic/ }));
    expect(screen.getByText("Link clicks")).toBeTruthy();
    const steps = screen.getByRole("navigation", { name: "Campaign steps" });
    fireEvent.click(within(steps).getByRole("button", { name: /Budget/ }));
    expect(await screen.findByText("₹30,000")).toBeTruthy();
    expect(screen.getByText(/≈ 15 \/ month/)).toBeTruthy();
    await waitFor(() => expect((screen.getByRole("button", { name: "Save draft" }) as HTMLButtonElement).disabled).toBe(false));
    fireEvent.click(screen.getByRole("button", { name: "Save draft" }));
    await waitFor(() => expect(m.createExecution).toHaveBeenCalledWith("c1", expect.objectContaining({ name: "Villas | Leads", objective: "traffic",
      ad_account_id: "act_555", page_id: "p1", daily_budget_minor: 100000 }), expect.any(String)));
    expect(m.submitExecution).not.toHaveBeenCalled();
    expect(await screen.findByText(/Draft saved in Campex/)).toBeTruthy();
  });

  it("adds more approved creatives as extra ads in the same ad set", async () => {
    const summary = (id: string, name: string) => ({ id, client_id: "c1", name, ad_type: "villa", status: "APPROVED" as const, current_version: 2,
      approved_version: 2, image_id: `img-${id}`, headline: `${name} headline`, created_by: "a@x.test", updated_at: "2026-11-20T10:00:00Z",
      media_type: "image" as const, video_id: null, video_duration_ms: null });
    mc.listCreatives.mockResolvedValue([summary("cr1", "Main villa"), summary("cr2", "Lawn photo"), summary("cr3", "Pool photo")]);
    mc.getCreative.mockResolvedValue({ asset: summary("cr1", "Main villa"), approvals: [], allowed_actions: [],
      versions: [{ version: 2, image_id: "img-cr1", video_id: null, headline: "Villas near Guntur", primary_text: "Book a visit.", description: null, cta: "LEARN_MORE" }] } as never);
    m.createExecution.mockResolvedValue(detail("DRAFT"));
    render(<CampaignStudio client={client} onOpenChannels={() => {}} />);
    fireEvent.change(screen.getByLabelText("Campaign name"), { target: { value: "Villas" } });
    const steps = screen.getByRole("navigation", { name: "Campaign steps" });
    fireEvent.click(within(steps).getByRole("button", { name: /^4/ }));
    fireEvent.change(await screen.findByLabelText(/^Approved creative/), { target: { value: "cr1" } });
    const add = await screen.findByLabelText("Add another ad");
    expect(within(add).queryByRole("option", { name: /Main villa/ })).toBeNull();
    fireEvent.change(add, { target: { value: "cr2" } });
    expect(await screen.findByText("Ad 2")).toBeTruthy();
    expect(screen.getByText(/2 of 6/)).toBeTruthy();
    await waitFor(() => expect((screen.getByRole("button", { name: "Save draft" }) as HTMLButtonElement).disabled).toBe(false));
    fireEvent.click(screen.getByRole("button", { name: "Save draft" }));
    await waitFor(() => expect(m.createExecution).toHaveBeenCalledWith("c1", expect.objectContaining({
      creative_asset_id: "cr1", extra_creatives: [{ asset_id: "cr2", version: 2 }] }), expect.any(String)));
  });

  it("blocks sending until required items are done, then validates and submits", async () => {
    m.createExecution.mockResolvedValue(detail("DRAFT"));
    m.validateExecution.mockResolvedValue({ valid: true, errors: [], warnings: [] });
    m.submitExecution.mockResolvedValue(detail("PENDING_APPROVAL"));
    render(<CampaignStudio client={client} onOpenChannels={() => {}} />);
    const send = () => screen.getAllByRole("button", { name: "Send for approval" })[0] as HTMLButtonElement;
    await waitFor(() => expect(send().disabled).toBe(true));
    fireEvent.change(screen.getByLabelText("Campaign name"), { target: { value: "Villas" } });
    const steps = screen.getByRole("navigation", { name: "Campaign steps" });
    fireEvent.click(within(steps).getByRole("button", { name: /Audience/ }));
    fireEvent.change(screen.getByLabelText(/^Location/), { target: { value: "Guntur" } });
    fireEvent.click(within(steps).getByRole("button", { name: /^4/ }));
    fireEvent.change(screen.getByLabelText(/^Headline/), { target: { value: "Villas near Guntur" } });
    fireEvent.change(screen.getByLabelText(/^Primary text/), { target: { value: "Book a site visit this weekend." } });
    await waitFor(() => expect(send().disabled).toBe(false));
    fireEvent.click(send());
    await waitFor(() => expect(m.submitExecution).toHaveBeenCalledWith("e1"));
    expect(await screen.findByText(/Sent for approval/)).toBeTruthy();
  });
});
