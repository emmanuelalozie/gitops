import { auditRows } from "./audit";
import type { AuditEntry, WorkflowRun } from "./types";

const entry = (sha: string): AuditEntry => ({ sha, short: sha.slice(0, 7), author: "A", date: "2026-11-01T00:00:00Z", subject: "s", pullRequest: 1, targets: ["prod"], files: [] });
const run = (sha: string, at: string, conclusion: string | null, status = "completed"): WorkflowRun => ({
  id: 1, sha, event: "push", status, conclusion, branch: "develop", createdAt: at, url: `run-${at}`,
  jobs: [{ name: "List targets", status: "completed", conclusion: "success", url: "" }, { name: "Apply prod", status, conclusion, url: "" }],
});

it("joins commits with their newest Apply run", () => {
  const rows = auditRows([entry("aaaaaaa1"), entry("bbbbbbb2")], [
    run("aaaaaaa1", "2026-11-01T01:00:00Z", "failure"),
    run("aaaaaaa1", "2026-11-01T02:00:00Z", "success"),
  ]);
  expect(rows[0]!.applied).toEqual([{ target: "prod", result: "success" }]);
  expect(rows[0]!.runUrl).toBe("run-2026-11-01T02:00:00Z");
  expect(rows[1]!.applied).toEqual([]);
  expect(auditRows([entry("c")], [run("c", "x", null, "in_progress")])[0]!.applied[0]!.result).toBe("running");
});
