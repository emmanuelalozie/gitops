// Every call the UI makes. Inside the desktop app these go to the Rust commands; in a plain browser
// (npm run dev, or screenshots) they go to the demo backend in demo.ts, which serves the fixtures.

import type {
  AppError, AppInfo, Commit, ConfigChange, DriftData, EngineVersion, Json, Lint, Plan, Problem, Proposal, ProposeResult,
  PullDetails, PullRequest, RepoInfo, RepoStatus, Resource, ResourceEntry, RunsData, Target, TokenStatus, Validation,
} from "./types";
import { demo } from "./demo";

type Invoke = <T>(command: string, args?: Record<string, unknown>) => Promise<T>;
type Listen = (event: string, handler: (payload: unknown) => void) => Promise<() => void>;

export const inDesktop = typeof window !== "undefined" && "__TAURI_INTERNALS__" in window;

let invokeImpl: Invoke = demo.invoke;
let listenImpl: Listen = demo.listen;

if (inDesktop) {
  const core = await import("@tauri-apps/api/core");
  const event = await import("@tauri-apps/api/event");
  invokeImpl = core.invoke as Invoke;
  listenImpl = async (name, handler) => event.listen(name, (e) => handler(e.payload));
}

/** Commands reject with an AppError object; anything else (a bug) is wrapped so the UI can always show it. */
export function asAppError(error: unknown): AppError {
  if (error && typeof error === "object" && "message" in error && "code" in error) {
    return error as AppError;
  }
  return { kind: "internal", code: "internal", message: String(error) };
}

async function call<T>(command: string, args?: Record<string, unknown>): Promise<T> {
  try {
    return await invokeImpl<T>(command, args);
  } catch (error) {
    throw asAppError(error);
  }
}

function engine<T>(method: string, params: Record<string, unknown> = {}): Promise<T> {
  return call<T>("engine_call", { method, params });
}

export const api = {
  appInfo: () => call<AppInfo>("app_info"),
  openRepo: (path: string) => call<RepoInfo>("open_repo", { path }),
  closeRepo: () => call<void>("close_repo"),
  repoStatus: () => call<RepoStatus>("repo_status"),
  restartEngine: () => call<EngineVersion>("restart_engine"),
  engineInfo: () => call<EngineVersion>("engine_info"),

  tokenStatus: (names: string[]) => call<TokenStatus[]>("token_status", { names }),
  setToken: (name: string, value: string) => call<void>("set_token", { name, value }),
  clearToken: (name: string) => call<void>("clear_token", { name }),

  targets: () => engine<{ targets: Target[] }>("targets.list").then((r) => r.targets),
  validate: (target?: string) => engine<Validation>("config.validate", target ? { target } : {}),
  plan: (target: string) => engine<Plan>("plan.compute", { target }),
  fetchLive: (target: string) => engine<{ resources: Resource[] }>("live.fetch", { target }).then((r) => r.resources),
  compare: (from: string, to: string) => engine<Plan>("targets.compare", { from, to }),
  schema: (kind: string) => engine<Record<string, Json>>("kind.schema", { kind }),
  checkResource: (target: string, resource: Resource, previousKey?: string) =>
    engine<{ ok: boolean; problems: Problem[] }>("resource.check", { target, resource, previousKey }),
  lint: () => engine<Lint>("repo.lint"),
  resources: (target: string) => engine<{ resources: ResourceEntry[] }>("resources.list", { target }).then((r) => r.resources),
  writeResource: (target: string, resource: Resource, previousKey?: string) =>
    engine<{ file: string; removed: string[] }>("resource.write", { target, resource, previousKey }),
  deleteResource: (target: string, key: string) => engine<{ file: string; removed: boolean }>("resource.delete", { target, key }),

  configChanges: () => call<ConfigChange[]>("config_changes"),
  workingDiff: (path: string) => call<string>("working_diff", { path }),
  discardChange: (path: string) => call<void>("discard_change", { path }),
  history: (path: string, limit = 100) => call<Commit[]>("file_history", { path, limit }),
  commitDiff: (sha: string, path: string) => call<string>("commit_diff", { sha, path }),
  propose: (proposal: Proposal) => call<ProposeResult>("propose", { proposal }),
  pulls: () => call<PullRequest[]>("github_pulls"),
  pullDetails: (number: number, sha: string) => call<PullDetails>("github_pull_details", { number, sha }),
  drift: (workflow = "drift.yml", limit = 21) => call<DriftData>("github_drift", { workflow, limit }),
  runs: () => call<RunsData>("github_runs"),
  dispatch: (workflow: string, gitRef: string) => call<void>("github_dispatch", { workflow, gitRef }),
  openUrl: (url: string) => (inDesktop ? call<void>("open_url", { url }) : Promise.resolve(void window.open(url, "_blank", "noopener"))),

  pickFolder: async (): Promise<string | null> => {
    if (!inDesktop) return demo.pickFolder();
    const dialog = await import("@tauri-apps/plugin-dialog");
    const picked = await dialog.open({ directory: true, multiple: false, title: "Open a GitOps repository" });
    return typeof picked === "string" ? picked : null;
  },

  on: (event: "repo-changed" | "engine-notification", handler: (payload: unknown) => void) => listenImpl(event, handler),
};
