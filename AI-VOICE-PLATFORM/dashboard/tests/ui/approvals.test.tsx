import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { CampaignApprovalsPage } from "../../src/CampaignApprovalsPage";
import * as api from "../../src/executionApi";
import type { ExecutionDetail, ExecutionStatus, ExecutionSummary } from "../../src/executionTypes";
import { NotificationBell } from "../../src/NotificationBell";

vi.mock("../../src/executionApi", () => ({
  listExecutions: vi.fn(), createExecution: vi.fn(), getExecution: vi.fn(), updateExecution: vi.fn(),
  validateExecution: vi.fn(), submitExecution: vi.fn(), approveExecution: vi.fn(), rejectExecution: vi.fn(),
  publishExecution: vi.fn(), retryExecution: vi.fn(), reconcileExecution: vi.fn(), cancelExecution: vi.fn(), retryAd: vi.fn(), checkAd: vi.fn(),
  reviseExecution: vi.fn(), searchMetaLocations: vi.fn(),
  listNotifications: vi.fn(), markNotificationRead: vi.fn(), markAllNotificationsRead: vi.fn(),
}));
vi.mock("../../src/creativeApi", () => ({ listCreatives: vi.fn(async () => []), getCreative: vi.fn() }));
const m = vi.mocked(api);

function summary(status: ExecutionStatus, over: Partial<ExecutionSummary> = {}): ExecutionSummary {
  return {
    id: "e1", client_id: "c1", name: "Green Villas launch", objective: "leads", ad_account_id: "act_555", status,
    meta_status: null, meta_effective_status: null, meta_campaign_id: null, publish_outcome: null, last_error: null,
    config_version: 1, created_by: "asha.manager@example.com", created_at: "2026-11-20T10:00:00Z",
    updated_at: "2026-11-20T10:00:00Z", published_at: null, ...over,
  };
}

function detail(status: ExecutionStatus, allowed: ExecutionDetail["allowed_actions"], over: Partial<ExecutionSummary> = {}): ExecutionDetail {
  return {
    execution: summary(status, over),
    config: { ad_account_id: "act_555", name: "Green Villas launch", objective: "leads", platforms: ["facebook"], location: "Guntur",
      age_min: 25, age_max: 55, daily_budget_minor: 50_000, headline: "Villas near Guntur", primary_text: "Book a visit.", cta: "LEARN_MORE" },
    config_hash: "h", last_validation: null, publish_tag: "cx-abc", publish_attempts: 0, meta_adset_id: null, meta_checked_at: null,
    versions: [], approvals: [], steps: [{ step: "create", outcome: "ok", detail: null, meta_ids: null, actor: "asha.manager@example.com", at: "2026-11-20T10:00:00Z" }],
    allowed_actions: allowed, ad_status: "not_requested", ad_error: null, meta_ad_id: null, meta_video_id: null, meta_creative_id: null,
  };
}

