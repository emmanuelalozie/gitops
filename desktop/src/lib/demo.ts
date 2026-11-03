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
    workflows: false, resources: 10, description: "Development users API: the json-server mock in mock/." }),
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

function plan(name: string): Plan {
  const t = demoTargets.find((x) => x.name === name);
  if (!t) throw { kind: "engine", code: "usage", message: `No target '${name}'` };
  if (name === "dev") return planFixture as Plan;
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
    case "kind.schema":
      return schemas[String(params.kind)] ?? { type: "object", properties: {} };
    case "resource.check":
      return { ok: true, problems: [] };
    case "targets.compare":
      return { ...emptyPlan(demoTargets[0]!), target: `${params.from} -> ${params.to}`, from: params.from, to: params.to };
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

export const demo = {
  async invoke<T>(command: string, args: Record<string, unknown> = {}): Promise<T> {
    const result = await (async (): Promise<unknown> => {
      switch (command) {
        case "app_info":
          return { version: "demo", recentRepos: [repo.path], openRepo: null };
        case "open_repo":
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
