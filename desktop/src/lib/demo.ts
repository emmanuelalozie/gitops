// A stand-in backend for running the UI in a plain browser (npm run dev, tests, screenshots). It never runs
// inside the desktop app. The data mirrors this repository's own targets, and the dev plan is the engine's
// fixture, so what the browser shows is what the engine really returns.

import planFixture from "../fixtures/plan.json";
import type { Plan, Resource, Target } from "./types";

type Handler = (payload: unknown) => void;
const listeners = new Map<string, Set<Handler>>();

const users: Resource[] = [
  { id: 1, username: "Bret", email: "new@b.io", phone: "555-0100" },
  { id: 2, username: "Antonette", email: "Shanna@melissa.tv", phone: "010-692-6593 x09125" },
  { id: 7, username: "Elwyn.Skiles", email: "e@b.io", phone: "555-0100" },
  { id: 5, username: "Moriah.Stanton", email: "m@b.io", phone: "555-0100" },
];

const target = (t: Partial<Target> & Pick<Target, "name" | "kind" | "keyField">): Target => ({
  type: t.kind === "users" ? "users" : "github",
  description: "",
  endpoint: null,
  prune: false,
  verifyAfterApply: true,
  localApply: false,
  workflows: true,
  tokenVariable: null,
  tokenRequired: false,
  tokenPresent: false,
  dir: `config/${t.name}`,
  resourceDir: `config/${t.name}/${t.kind}`,
  resources: 1,
  ...t,
});

export const demoTargets: Target[] = [
  target({ name: "dev", kind: "users", keyField: "username", endpoint: "http://127.0.0.1:3000", prune: true, localApply: true,
    workflows: false, resources: 10, mock: "mock/db.json", description: "Development users API: the json-server mock in mock/." }),
  target({ name: "github", kind: "labels", keyField: "name", endpoint: "emmanuelalozie/gitops", tokenVariable: "GITHUB_TOKEN",
    resources: 3, description: "Issue labels of this repository. Labels not listed here are left alone." }),
  target({ name: "github-branches", kind: "branch-protection", keyField: "branch", endpoint: "emmanuelalozie/gitops",
    tokenVariable: "GITOPS_ADMIN_TOKEN", tokenRequired: true, description: "Branch protection rules of this repository." }),
  target({ name: "github-repo", kind: "repository", keyField: "name", endpoint: "emmanuelalozie/gitops",
    tokenVariable: "GITOPS_ADMIN_TOKEN", tokenRequired: true, description: "Settings of this repository." }),
  target({ name: "prod", kind: "users", keyField: "username", endpoint: "https://jsonplaceholder.typicode.com", prune: true,
    verifyAfterApply: false, resources: 10, description: "Production users API." }),
];

function emptyPlan(t: Target): Plan {
  return {
    schemaVersion: 1, target: t.name, description: `${t.kind} @ ${t.endpoint}`, kind: t.kind, keyField: t.keyField,
    skipped: false, planId: "0".repeat(64), hasChanges: false, summary: { create: 0, update: 0, delete: 0 },
    changes: [], unmanaged: [],
  };
}

let devApplied = false;
let mockRunning = true;

function plan(name: string): Plan {
  const t = demoTargets.find((x) => x.name === name);
  if (!t) throw { kind: "engine", code: "usage", message: `No target '${name}'` };
  if (t.mock && !mockRunning) {
    throw { kind: "engine", code: "unreachable", message: `can't connect to the live system: GET ${t.endpoint}/users (ConnectException). Is it running?` };
  }
  if (name === "dev") return devApplied ? emptyPlan(t) : (planFixture as Plan);
  if (t.tokenRequired) {
    return { ...emptyPlan(t), skipped: true, planId: null, skipReason: `'${name}' needs a token in ${t.tokenVariable}, which isn't set.` };
  }
  if (name === "github") {
    return { ...emptyPlan(t), unmanaged: ["bug", "documentation", "duplicate", "enhancement", "good first issue", "help wanted", "invalid", "question", "wontfix"] };
  }
  return emptyPlan(t);
}

const schemas: Record<string, unknown> = {
  users: {
    type: "object",
    properties: {
      id: { type: "integer", minimum: 1 },
      username: { type: "string", pattern: "^[A-Za-z0-9._-]+$" },
      email: { type: "string", pattern: "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$" },
      phone: { type: "string", minLength: 1 },
    },
    required: ["id", "username", "email", "phone"],
    additionalProperties: false,
  },
};