beforeEach(() => {
  m.listExecutions.mockResolvedValue([summary("PENDING_APPROVAL"), summary("DRAFT", { id: "e2", name: "Old draft" }),
    summary("PUBLISHED_PAUSED", { id: "e3", name: "Live one", meta_effective_status: "PAUSED" })]);
});
afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe("Campaign approvals", () => {
  it("defaults to campaigns that need action and shows owners by first name", async () => {
    render(<CampaignApprovalsPage clientId="c1" />);
    expect(await screen.findByText("Green Villas launch")).toBeTruthy();
    expect(screen.queryByText("Old draft")).toBeNull();
    expect(screen.queryByText("Live one")).toBeNull();
    expect(screen.getAllByText("Asha").length).toBeGreaterThan(0);
    expect(screen.queryByText(/asha.manager@example.com/)).toBeNull();
    fireEvent.click(screen.getByRole("tab", { name: /Published/ }));
    expect(await screen.findByText("Live one")).toBeTruthy();
  });

  it("only offers actions the server allows, and approving calls the API", async () => {
    m.getExecution.mockResolvedValue(detail("PENDING_APPROVAL", ["edit", "approve", "reject", "cancel"]));
    m.approveExecution.mockResolvedValue(detail("APPROVED", ["edit", "publish", "cancel"]));
    render(<CampaignApprovalsPage clientId="c1" focusId="e1" />);
    const dialog = await screen.findByRole("dialog", { name: "Green Villas launch" });
    await within(dialog).findByText("Daily budget");
    expect(within(dialog).queryByRole("button", { name: /Publish/ })).toBeNull();
    fireEvent.click(within(dialog).getByRole("button", { name: "Approve" }));
    await waitFor(() => expect(m.approveExecution).toHaveBeenCalledWith("e1"));
    expect(await within(dialog).findByRole("button", { name: "Publish as paused…" })).toBeTruthy();
  });

  it("publishing needs an explicit acknowledgement", async () => {
    m.getExecution.mockResolvedValue(detail("APPROVED", ["edit", "publish", "cancel"]));
    m.publishExecution.mockResolvedValue(detail("PUBLISHED_PAUSED", ["reconcile"], { meta_campaign_id: "9001", meta_status: "PAUSED" }));
    render(<CampaignApprovalsPage clientId="c1" focusId="e1" />);
    const dialog = await screen.findByRole("dialog", { name: "Green Villas launch" });
    fireEvent.click(await within(dialog).findByRole("button", { name: "Publish as paused…" }));
    const confirm = within(dialog).getByRole("button", { name: "Publish paused campaign" }) as HTMLButtonElement;
    expect(confirm.disabled).toBe(true);
    expect(m.publishExecution).not.toHaveBeenCalled();
    fireEvent.click(within(dialog).getByLabelText(/I approve creating this paused campaign/));
    fireEvent.click(confirm);
    await waitFor(() => expect(m.publishExecution).toHaveBeenCalledWith("e1"));
    expect(await within(dialog).findByText(/Nothing is delivering/)).toBeTruthy();
  });

  it("requesting changes requires a note", async () => {
    m.getExecution.mockResolvedValue(detail("PENDING_APPROVAL", ["approve", "reject"]));
    m.rejectExecution.mockResolvedValue(detail("DRAFT", ["edit", "validate"]));
    render(<CampaignApprovalsPage clientId="c1" focusId="e1" />);
    const dialog = await screen.findByRole("dialog", { name: "Green Villas launch" });
    fireEvent.click(await within(dialog).findByRole("button", { name: "Request changes" }));
    const submit = within(dialog).getAllByRole("button", { name: "Request changes" }).at(-1) as HTMLButtonElement;
    expect(submit.disabled).toBe(true);
    fireEvent.change(within(dialog).getByLabelText("What needs to change?"), { target: { value: "Remove the price claim" } });
    fireEvent.click(submit);
    await waitFor(() => expect(m.rejectExecution).toHaveBeenCalledWith("e1", "Remove the price claim"));
  });

  it("surfaces an unconfirmed publish and offers reconciliation", async () => {
    m.listExecutions.mockResolvedValue([summary("EXECUTING", { publish_outcome: "unknown" })]);
    m.getExecution.mockResolvedValue(detail("EXECUTING", ["reconcile"], { publish_outcome: "unknown",
      last_error: "Meta did not confirm the result. Use Reconcile to check again; nothing will be re-created." }));
    render(<CampaignApprovalsPage clientId="c1" focusId="e1" />);
    expect(await screen.findByText("Needs reconciliation")).toBeTruthy();
    const dialog = await screen.findByRole("dialog", { name: "Green Villas launch" });
    expect(await within(dialog).findByText(/nothing will be re-created/)).toBeTruthy();
    expect(within(dialog).getByRole("button", { name: "Reconcile with Meta" })).toBeTruthy();
  });
});

describe("Ad creation status", () => {
  it("shows video processing and lets a failed ad be retried", async () => {
    m.getExecution.mockResolvedValue({ ...detail("PUBLISHED_PAUSED", ["reconcile", "ad_check"], { meta_campaign_id: "9001" }), ad_status: "processing" });
    render(<CampaignApprovalsPage clientId="c1" focusId="e1" />);
    const dialog = await screen.findByRole("dialog", { name: "Green Villas launch" });
    expect(await within(dialog).findByText("Meta is processing the video")).toBeTruthy();
    expect(within(dialog).getByRole("button", { name: "Check now" })).toBeTruthy();
    cleanup();
    m.getExecution.mockResolvedValue({ ...detail("PUBLISHED_PAUSED", ["reconcile", "ad_retry"], { meta_campaign_id: "9001" }),
      ad_status: "failed", ad_error: "Meta could not process the video. Re-export it as H.264 MP4 and upload it again." });
    m.retryAd.mockResolvedValue({ ...detail("PUBLISHED_PAUSED", ["reconcile"], { meta_campaign_id: "9001" }), ad_status: "created", meta_ad_id: "ad1" });
    render(<CampaignApprovalsPage clientId="c1" focusId="e1" />);
    const d2 = await screen.findByRole("dialog", { name: "Green Villas launch" });
    expect(await within(d2).findByText(/Re-export it as H.264 MP4/)).toBeTruthy();
    fireEvent.click(within(d2).getByRole("button", { name: "Retry ad creation" }));
    await waitFor(() => expect(m.retryAd).toHaveBeenCalledWith("e1"));
    expect(await within(d2).findByText("Ad created (paused)")).toBeTruthy();
  });
});

