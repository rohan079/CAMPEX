import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as api from "../../src/actionApi";
import type { CampaignAction } from "../../src/actionApi";
import { OptimizationActionsPage } from "../../src/OptimizationActionsPage";

vi.mock("../../src/actionApi", () => ({
  previewAction: vi.fn(), listActions: vi.fn(), getAction: vi.fn(), approveAction: vi.fn(), rejectAction: vi.fn(),
  cancelAction: vi.fn(), executeAction: vi.fn(),
}));
const m = vi.mocked(api);

function action(over: Partial<CampaignAction> = {}): CampaignAction {
  return {
    id: "a1", client_id: "c1", recommendation_id: "r1", source: "recommendation", action_type: "set_daily_budget", level: "adset",
    object_id: "120200001", object_name: "Villas · audience", ad_account_id: "act_555", status: "APPROVED",
    before_state: { status: "ACTIVE", daily_budget: "35000" }, proposed_after: { daily_budget: 38500 }, actual_after: null,
    verification: null, guardrails: [{ code: "spend", severity: "warning", message: "Spending can rise by up to 35 per day." }],
    reason: "From recommendation: Increase budget", attempts: 0, last_error: null, created_by: "asha.manager@example.com",
    approved_by: "asha.manager@example.com", approved_at: "2026-11-20T10:00:00Z", approval_note: null, executed_by: null,
    executed_at: null, verified_at: null, expires_at: "2026-11-21T10:00:00Z", created_at: "2026-11-20T09:00:00Z",
    events: [], allowed_actions: ["execute", "cancel"], ...over,
  };
}

beforeEach(() => { m.listActions.mockResolvedValue([action()]); });
afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe("Optimization actions", () => {
  it("lists open changes with a readable summary", async () => {
    render(<OptimizationActionsPage clientId="c1" />);
    expect(await screen.findByText("Villas · audience")).toBeTruthy();
    expect(screen.getByText("Daily budget ₹350 → ₹385")).toBeTruthy();
    expect(screen.getByText("Recommendation")).toBeTruthy();
  });

  it("shows before, proposed and after, and needs an explicit acknowledgement to apply", async () => {
    m.getAction.mockResolvedValue(action());
    m.executeAction.mockResolvedValue(action({ status: "SUCCEEDED", verification: "verified", actual_after: { status: "ACTIVE", daily_budget: "38500" }, allowed_actions: [] }));
    render(<OptimizationActionsPage clientId="c1" />);
    fireEvent.click(await screen.findByRole("button", { name: "Villas · audience" }));
    const dialog = await screen.findByRole("dialog");
    expect(await within(dialog).findByText("₹350")).toBeTruthy();
    expect(within(dialog).getByText("₹385")).toBeTruthy();
    const apply = within(dialog).getByRole("button", { name: "Apply in Meta" }) as HTMLButtonElement;
    expect(apply.disabled).toBe(true);
    fireEvent.click(within(dialog).getByLabelText(/Apply this change/));
    fireEvent.click(apply);
    await waitFor(() => expect(m.executeAction).toHaveBeenCalledWith("a1"));
    expect(await within(dialog).findByText(/Applied and verified/)).toBeTruthy();
  });

  it("warns that resuming starts spending", async () => {
    m.getAction.mockResolvedValue(action({ action_type: "resume", proposed_after: { status: "ACTIVE" }, before_state: { status: "PAUSED" } }));
    render(<OptimizationActionsPage clientId="c1" />);
    fireEvent.click(await screen.findByRole("button", { name: "Villas · audience" }));
    expect(await screen.findByLabelText(/starts delivery and spending now/)).toBeTruthy();
  });

  it("flags a change Meta did not reflect", async () => {
    m.listActions.mockResolvedValue([action({ status: "SUCCEEDED", verification: "mismatch", allowed_actions: [] })]);
    render(<OptimizationActionsPage clientId="c1" />);
    fireEvent.click(await screen.findByRole("tab", { name: "All" }));
    expect(await screen.findByText("Check in Meta")).toBeTruthy();
  });

  it("prepares a manual change from the form", async () => {
    m.previewAction.mockResolvedValue(action({ id: "a2", status: "PROPOSED", allowed_actions: ["approve", "reject", "cancel"] }));
    m.getAction.mockResolvedValue(action({ id: "a2", status: "PROPOSED", allowed_actions: ["approve", "reject", "cancel"] }));
    render(<OptimizationActionsPage clientId="c1" />);
    fireEvent.click(await screen.findByRole("button", { name: "＋ Prepare change" }));
    const dialog = await screen.findByRole("dialog", { name: "Prepare a change" });
    fireEvent.change(within(dialog).getByLabelText("Meta ID"), { target: { value: "120200009" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Preview change" }));
    await waitFor(() => expect(m.previewAction).toHaveBeenCalledWith("c1", expect.objectContaining({ action_type: "pause", level: "ad", object_id: "120200009" })));
  });
});