const delay = <T>(value: T, ms = 150) => new Promise<T>((resolve) => setTimeout(() => resolve(value), ms));

async function engineCall(method: string, params: Record<string, unknown>): Promise<unknown> {
  switch (method) {
    case "targets.list":
      return { targets: demoTargets };
    case "config.validate":
      return { ok: true, targets: demoTargets.map((t) => ({ name: t.name, kind: t.kind, files: t.resources, ok: true, problems: [] })) };
    case "plan.compute":
      return delay(plan(String(params.target)), 400);
    case "live.fetch":
      return { resources: users.map((u, i) => ({ ...u, website: `site${i}.example` })) };
    case "kind.schema": {
      const kind = params.target ? demoTargets.find((t) => t.name === params.target)?.kind : params.kind;
      return schemas[String(kind)] ?? { type: "object", properties: {} };
    }
    case "resource.check":
      return { ok: true, problems: [] };
    case "resources.list":
      return { resources: (params.target === "dev" || params.target === "prod" ? users : []).map((u) => ({
        key: String(u.username), file: `config/${params.target}/users/${String(u.username).toLowerCase()}.json`, resource: u })) };
    case "resource.write": {
      const r = params.resource as Resource;
      const i = users.findIndex((u) => u.username === (params.previousKey ?? r.username));
      if (i >= 0) users[i] = r; else users.push(r);
      changes.add(`config/${params.target}/users/${String(r.username).toLowerCase()}.json`);
      return { file: `config/${params.target}/users/${String(r.username).toLowerCase()}.json`, removed: [] };
    }
    case "resource.delete": {
      const i = users.findIndex((u) => u.username === params.key);
      if (i >= 0) users.splice(i, 1);
      changes.add(`config/${params.target}/users/${String(params.key).toLowerCase()}.json`);
      return { file: "", removed: i >= 0 };
    }
    case "targets.compare":
      return { ...(planFixture as Plan), planId: null, target: `${params.from} -> ${params.to}`, description: "users in Git", from: params.from, to: params.to };
    case "mock.status":
    case "mock.start":
    case "mock.stop": {
      const t = demoTargets.find((x) => x.name === params.target);
      if (!t?.mock) return { target: params.target, configured: false };
      if (method === "mock.start") { await delay(null, 600); mockRunning = true; devApplied = false; }
      if (method === "mock.stop") mockRunning = false;
      return { target: t.name, configured: true, seed: t.mock, url: t.endpoint, running: mockRunning, startedHere: mockRunning, via: mockRunning ? "Node.js" : null };
    }
    case "plan.apply": {
      const target = String(params.target);
      if (target !== "dev") throw { kind: "engine", code: "not_allowed", message: `'${target}' changes through pull requests, not from this app` };
      const current = plan(target);
      if (params.planId !== current.planId) {
        throw { kind: "engine", code: "plan_changed", message: "The plan changed since it was approved. Nothing was applied.", details: { expected: params.planId, actual: current.planId } };
      }
      const steps: [string, string][] = [];
      const replaced = current.changes.filter((c) => c.replace);
      current.changes.filter((c) => c.action === "delete").forEach((c) => steps.push([c.key, "delete"]));
      replaced.forEach((c) => steps.push([c.key, "delete-to-replace"]));
      current.changes.filter((c) => c.action === "update" && !c.replace).forEach((c) => steps.push([c.key, "update"]));
      current.changes.filter((c) => c.action === "create").forEach((c) => steps.push([c.key, "create"]));
      replaced.forEach((c) => steps.push([c.key, "create-replacement"]));
      for (const [key, step] of steps) {
        for (const state of ["starting", "finished"]) {
          await delay(null, 120);
          demo.emit("engine-notification", { method: "apply.progress", params: { requestId: 1, target, key, step, state } });
        }
      }
      devApplied = true;
      return { schemaVersion: 1, target, planId: current.planId, applied: true, verified: true, verifyAfterApply: true, after: plan(target) };
    }
    case "repo.lint":
      return {
        ok: true, findings: [],
        requirements: [
          { target: "github-branches", branch: "develop", file: "config/github-branches/branch-protection/develop.json", check: "Build, test, validate",
            satisfiedBy: [{ workflow: "CI", job: "build", file: ".github/workflows/ci.yml" }] },
          { target: "github-branches", branch: "develop", file: "config/github-branches/branch-protection/develop.json", check: "Apply and verify (dev mock)",
            satisfiedBy: [{ workflow: "CI", job: "integration", file: ".github/workflows/ci.yml" }] },
        ],
        checks: [
          { workflow: "CI", file: ".github/workflows/ci.yml", job: "build", name: "Build, test, validate", onPullRequest: true },
          { workflow: "CI", file: ".github/workflows/ci.yml", job: "integration", name: "Apply and verify (dev mock)", onPullRequest: true },
          { workflow: "Plan", file: ".github/workflows/plan.yml", job: "plan", name: "plan", onPullRequest: true },
          { workflow: "Drift detection", file: ".github/workflows/drift.yml", job: "drift", name: "Drift in ${{ matrix.target }}", onPullRequest: false },
        ],
      };
    default:
      throw { kind: "engine", code: "not_allowed", message: `The demo doesn't do '${method}'` };
  }
}

