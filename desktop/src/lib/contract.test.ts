// The engine's ContractTest writes these fixtures; this checks the app still reads them the way types.ts says.
// If the engine's JSON changes shape, one side or the other fails.

import planFixture from "../fixtures/plan.json";
import errorFixture from "../fixtures/error.json";
import type { Action, AppError, Plan } from "./types";

const actions: Action[] = ["create", "update", "delete"];

it("reads the plan fixture", () => {
  const plan = planFixture as Plan;
  expect(plan.schemaVersion).toBe(1);
  expect(plan.planId).toMatch(/^[0-9a-f]{64}$/);
  expect(Object.keys(plan.summary).sort()).toEqual([...actions].sort());
  for (const change of plan.changes) {
    expect(actions).toContain(change.action);
    expect(typeof change.replace).toBe("boolean");
    // create: only desired; delete: only live; update: both.
    expect(change.desired === null).toBe(change.action === "delete");
    expect(change.live === null).toBe(change.action === "create");
    for (const diff of change.diffs) {
      expect(diff).toHaveProperty("field");
      expect(diff).toHaveProperty("liveMissing");
    }
  }
  const counted = Object.fromEntries(actions.map((a) => [a, plan.changes.filter((c) => c.action === a).length]));
  expect(counted).toEqual(plan.summary);
});

it("reads the error fixture", () => {
  // The engine's error object; the Rust layer adds "kind" when it forwards one.
  const error = errorFixture as Omit<AppError, "kind"> & { details: { status: number } };
  expect(error.code).toBe("api");
  expect(error.details.status).toBe(500);
});
