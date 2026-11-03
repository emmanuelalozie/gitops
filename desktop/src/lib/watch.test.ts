import { alerts, snapshot } from "./watch";
import type { Load } from "./model";
import type { DriftIssue, Plan } from "./types";
import planFixture from "../fixtures/plan.json";

const drift = { state: "ok", value: planFixture as Plan, at: 0 } as Load<Plan>;
const inSync = { state: "ok", value: { ...(planFixture as Plan), hasChanges: false, changes: [] }, at: 0 } as Load<Plan>;
const failing = { state: "error", error: { kind: "engine", code: "unreachable", message: "can't connect\nmore" } } as Load<Plan>;
const issue = (n: number, state: "open" | "closed"): DriftIssue => ({ number: n, title: "Drift detected in prod", state, createdAt: "", closedAt: null, comments: 0, url: "" });

it("says nothing on the first look", () => {
  expect(alerts(null, { dev: drift }, [issue(1, "open")])).toEqual([]);
});

it("says nothing about a target the last look didn't see", () => {
  expect(alerts(snapshot({}, []), { added: drift }, [])).toEqual([]);
});

it("tells only what got worse or recovered", () => {
  const before = snapshot({ dev: inSync, prod: drift, gh: inSync, back: drift }, [issue(1, "open")]);
  const now = { dev: drift, prod: drift, gh: failing, back: inSync };
  expect(alerts(before, now, [issue(1, "open"), issue(2, "open"), issue(3, "closed")]).map((a) => a.title)).toEqual([
    "Drift in dev", "Can't plan gh", "back is back in sync", "Drift issue #2",
  ]);
  expect(alerts(before, now, null).find((a) => a.title === "Can't plan gh")?.body).toBe("can't connect");
  expect(alerts(before, now, null).find((a) => a.title === "Drift in dev")?.body).toContain("2 to update");
});
