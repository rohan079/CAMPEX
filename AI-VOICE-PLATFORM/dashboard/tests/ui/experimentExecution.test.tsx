import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as api from "../../src/experimentExecutionApi";
import type { ExperimentExecution } from "../../src/experimentExecutionApi";
import { ExperimentExecutionDrawer } from "../../src/ExperimentExecutionDrawer";

vi.mock("../../src/experimentExecutionApi", () => ({ getExperimentExecution: vi.fn(), launchExperiment: vi.fn(), followUpExperiment: vi.fn(), linkVariantCreative: vi.fn() }));
vi.mock("../../src/creativeApi", () => ({ listCreatives: vi.fn(async () => [{ id: "cr1", name: "Lake view", approved_version: 2 }]) }));
const m = vi.mocked(api);

function x(over: Partial<ExperimentExecution> = {}): ExperimentExecution {
  return {
    experiment_id: "x1", experiment_status: "draft", outcome: null, phase: "setup", ready: true,
    checks: [{ code: "paused", severity: "info", message: "The test variant is paused; launching prepares an approved resume.", variant_role: "test" }],
    variants: [
      { variant_id: "v1", role: "control", name: "Control", level: "ad", object_id: "111", object_name: "Ad A", status: "ACTIVE", effective_status: "ACTIVE", campaign_id: "9", creative_asset_id: null, creative_version: null, creative_name: null },
      { variant_id: "v2", role: "test", name: "New headline", level: "ad", object_id: "222", object_name: "Ad B", status: "PAUSED", effective_status: "PAUSED", campaign_id: "9", creative_asset_id: null, creative_version: null, creative_name: null },
    ],
    actions: [], progress: { start_date: null, planned_end_date: "2026-12-05", days_elapsed: null, days_planned: null, min_sample_size: 1000, interim: null,
      sample_reached: false, duration_reached: false, guidance: "Check readiness, then launch. Paused variants are resumed only after approval." },
    allowed_actions: ["launch"], ...over,
  };
}

beforeEach(() => { m.getExperimentExecution.mockResolvedValue(x()); });
afterEach(() => { cleanup(); vi.clearAllMocks(); });

const open = () => render(<ExperimentExecutionDrawer clientId="c1" experimentId="x1" name="Headline test" onClose={() => {}} onChanged={() => {}} />);

describe("Experiment execution", () => {
  it("shows variant state from Meta and launches through approvals", async () => {
    m.launchExperiment.mockResolvedValue(x({ phase: "awaiting_activation", actions: [{ action_id: "a1", purpose: "activate", variant_role: "test",
      action_type: "resume", status: "PROPOSED", verification: null, created_at: "2026-11-21T00:00:00Z" }] }));
    open();
    expect(await screen.findByText(/launching prepares an approved resume/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Launch experiment" }));
    await waitFor(() => expect(m.launchExperiment).toHaveBeenCalledWith("x1"));
    expect(await screen.findByText("Resume test")).toBeTruthy();
    expect(screen.getByText(/Approve and apply these in Optimization actions/)).toBeTruthy();
    expect(screen.getByRole("button", { name: "Check & start measuring" })).toBeTruthy();
  });

  it("blocks launch while a readiness check fails", async () => {
    m.getExperimentExecution.mockResolvedValue(x({ ready: false, checks: [{ code: "account", severity: "error", message: "The test variant is not in an ad account mapped to this client.", variant_role: "test" }] }));
    open();
    expect(((await screen.findByRole("button", { name: "Launch experiment" })) as HTMLButtonElement).disabled).toBe(true);
  });

  it("attaches an approved creative to a variant", async () => {
    m.linkVariantCreative.mockResolvedValue(x());
    open();
    const selects = await screen.findAllByLabelText("Approved creative");
    await screen.findAllByRole("option", { name: "Lake view · v2" });
    fireEvent.change(selects[1], { target: { value: "cr1" } });
    await waitFor(() => expect(m.linkVariantCreative).toHaveBeenCalledWith("x1", "v2", "cr1"));
  });

  it("offers a prepared pause of the loser only after a reliable winner", async () => {
    m.getExperimentExecution.mockResolvedValue(x({ experiment_status: "completed", outcome: "no_reliable_winner", phase: "completed", allowed_actions: [],
      progress: { ...x().progress, guidance: "No reliable winner: keep the control or run a longer test. Nothing will be replaced." } }));
    open();
    expect(await screen.findByText(/Nothing will be replaced/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Prepare pause of losing variant" })).toBeNull();
    cleanup();
    m.getExperimentExecution.mockResolvedValue(x({ experiment_status: "completed", outcome: "winner", phase: "completed", allowed_actions: ["follow_up"] }));
    m.followUpExperiment.mockResolvedValue(x({ experiment_status: "completed", outcome: "winner", phase: "completed", allowed_actions: [] }));
    open();
    fireEvent.click(await screen.findByRole("button", { name: "Prepare pause of losing variant" }));
    await waitFor(() => expect(m.followUpExperiment).toHaveBeenCalledWith("x1"));
  });
});
