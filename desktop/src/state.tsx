// The open repository and everything loaded from it, shared by every view.

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { api, asAppError } from "./lib/api";
import { affectedTargets, type Load } from "./lib/model";
import type { DriftIssue, Lint, Plan, RepoInfo, Target, Validation, WatchSettings } from "./lib/types";
import { alerts, snapshot, type Snapshot } from "./lib/watch";

export type Page =
  | { name: "overview" }
  | { name: "target"; target: string; tab?: string }
  | { name: "problems" }
  | { name: "changes" }
  | { name: "compare" }
  | { name: "github"; tab?: string }
  | { name: "graph" }
  | { name: "add-target" }
  | { name: "all-changes" }
  | { name: "audit" }
  | { name: "search"; query: string }
  | { name: "settings" };

interface RepoState {
  repo: RepoInfo;
  targets: Load<Target[]>;
  plans: Record<string, Load<Plan>>;
  validation: Load<Validation>;
  lint: Load<Lint>;
  /** How many files under config/ have uncommitted changes. */
  changeCount: number;
  page: Page;
  go: (page: Page) => void;
  planTarget: (name: string) => Promise<Load<Plan>>;
  planAll: () => void;
  reload: () => Promise<void>;
  setRepo: (repo: RepoInfo) => void;
  close: () => void;
  /** Bumped whenever files under config/ change, so views showing file contents reload them. */
  revision: number;
  /** A pull request being prepared elsewhere (a promotion), which the Changes page starts from. */
  draft: Draft | null;
  setDraft: (draft: Draft | null) => void;
  /** Re-plan on a schedule and notify on drift (Settings). */
  watch: WatchSettings;
  setWatch: (enabled: boolean, minutes: number) => Promise<void>;
  /** When the watch last looked, if it has. */
  lastWatch: number | null;
}

export interface Draft {
  title: string;
  body: string;
  labels: string[];
  paths: string[];
}

const Context = createContext<RepoState | null>(null);

export function useRepo(): RepoState {
  const value = useContext(Context);
  if (!value) throw new Error("useRepo outside RepoProvider");
  return value;
}

async function load<T>(promise: Promise<T>): Promise<Load<T>> {
  try {
    return { state: "ok", value: await promise, at: Date.now() };
  } catch (error) {
    return { state: "error", error: asAppError(error) };
  }
}

export function RepoProvider({ initial, onClose, children }: { initial: RepoInfo; onClose: () => void; children: ReactNode }) {
  const [repo, setRepo] = useState(initial);
  const [targets, setTargets] = useState<Load<Target[]>>({ state: "loading" });
  const [plans, setPlans] = useState<Record<string, Load<Plan>>>({});
  const [validation, setValidation] = useState<Load<Validation>>({ state: "loading" });
  const [lint, setLint] = useState<Load<Lint>>({ state: "loading" });
  const [changeCount, setChangeCount] = useState(0);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [page, setPage] = useState<Page>({ name: "overview" });
  const [revision, setRevision] = useState(0);
  const targetsRef = useRef<Target[]>([]);

  const planTarget = useCallback(async (name: string): Promise<Load<Plan>> => {
    setPlans((p) => ({ ...p, [name]: { state: "loading", previous: p[name]?.state === "ok" ? p[name].value : undefined } }));
    const result = await load(api.plan(name));
    setPlans((p) => ({ ...p, [name]: result }));
    return result;
  }, []);

  const planAll = useCallback(() => {
    targetsRef.current.forEach((t) => void planTarget(t.name));
  }, [planTarget]);

  const reload = useCallback(async () => {
    const [t, v, l, c] = await Promise.all([load(api.targets()), load(api.validate()), load(api.lint()), load(api.configChanges())]);
    if (t.state === "ok") targetsRef.current = t.value;
    setTargets(t);
    setValidation(v);
    setLint(l);
    setChangeCount(c.state === "ok" ? c.value.length : 0);
    setRevision((r) => r + 1);
  }, []);

  // First load: everything, then plan every target (plans read the live systems, so they come last).
  useEffect(() => {
    void reload().then(planAll);
  }, [reload, planAll]);

  // Files changed (an edit, a branch switch): reload, then re-plan only the targets whose files changed.
  useEffect(() => {
    const off = api.on("repo-changed", (payload) => {
      const paths = (payload as { paths: string[] }).paths ?? [];
      const touched = affectedTargets(paths, targetsRef.current);
      void reload().then(() => touched.forEach((name) => void planTarget(name)));
      void api.repoStatus().then((git) => setRepo((r) => ({ ...r, git }))).catch(() => undefined);
    });
    return () => void off.then((f) => f());
  }, [reload, planTarget]);

  // The drift watch: on a schedule, plan every target and look at GitHub's drift issues, and notify about what
  // got worse (or recovered) since the last look. Runs while the app is open.
  const [watch, setWatchState] = useState<WatchSettings>({ enabled: false, minutes: 30 });
  const [lastWatch, setLastWatch] = useState<number | null>(null);
  const lastLook = useRef<Snapshot | null>(null);
  const hasGitHub = Boolean(repo.git.github);
  useEffect(() => {
    void api.watchSettings().then(setWatchState).catch(() => undefined);
  }, []);
  const setWatch = useCallback(async (enabled: boolean, minutes: number) => {
    setWatchState(await api.setWatchSettings(enabled, minutes));
  }, []);
  const targetsReady = targets.state === "ok";
  useEffect(() => {
    lastLook.current = null;
    if (!watch.enabled || !targetsReady) return;
    let cancelled = false;
    const look = async () => {
      const names = targetsRef.current.map((t) => t.name);
      const results: Record<string, Load<Plan>> = Object.fromEntries(await Promise.all(names.map(async (n) => [n, await planTarget(n)] as const)));
      const issues: DriftIssue[] | null = hasGitHub ? await api.drift("drift.yml", 1).then((d) => d.issues, () => null) : null;
      if (cancelled) return;
      for (const alert of alerts(lastLook.current, results, issues)) void api.notify(alert.title, alert.body).catch(() => undefined);
      lastLook.current = snapshot(results, issues);
      setLastWatch(Date.now());
    };
    void look();
    const timer = setInterval(() => void look(), watch.minutes * 60_000);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [watch.enabled, watch.minutes, targetsReady, hasGitHub, planTarget]);

  const value = useMemo<RepoState>(
    () => ({ repo, targets, plans, validation, lint, changeCount, page, go: setPage, planTarget, planAll, reload, setRepo, close: onClose, revision, draft, setDraft, watch, setWatch, lastWatch }),
    [repo, targets, plans, validation, lint, changeCount, page, planTarget, planAll, reload, onClose, revision, draft, watch, setWatch, lastWatch],
  );
  return <Context.Provider value={value}>{children}</Context.Provider>;
}
