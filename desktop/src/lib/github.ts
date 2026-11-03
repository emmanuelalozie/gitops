// Shapes GitHub data for the views. Pure functions, tested in github.test.ts.

import type { CheckRun, DriftIssue, WorkflowRun } from "./types";

export function checkSummary(checks: CheckRun[]) {
  let passed = 0, failed = 0, pending = 0;
  for (const c of checks) {
    if (c.status !== "completed") pending++;
    else if (c.conclusion === "success" || c.conclusion === "neutral" || c.conclusion === "skipped") passed++;
    else failed++;
  }
  return { passed, failed, pending, total: checks.length };
}

export type Cell = "in-sync" | "drift" | "skipped" | "running" | "none";

/** The Drift workflow runs one job per target, named "Drift in <target>": success means in sync, failure means drift. */
export function cellOf(job: { status: string; conclusion: string | null } | undefined): Cell {
  if (!job) return "none";
  if (job.status !== "completed") return "running";
  if (job.conclusion === "success") return "in-sync";
  if (job.conclusion === "failure") return "drift";
  return "skipped";
}

export interface Timeline {
  runs: WorkflowRun[];
  rows: { target: string; cells: Cell[] }[];
}

/** Targets × runs, oldest run on the left. Targets that appear in any run are included, known targets first. */
export function driftTimeline(runs: WorkflowRun[], knownTargets: string[]): Timeline {
  const ordered = [...runs].sort((a, b) => a.createdAt.localeCompare(b.createdAt));
  const fromJobs = new Set<string>();
  for (const r of ordered) for (const j of r.jobs ?? []) {
    const m = /^Drift in (.+)$/.exec(j.name);
    if (m) fromJobs.add(m[1]!);
  }
  const targets = [...knownTargets.filter((t) => fromJobs.has(t)), ...[...fromJobs].filter((t) => !knownTargets.includes(t)).sort()];
  return {
    runs: ordered,
    rows: targets.map((target) => ({
      target,
      cells: ordered.map((r) => cellOf(r.jobs?.find((j) => j.name === `Drift in ${target}`))),
    })),
  };
}

/** The Drift workflow names its issues "Drift detected in <target>". */
export function issueTarget(issue: DriftIssue): string | null {
  return /^Drift detected in (.+)$/.exec(issue.title)?.[1] ?? null;
}

export function openIssueFor(issues: DriftIssue[], target: string): DriftIssue | undefined {
  return issues.find((i) => i.state === "open" && issueTarget(i) === target);
}

/** How long an issue stayed open, in days (to now if still open). */
export function openDays(issue: DriftIssue, now = Date.now()): number {
  const end = issue.closedAt ? Date.parse(issue.closedAt) : now;
  return Math.max(0, Math.round((end - Date.parse(issue.createdAt)) / 86_400_000));
}
