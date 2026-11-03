import { useEffect, useState } from "react";
import { api } from "../lib/api";
import { health, type Health, type Load } from "../lib/model";
import type { AppError, Plan, Target, WorkspaceEntry } from "../lib/types";
import { ErrorBox, HealthBadge, Spinner } from "../components/ui";
import { useWorkspace } from "../workspace";

interface RepoRow {
  entry: WorkspaceEntry;
  targets: Load<Target[]>;
  plans: Record<string, Load<Plan>>;
}

/** Every open repository and the state of each of its targets, planned side by side. */
export function WorkspacePage() {
  const workspace = useWorkspace();
  const [rows, setRows] = useState<RepoRow[] | null>(null);
  const [error, setError] = useState<AppError | null>(null);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const entries = await api.workspace();
        const initial: RepoRow[] = entries.map((entry) => ({ entry, targets: { state: "loading" }, plans: {} }));
        if (!cancelled) setRows(initial);
        await Promise.all(entries.map(async (entry, i) => {
          try {
            const targets = await api.workspaceTargets(entry.path);
            if (cancelled) return;
            setRows((r) => r && r.map((row, j) => (j === i ? { ...row, targets: { state: "ok", value: targets, at: Date.now() } } : row)));
            await Promise.all(targets.map(async (t) => {
              let plan: Load<Plan>;
              try {
                plan = { state: "ok", value: await api.workspacePlan(entry.path, t.name), at: Date.now() };
              } catch (e) {
                plan = { state: "error", error: e as AppError };
              }
              if (!cancelled) setRows((r) => r && r.map((row, j) => (j === i ? { ...row, plans: { ...row.plans, [t.name]: plan } } : row)));
            }));
          } catch (e) {
            if (!cancelled) setRows((r) => r && r.map((row, j) => (j === i ? { ...row, targets: { state: "error", error: e as AppError } } : row)));
          }
        }));
      } catch (e) {
        setError(e as AppError);
      }
    })();
    return () => { cancelled = true; };
  }, []);

  if (error) return <div className="page"><ErrorBox error={error} /></div>;
  if (!rows) return <Spinner />;
  const count = (row: RepoRow, h: Health) => Object.values(row.plans).filter((p) => health(p) === h).length;
  return (
    <div className="page">
      <header className="page-head">
        <div>
          <h1>Workspace</h1>
          <p className="muted">{rows.length} open repositories, each planned against its live systems.</p>
        </div>
        <button onClick={() => void workspace.openAnother()}>Open another…</button>
      </header>
      {rows.map((row) => (
        <section key={row.entry.path} className="workspace-repo">
          <div className="workspace-head">
            <button className="link" onClick={() => void workspace.switchTo(row.entry.path)}><strong>{row.entry.name}</strong></button>
            <span className="muted small">{row.entry.git?.branch ?? ""} · {row.entry.path}</span>
            {row.entry.active && <span className="tag tone-info">on screen</span>}
            <span className="muted small push-right">{count(row, "drift")} with changes · {count(row, "error")} failing</span>
          </div>
          {row.targets.state === "loading" && <Spinner />}
          {row.targets.state === "error" && <ErrorBox error={row.targets.error} />}
          {row.targets.state === "ok" && (
            <div className="workspace-targets">
              {row.targets.value.map((t) => (
                <div key={t.name} className="workspace-target">
                  <span>{t.name}</span> <span className="muted small">{t.kind}</span> <HealthBadge health={health(row.plans[t.name])} />
                </div>
              ))}
            </div>
          )}
        </section>
      ))}
    </div>
  );
}
