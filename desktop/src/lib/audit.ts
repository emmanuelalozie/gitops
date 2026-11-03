// Joins the Git history of config/ with the Apply workflow's runs by commit, for the audit view.

import type { AuditEntry, WorkflowRun } from "./types";

export interface AuditRow extends AuditEntry {
  /** Per target the Apply run handled: success, failure, skipped or running. */
  applied: { target: string; result: string }[];
  runUrl: string | null;
}

export function auditRows(entries: AuditEntry[], runs: WorkflowRun[]): AuditRow[] {
  return entries.map((e) => {
    // The newest run for this commit (re-runs replace older results).
    const run = runs.filter((r) => r.sha === e.sha).sort((a, b) => b.createdAt.localeCompare(a.createdAt))[0];
    const applied = (run?.jobs ?? [])
      .map((j) => ({ m: /^Apply (.+)$/.exec(j.name), j }))
      .filter((x) => x.m)
      .map(({ m, j }) => ({ target: m![1]!, result: j.status !== "completed" ? "running" : (j.conclusion ?? "unknown") }));
    return { ...e, applied, runUrl: run?.url ?? null };
  });
}
