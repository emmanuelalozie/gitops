// The engine's JSON (docs/engine-protocol.md, schema version 1) and the Rust commands' results.
// src/fixtures/*.json are written by the engine's own tests; types.test.ts checks they still fit these types.

export type Json = null | boolean | number | string | Json[] | { [key: string]: Json };
export type Resource = { [field: string]: Json };

export type Action = "create" | "update" | "delete";

export interface FieldDiff {
  field: string;
  live: Json;
  liveMissing: boolean;
  desired: Json;
}

export interface Change {
  action: Action;
  key: string;
  replace: boolean;
  desired: Resource | null;
  live: Resource | null;
  diffs: FieldDiff[];
}

export interface Plan {
  schemaVersion: 1;
  target: string;
  description: string;
  kind: string;
  keyField: string;
  skipped: boolean;
  skipReason?: string;
  planId: string | null;
  hasChanges: boolean;
  summary: Record<Action, number>;
  changes: Change[];
  unmanaged: string[];
  from?: string;
  to?: string;
}

export interface Target {
  name: string;
  kind: string;
  keyField: string;
  description: string;
  endpoint: string | null;
  endpointVariable?: string;
  endpointOverridden?: boolean;
  prune: boolean;
  verifyAfterApply: boolean;
  localApply: boolean;
  workflows: boolean;
  tokenVariable: string | null;
  tokenRequired: boolean;
  tokenPresent: boolean;
  dir: string;
  resourceDir: string;
  resources: number;
}

export interface Problem {
  file: string | null;
  pointer: string;
  message: string;
}

export interface Validation {
  ok: boolean;
  targets: { name: string; kind: string; files: number; ok: boolean; problems: Problem[] }[];
}

export interface LintFinding {
  severity: "warning" | "error";
  code: string;
  target: string;
  file: string;
  pointer: string;
  message: string;
}

export interface Lint {
  ok: boolean;
  findings: LintFinding[];
  requirements: { target: string; branch: string; file: string; check: string; satisfiedBy: { workflow: string; job: string; file: string }[] }[];
  checks: { workflow: string; file: string; job: string; name: string; onPullRequest: boolean }[];
}

export interface EngineVersion {
  engineVersion: string;
  schemaVersion: number;
  java: string;
  alive?: boolean;
}

export interface GitHubRepo {
  owner: string;
  name: string;
}

export interface RepoStatus {
  branch: string | null;
  github: GitHubRepo | null;
  defaultBranch: string | null;
  shallow: boolean;
}

export interface RepoInfo {
  path: string;
  name: string;
  git: RepoStatus;
  engine: EngineVersion;
}

export interface AppInfo {
  version: string;
  recentRepos: string[];
  openRepo: string | null;
}

export interface TokenStatus {
  name: string;
  present: boolean;
}

/** Every error from a command: the engine's codes (invalid_config, plan_changed, ...) come through as-is. */
export interface AppError {
  kind: "engine" | "git" | "github" | "keychain" | "repo" | "io" | "internal";
  code: string;
  message: string;
  details?: Json & { problems?: Problem[] };
}

export interface ApplyProgress {
  requestId: number;
  target: string;
  key: string;
  step: "delete" | "delete-to-replace" | "update" | "create" | "create-replacement";
  state: "starting" | "finished" | "failed";
  error?: AppError;
}

export interface ResourceEntry {
  key: string;
  file: string;
  resource: Resource | null;
  error?: string;
}

export interface ConfigChange {
  path: string;
  status: "modified" | "added" | "deleted" | "renamed" | "untracked";
  target: string | null;
}

export interface Commit {
  sha: string;
  short: string;
  author: string;
  date: string;
  subject: string;
  pullRequest: number | null;
}

export interface Proposal {
  branch: string;
  title: string;
  body: string;
  paths: string[];
}

export interface ProposeResult {
  proposed: { branch: string; commit: string; fromBranch: string; pushed: boolean; pushError: string | null };
  base: string;
  pullRequest: { number: number; url: string } | null;
  compareUrl: string | null;
  pullRequestError: AppError | null;
}

export interface PullRequest {
  number: number;
  title: string;
  author: string;
  branch: string;
  sha: string;
  base: string;
  draft: boolean;
  url: string;
  updatedAt: string;
}

export interface CheckRun {
  name: string;
  status: "queued" | "in_progress" | "completed" | string;
  conclusion: "success" | "failure" | "neutral" | "cancelled" | "skipped" | "timed_out" | "action_required" | null | string;
  url: string;
}

export interface PullDetails {
  files: { path: string; status: string; additions: number; deletions: number }[];
  checks: CheckRun[];
  planComment: { body: string; updatedAt: string; url: string; author: string } | null;
}

export interface WorkflowJob {
  name: string;
  status: string;
  conclusion: string | null;
  url: string;
}

export interface WorkflowRun {
  id: number;
  workflow?: string;
  path?: string;
  title?: string;
  event: string;
  status: string;
  conclusion: string | null;
  branch: string;
  createdAt: string;
  url: string;
  jobs?: WorkflowJob[];
}

export interface DriftIssue {
  number: number;
  title: string;
  state: "open" | "closed";
  createdAt: string;
  closedAt: string | null;
  comments: number;
  url: string;
}

export interface DriftData {
  issues: DriftIssue[];
  runs: WorkflowRun[];
}

export interface RunsData {
  runs: WorkflowRun[];
  dispatchable: { file: string; name: string }[];
  tokenPresent: boolean;
}
