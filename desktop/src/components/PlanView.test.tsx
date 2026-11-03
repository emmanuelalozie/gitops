import { render, screen } from "@testing-library/react";
import planFixture from "../fixtures/plan.json";
import type { Plan } from "../lib/types";
import { PlanView, rows } from "./PlanView";

const plan = planFixture as Plan;

it("marks only the fields an update changes", () => {
  const update = plan.changes.find((c) => c.action === "update" && c.key === "Bret")!;
  const changed = rows(update).filter((r) => r.changed).map((r) => `${r.field}: ${r.live} -> ${r.desired}`);
  expect(changed).toEqual(['email: "old@b.io" -> "new@b.io"']);
});

it("shows one side for creates and deletes", () => {
  const create = rows(plan.changes.find((c) => c.action === "create")!);
  expect(create.every((r) => r.live === "" && r.desired !== "")).toBe(true);
  const remove = rows(plan.changes.find((c) => c.action === "delete")!);
  expect(remove.every((r) => r.desired === "" && r.live !== "")).toBe(true);
});

it("renders every change and flags replacements", () => {
  render(<PlanView plan={plan} />);
  for (const change of plan.changes) expect(screen.getByText(change.key)).toBeTruthy();
  expect(screen.getAllByText("replaces")).toHaveLength(plan.changes.filter((c) => c.replace).length);
});

it("explains a skipped plan instead of showing changes", () => {
  render(<PlanView plan={{ ...plan, skipped: true, skipReason: "needs a token" }} />);
  expect(screen.getByText("needs a token")).toBeTruthy();
});
