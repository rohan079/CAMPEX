import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as api from "../../src/automationApi";
import type { Rule } from "../../src/automationApi";
import { AutomationRulesPage } from "../../src/AutomationRulesPage";

vi.mock("../../src/automationApi", () => ({
  getAutomationSettings: vi.fn(), listRules: vi.fn(), createRule: vi.fn(), updateRule: vi.fn(), setRuleEnabled: vi.fn(),
  runRule: vi.fn(), listRuleExecutions: vi.fn(),
}));
const m = vi.mocked(api);

const rule: Rule = {
  id: "r1", client_id: "c1", name: "CPL watch", rule_type: "cpl_above_threshold", campaign_id: "1001", threshold: 1500,
  observation_days: 7, min_sample: 5, frequency: "daily", severity: "warning", notify_team: true, execution_level: "APPROVAL_REQUIRED",
  proposed_action: "pause", max_auto_actions_per_day: 1, cooldown_hours: 24, enabled: true, last_evaluated_at: "2026-11-20T10:00:00Z",
  last_outcome: "insufficient_data", created_by: "asha.manager@example.com", updated_at: "2026-11-20T10:00:00Z",
};

beforeEach(() => {
  m.getAutomationSettings.mockResolvedValue({ scheduler_enabled: true, preapproved_actions_enabled: false });
  m.listRules.mockResolvedValue([rule]);
  m.listRuleExecutions.mockResolvedValue([{ id: "e1", rule_id: "r1", rule_name: "CPL watch", object_id: "1001", object_name: "Villas",
    outcome: "insufficient_data", result: "recorded", observed: {}, message: "Only 2 Meta-reported leads in 7 days (need 5); no decision yet.",
    action_id: null, triggered_by: "scheduler", evaluated_at: "2026-11-20T10:00:00Z" }]);
});
afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe("Automation rules", () => {
  it("lists rules with their level and explains that level 4 is off", async () => {
    render(<AutomationRulesPage clientId="c1" />);
    const card = await screen.findByRole("article", { name: "CPL watch" });
    expect(within(card).getByText("Needs approval")).toBeTruthy();
    expect(within(card).getByText(/Not enough data/)).toBeTruthy();
    expect(screen.getByText(/Level 4 \(pre-approved pausing\) is off/)).toBeTruthy();
    expect(screen.getByText(/no decision yet/)).toBeTruthy();
  });

  it("toggles and runs a rule", async () => {
    m.setRuleEnabled.mockResolvedValue({ ...rule, enabled: false });
    m.runRule.mockResolvedValue([]);
    render(<AutomationRulesPage clientId="c1" />);
    fireEvent.click(await screen.findByRole("switch", { name: "Disable CPL watch" }));
    await waitFor(() => expect(m.setRuleEnabled).toHaveBeenCalledWith("r1", false));
    fireEvent.click(screen.getByRole("button", { name: "Run now" }));
    await waitFor(() => expect(m.runRule).toHaveBeenCalledWith("r1"));
  });

  it("hides level 4 when the server has it disabled and only offers pauses for actionable rules", async () => {
    m.createRule.mockResolvedValue(rule);
    render(<AutomationRulesPage clientId="c1" />);
    fireEvent.click(await screen.findByRole("button", { name: "＋ New rule" }));
    const dialog = await screen.findByRole("dialog", { name: "New rule" });
    expect(within(dialog).queryByText(/Pre-approved pause/)).toBeNull();
    expect(within(dialog).getByText(/Prepare for approval/)).toBeTruthy();
    fireEvent.change(within(dialog).getByLabelText("Condition"), { target: { value: "spend_over_limit" } });
    expect(within(dialog).queryByText(/Prepare for approval/)).toBeNull();
    fireEvent.change(within(dialog).getByLabelText("Name"), { target: { value: "Daily cap" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save rule" }));
    await waitFor(() => expect(m.createRule).toHaveBeenCalledWith("c1", expect.objectContaining({
      name: "Daily cap", rule_type: "spend_over_limit", threshold: 1000, campaign_id: null })));
  });

  it("offers level 4 only when the server allows it", async () => {
    m.getAutomationSettings.mockResolvedValue({ scheduler_enabled: true, preapproved_actions_enabled: true });
    render(<AutomationRulesPage clientId="c1" />);
    expect(await screen.findByText(/Level 4 is enabled on this server/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "＋ New rule" }));
    expect(await screen.findByText(/Pre-approved pause/)).toBeTruthy();
  });
});
