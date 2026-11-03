import { foldProgress } from "./apply";
import { demoTargets } from "./demo";
import { layoutGraph } from "./graph";
import { defaultChoice, promotionOps, promotionTitle } from "./promote";
import type { Lint, Plan } from "./types";
import planFixture from "../fixtures/plan.json";

const lint: Lint = {
  ok: false,
  findings: [],
  checks: [],
  requirements: [
    { target: "github-branches", branch: "develop", file: "f", check: "Build, test, validate", satisfiedBy: [{ workflow: "CI", job: "build", file: ".github/workflows/ci.yml" }] },
    { target: "github-branches", branch: "develop", file: "f", check: "Renamed job", satisfiedBy: [] },
  ],
};

describe("graph", () => {
  const graph = layoutGraph(demoTargets, lint, (t) => (t === "dev" ? "drift" : "in-sync"));
  const node = (id: string) => graph.nodes.find((n) => n.id === id);

  it("connects jobs to checks to rules to targets to live systems", () => {
    expect(graph.edges).toContainEqual({ from: "job:.github/workflows/ci.yml#build", to: "check:Build, test, validate", broken: false });
    expect(graph.edges).toContainEqual({ from: "check:Build, test, validate", to: "rule:github-branches/develop", broken: false });
    expect(graph.edges).toContainEqual({ from: "rule:github-branches/develop", to: "target:github-branches", broken: false });
    expect(graph.edges).toContainEqual({ from: "target:dev", to: "live:http://127.0.0.1:3000", broken: false });
  });

  it("marks a required check nothing reports", () => {
    expect(node("check:Renamed job")?.tone).toBe("danger");
    expect(graph.edges.find((e) => e.from === "check:Renamed job")?.broken).toBe(true);
  });

  it("shares a live system between targets and colours targets by health", () => {
    expect(graph.nodes.filter((n) => n.id === "live:emmanuelalozie/gitops")).toHaveLength(1);
    expect(node("target:dev")?.tone).toBe("drift");
    expect(graph.rows).toBe(Math.max(...[0, 1, 2, 3, 4].map((c) => graph.nodes.filter((n) => n.column === c).length)));
  });
});

it("folds apply progress into one row per resource", () => {
  const rows = foldProgress([
    { requestId: 1, target: "dev", key: "Bret", step: "delete-to-replace", state: "starting" },
    { requestId: 1, target: "dev", key: "Bret", step: "delete-to-replace", state: "finished" },
    { requestId: 1, target: "dev", key: "Ann", step: "update", state: "starting" },
    { requestId: 1, target: "dev", key: "Bret", step: "create-replacement", state: "failed", error: { kind: "engine", code: "api", message: "boom" } },
  ]);
  expect(rows).toEqual([
    { key: "Bret", steps: [{ step: "delete-to-replace", state: "finished", error: undefined }, { step: "create-replacement", state: "failed", error: "boom" }] },
    { key: "Ann", steps: [{ step: "update", state: "starting", error: undefined }] },
  ]);
});

describe("promotion", () => {
  const plan = planFixture as Plan;

  it("copies additions and updates by default, deletes only when chosen", () => {
    const chosen = defaultChoice(plan);
    expect([...chosen].every((id) => !id.startsWith("delete:"))).toBe(true);
    const ops = promotionOps(plan, chosen);
    expect(ops.map((o) => `${o.type} ${o.key}`)).toEqual(["write Bret", "write Elwyn.Skiles", "write Moriah.Stanton"]);
    expect(ops[0]).toMatchObject({ previousKey: "Bret", resource: { email: "new@b.io" } });
    expect(ops[2]).toMatchObject({ previousKey: undefined });
    expect(promotionOps(plan, new Set(["delete:Moriah.Stantonite"]))).toEqual([{ type: "delete", key: "Moriah.Stantonite" }]);
  });

  it("titles the pull request", () => {
    expect(promotionTitle("dev", "prod", ["Bret"])).toBe("Promote Bret from dev to prod");
    expect(promotionTitle("dev", "prod", ["a", "b", "c", "d"])).toBe("Promote a, b and 2 more from dev to prod");
  });
});
