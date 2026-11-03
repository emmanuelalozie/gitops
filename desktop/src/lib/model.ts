// Small pure functions the views share. Kept free of React so they're easy to test.

import type { AppError, Json, Plan, Target } from "./types";

export type Load<T> = { state: "idle" } | { state: "loading"; previous?: T } | { state: "ok"; value: T; at: number } | { state: "error"; error: AppError };

export type Health = "in-sync" | "drift" | "skipped" | "error" | "loading" | "unknown";

export function health(plan: Load<Plan> | undefined): Health {
  if (!plan || plan.state === "idle") return "unknown";
  if (plan.state === "loading") return "loading";
  if (plan.state === "error") return "error";
  if (plan.value.skipped) return "skipped";
  return plan.value.hasChanges ? "drift" : "in-sync";
}

export const healthLabel: Record<Health, string> = {
  "in-sync": "In sync",
  drift: "Drift",
  skipped: "Skipped",
  error: "Error",
  loading: "Planning…",
  unknown: "Not planned",
};

/** A JSON value as one short line: strings quoted, as the CLI prints them. */
export function formatValue(value: Json | undefined, missing = false): string {
  if (missing || value === undefined) return "(unset)";
  return JSON.stringify(value);
}

export function shortId(id: string | null | undefined): string {
  return id ? id.slice(0, 12) : "—";
}

/** "config/dev/users/bret.json" → "dev". Changes outside config/<target>/ affect no single target. */
export function targetOfPath(path: string): string | null {
  const match = /^config\/([^/]+)\//.exec(path.replace(/\\/g, "/"));
  return match ? match[1]! : null;
}

export function affectedTargets(paths: string[], targets: Target[]): string[] {
  const names = new Set(targets.map((t) => t.name));
  const hit = new Set<string>();
  for (const path of paths) {
    const name = targetOfPath(path);
    if (name && names.has(name)) hit.add(name);
  }
  return [...hit].sort();
}

/** Token variables the open repository's targets use, plus GITHUB_TOKEN for the app's own GitHub views. */
export function tokenNames(targets: Target[]): string[] {
  const names = new Set<string>(["GITHUB_TOKEN"]);
  for (const t of targets) if (t.tokenVariable) names.add(t.tokenVariable);
  return [...names].sort();
}

export function relativeTime(at: number, now = Date.now()): string {
  const seconds = Math.max(0, Math.round((now - at) / 1000));
  if (seconds < 10) return "just now";
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 48) return `${hours} h ago`;
  return `${Math.round(hours / 24)} days ago`;
}

/** The field names to show for a set of resources: schema order first, then anything else in first-seen order. */
export function columns(resources: Record<string, Json>[], preferred: string[] = []): string[] {
  const seen = new Set<string>(preferred);
  const extra: string[] = [];
  for (const r of resources) for (const k of Object.keys(r)) if (!seen.has(k)) { seen.add(k); extra.push(k); }
  return [...preferred.filter((p) => resources.some((r) => p in r)), ...extra];
}

/** An engine path (absolute in the app) as a path inside the repository, with forward slashes. */
export function repoRelative(path: string, repoRoot: string): string {
  const p = path.replace(/\\/g, "/");
  const root = repoRoot.replace(/\\/g, "/").replace(/\/+$/, "");
  return p.startsWith(root + "/") ? p.slice(root.length + 1) : p;
}

/** A next step for errors people can fix themselves, such as a local system that isn't running. */
export function hintFor(error: AppError, target?: Target): string | null {
  if (error.code === "unreachable" && target?.endpoint && /^https?:\/\/(127\.0\.0\.1|localhost)(:|\/|$)/.test(target.endpoint)) {
    if (target.mock) return "Its mock API isn't running. Press Start mock; the plan runs again once it's up.";
    return `Nothing is answering on ${target.endpoint}. Start that system (for a mock, \`docker compose up -d\` in the repository), then Re-plan.`;
  }
  if (error.code === "token_missing") return "Add the token in Settings; the engine restarts to pick it up.";
  return null;
}