describe("Notification bell", () => {
  it("lists each ad in the ad set with its own status", async () => {
    m.getExecution.mockResolvedValue({ ...detail("PUBLISHED_PAUSED", ["reconcile", "ad_retry"]), ad_status: "failed", ad_error: "Ad 2: Meta rejected it",
      ads: [{ position: 1, creative_asset_id: "cr1", creative_version: 2, status: "created", error: null, meta_ad_id: "9001" },
        { position: 2, creative_asset_id: "cr2", creative_version: 2, status: "failed", error: "Meta rejected it", meta_ad_id: null }] });
    render(<CampaignApprovalsPage clientId="c1" focusId="e1" />);
    const dialog = await screen.findByRole("dialog", { name: "Green Villas launch" });
    const list = await within(dialog).findByRole("list", { name: "Ads in this ad set" });
    expect(within(list).getByText("Ad created (paused)")).toBeTruthy();
    expect(within(list).getByText("Meta rejected it")).toBeTruthy();
    expect(within(dialog).getByRole("button", { name: "Retry ad creation" })).toBeTruthy();
  });

  it("a rejected publish is revised with a location picked from Meta", async () => {
    m.getExecution.mockResolvedValue(detail("FAILED", ["retry", "reconcile", "cancel", "revise"], { publish_outcome: "rejected",
      last_error: "Meta rejected the publish: Meta could not match the target location." }));
    m.reviseExecution.mockResolvedValue(detail("DRAFT", ["edit", "validate", "cancel"]));
    m.searchMetaLocations.mockResolvedValue({ locations: [{ key: "1035921", type: "city", name: "Guntur", label: "Guntur, Andhra Pradesh, India" }] });
    m.updateExecution.mockResolvedValue(detail("DRAFT", ["edit", "validate", "cancel"]));
    render(<CampaignApprovalsPage clientId="c1" focusId="e1" />);
    const dialog = await screen.findByRole("dialog", { name: "Green Villas launch" });
    fireEvent.click(await within(dialog).findByRole("button", { name: "Edit and resubmit" }));
    await waitFor(() => expect(m.reviseExecution).toHaveBeenCalledWith("e1"));
    const input = await within(dialog).findByPlaceholderText(/Start typing a city/);
    fireEvent.change(input, { target: { value: "Gunt" } });
    fireEvent.mouseDown(await within(dialog).findByRole("option", { name: /Guntur, Andhra Pradesh, India/ }));
    expect(within(dialog).getByText(/Matched in Meta as a city/)).toBeTruthy();
    expect(m.searchMetaLocations).toHaveBeenCalledWith("c1", "Gunt");
    fireEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));
    await waitFor(() => expect(m.updateExecution).toHaveBeenCalled());
    expect(m.updateExecution.mock.calls[0][1]).toMatchObject({ location: "Guntur, Andhra Pradesh, India", location_key: "1035921", location_type: "city" });
  });

  it("shows the unread count and opens the related campaign", async () => {
    m.listNotifications.mockResolvedValue({ unread_count: 1, items: [{ id: "n1", client_id: "c1", type: "campaign_approval_requested",
      severity: "info", title: "Approval requested: Villas", body: null, entity_type: "campaign_execution", entity_id: "e1",
      created_at: "2026-11-20T10:00:00Z", read_at: null }] });
    m.markNotificationRead.mockResolvedValue({ ok: true });
    const open = vi.fn();
    render(<NotificationBell onOpenExecution={open} />);
    const bell = await screen.findByRole("button", { name: "Notifications, 1 unread" });
    fireEvent.click(bell);
    fireEvent.click(await screen.findByText("Approval requested: Villas"));
    await waitFor(() => expect(open).toHaveBeenCalledWith("e1"));
    expect(m.markNotificationRead).toHaveBeenCalledWith("n1");
  });
});
