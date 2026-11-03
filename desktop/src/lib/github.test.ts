import { checkSummary, driftTimeline, issueTarget, openDays, openIssueFor } from "./github";
import { parse } from "./markdown";
import type { DriftIssue, WorkflowRun } from "./types";

const run = (id: number, createdAt: string, jobs: [string, string, string | null][]): WorkflowRun => ({
  id, createdAt, event: "schedule", status: "completed", conclusion: "success", branch: "develop", url: `u${id}`,
  jobs: jobs.map(([name, status, conclusion]) => ({ name, status, conclusion, url: "" })),
});

it("summarises checks", () => {
  expect(checkSummary([
    { name: "a", status: "completed", conclusion: "success", url: "" },
    { name: "b", status: "completed", conclusion: "failure", url: "" },
    { name: "c", status: "in_progress", conclusion: null, url: "" },
    { name: "d", status: "completed", conclusion: "skipped", url: "" },
  ])).toEqual({ passed: 2, failed: 1, pending: 1, total: 4 });
});

it("lays drift runs out as targets by runs, oldest first", () => {
  const timeline = driftTimeline([
    run(2, "2026-11-02T06:17:00Z", [["List targets", "completed", "success"], ["Drift in prod", "completed", "failure"], ["Drift in github", "completed", "success"]]),
    run(1, "2026-11-01T06:17:00Z", [["Drift in prod", "completed", "success"], ["Drift in old", "completed", "cancelled"]]),
    run(3, "2026-11-03T06:17:00Z", [["Drift in prod", "in_progress", null]]),
  ], ["github", "prod"]);
  expect(timeline.runs.map((r) => r.id)).toEqual([1, 2, 3]);
  expect(timeline.rows).toEqual([
    { target: "github", cells: ["none", "in-sync", "none"] },
    { target: "prod", cells: ["in-sync", "drift", "running"] },
    { target: "old", cells: ["skipped", "none", "none"] },
  ]);
});

it("finds the open drift issue of a target", () => {
  const issue = (n: number, title: string, state: "open" | "closed"): DriftIssue =>
    ({ number: n, title, state, createdAt: "2026-11-01T00:00:00Z", closedAt: state === "closed" ? "2026-11-03T00:00:00Z" : null, comments: 0, url: "" });
  const issues = [issue(1, "Drift detected in prod", "closed"), issue(2, "Drift detected in prod", "open"), issue(3, "Something else", "open")];
  expect(openIssueFor(issues, "prod")?.number).toBe(2);
  expect(issueTarget(issues[2]!)).toBeNull();
  expect(openDays(issues[0]!)).toBe(2);
  expect(openDays(issues[1]!, Date.parse("2026-11-04T00:00:00Z"))).toBe(3);
});

it("reads the Plan workflow's comment", () => {
  const blocks = parse(`## GitOps plan

What the Apply workflow will change when this pull request is merged.

### Plan for \`prod\` (users @ https://x)

**Plan: 0 to create, 1 to update, 0 to delete.**

| Action | Resource | Changes |
|---|---|---|
| ~ update | \`Bret\` | \`email\`: "a" → "b"<br>\`phone\`: "1" → "2" |
| + create | \`a\\|b\` | new resource |

- one
- two`);
  expect(blocks.map((b) => b.type)).toEqual(["heading", "paragraph", "heading", "paragraph", "table", "list"]);
  const table = blocks[4] as Extract<(typeof blocks)[number], { type: "table" }>;
  expect(table.rows).toHaveLength(2);
  expect(table.rows[0]![2]!.filter((p) => p.type === "break")).toHaveLength(1);
  expect(table.rows[1]![1]).toEqual([{ type: "code", text: "a|b" }]);
});

it("never produces HTML from a comment", () => {
  const blocks = parse('<img src=x onerror="alert(1)"> **bold** <script>x</script>');
  expect(JSON.stringify(blocks)).toContain('"type":"text","text":"<img src=x onerror=\\"alert(1)\\"> "');
});