const repo = {
  path: "/demo/gitops",
  name: "gitops",
  git: { branch: "develop", github: { owner: "emmanuelalozie", name: "gitops" }, defaultBranch: "develop", shallow: false },
  engine: { engineVersion: "demo", schemaVersion: 1, java: "none (demo data)" },
};

const tokens = new Map<string, boolean>();
const changes = new Set<string>(["config/dev/users/bret.json"]);
const history = [
  { sha: "5dc5699aa", short: "5dc5699", author: "Emmanuel Alozie", date: "2026-10-30T18:02:11Z", subject: "Merge pull request #14 from emmanuelalozie/fix/demo-windows-localhost", pullRequest: 14 },
  { sha: "a1b2c3d4e", short: "a1b2c3d", author: "Emmanuel Alozie", date: "2026-10-21T09:40:00Z", subject: "Add Moriah.Stanton to dev (#11)", pullRequest: 11 },
];

export const demo = {
  async invoke<T>(command: string, args: Record<string, unknown> = {}): Promise<T> {
    const result = await (async (): Promise<unknown> => {
      switch (command) {
        case "app_info":
          return { version: "demo", recentRepos: [repo.path, "/demo/inventory-manager"], openRepo: null };
        case "open_repo":
          if (String(args.path).endsWith("inventory-manager")) {
            throw { kind: "repo", code: "not_gitops",
              message: "inventory-manager is a regular project, not a GitOps repository. The app opens a repository that describes live systems in config/<target>/target.json, such as your gitops repository.",
              details: { path: args.path, name: "inventory-manager", github: { owner: "emmanuelalozie", name: "inventory-manager" } } };
          }
          return delay(repo, 300);
        case "close_repo":
          return null;
        case "repo_status":
          return repo.git;
        case "engine_info":
        case "restart_engine":
          return { ...repo.engine, alive: true };
        case "engine_call":
          return engineCall(String(args.method), (args.params as Record<string, unknown>) ?? {});
        case "config_changes":
          return [...changes].map((path) => ({ path, status: "modified", target: path.split("/")[1] ?? null }));
        case "working_diff":
          return `--- a/${args.path}\n+++ b/${args.path}\n@@ -1,6 +1,6 @@\n {\n   "id": 1,\n   "username": "Bret",\n-  "email": "old@b.io",\n+  "email": "new@b.io",\n   "phone": "555-0100"\n }\n`;
        case "discard_change":
          changes.delete(String(args.path));
          return null;
        case "file_history":
          return history;
        case "commit_diff":
          return `--- a/config/dev/users/moriah.stanton.json\n+++ b/config/dev/users/moriah.stanton.json\n@@ -0,0 +1,6 @@\n+{\n+  "id": 5,\n+  "username": "Moriah.Stanton"\n+}\n`;
        case "propose": {
          const p = args.proposal as { branch: string; paths: string[] };
          p.paths.forEach((x) => changes.delete(x));
          return { proposed: { branch: p.branch, commit: "f00dfeed", fromBranch: "develop", pushed: true, pushError: null }, base: "develop",
            pullRequest: null, compareUrl: `https://github.com/emmanuelalozie/gitops/compare/develop...${p.branch}?expand=1`, pullRequestError: null };
        }
        case "github_pulls":
          return [{ number: 17, title: "Recolour the drift label", author: "emmanuelalozie", branch: "gitops/recolour-drift", sha: "abc1234def",
            base: "develop", draft: false, url: "https://github.com/emmanuelalozie/gitops/pull/17", updatedAt: new Date(Date.now() - 3_600_000).toISOString() }];
        case "github_pull_details":
          return {
            files: [{ path: "config/github/labels/drift.json", status: "modified", additions: 1, deletions: 1 }],
            checks: [
              { name: "Build, test, validate", status: "completed", conclusion: "success", url: "" },
              { name: "Apply and verify (dev mock)", status: "completed", conclusion: "success", url: "" },
              { name: "plan", status: "in_progress", conclusion: null, url: "" },
            ],
            planComment: { author: "github-actions[bot]", updatedAt: new Date().toISOString(), url: "https://github.com/emmanuelalozie/gitops/pull/17",
              body: "## GitOps plan\n\nWhat the Apply workflow will change when this pull request is merged.\n\n### Plan for `github` (labels @ emmanuelalozie/gitops)\n\n**Plan: 0 to create, 1 to update, 0 to delete.**\n\n| Action | Resource | Changes |\n|---|---|---|\n| ~ update | `drift` | `color`: \"d93f0b\" → \"b60205\" |\n\n9 live resource(s) are not in Git and are left alone because prune is off.\n\n### Plan for `prod` (users @ https://jsonplaceholder.typicode.com)\n\nNo changes. Live state matches Git." },
          };
        case "github_drift": {
          const day = 86_400_000;
          const runs = Array.from({ length: 14 }, (_, i) => {
            const at = new Date(Date.now() - (13 - i) * day).toISOString();
            const drift = (t: string) => (t === "prod" && i >= 9 && i <= 11) || (t === "github" && i === 4);
            return { id: 1000 + i, event: "schedule", status: "completed", conclusion: "success", branch: "develop", createdAt: at,
              url: "https://github.com/emmanuelalozie/gitops/actions", jobs: ["github", "github-branches", "github-repo", "prod"].map((t) => ({
                name: `Drift in ${t}`, status: "completed", conclusion: drift(t) ? "failure" : "success", url: "" })) };
          });
          return { runs, issues: [
            { number: 15, title: "Drift detected in prod", state: "closed", createdAt: new Date(Date.now() - 4 * day).toISOString(),
              closedAt: new Date(Date.now() - 1.5 * day).toISOString(), comments: 2, url: "https://github.com/emmanuelalozie/gitops/issues/15" },
            { number: 9, title: "Drift detected in github", state: "closed", createdAt: new Date(Date.now() - 9 * day).toISOString(),
              closedAt: new Date(Date.now() - 8 * day).toISOString(), comments: 0, url: "https://github.com/emmanuelalozie/gitops/issues/9" },
          ] };
        }
        case "github_runs":
          return { tokenPresent: tokens.get("GITHUB_TOKEN") ?? false,
            dispatchable: [{ file: "apply.yml", name: "Apply" }, { file: "drift.yml", name: "Drift detection" }],
            runs: ["CI", "Plan", "Drift detection", "CI"].map((w, i) => ({ id: 2000 + i, workflow: w, event: w === "Drift detection" ? "schedule" : "pull_request",
              status: "completed", conclusion: i === 2 ? "failure" : "success", branch: "develop", title: w === "Drift detection" ? "Drift detection" : "Recolour the drift label",
              createdAt: new Date(Date.now() - i * 3_600_000).toISOString(), url: "https://github.com/emmanuelalozie/gitops/actions" })) };
        case "github_dispatch":
          if (!tokens.get("GITHUB_TOKEN")) throw { kind: "github", code: "token_missing", message: "Starting a workflow needs GITHUB_TOKEN (with Actions: write) in Settings" };
          return null;
        case "token_status":
          return (args.names as string[]).map((name) => ({ name, present: tokens.get(name) ?? false }));
        case "set_token":
          tokens.set(String(args.name), true);
          return null;
        case "clear_token":
          tokens.delete(String(args.name));
          return null;
        default:
          throw { kind: "internal", code: "demo", message: `The demo doesn't do '${command}'` };
      }
    })();
    return result as T;
  },
  async listen(event: string, handler: Handler): Promise<() => void> {
    if (!listeners.has(event)) listeners.set(event, new Set());
    listeners.get(event)!.add(handler);
    return () => listeners.get(event)?.delete(handler);
  },
  emit(event: string, payload: unknown) {
    listeners.get(event)?.forEach((h) => h(payload));
  },
  async pickFolder(): Promise<string | null> {
    return repo.path;
  },
};
