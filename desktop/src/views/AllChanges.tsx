import { useState } from "react";
import { health } from "../lib/model";
import { PlanView } from "../components/PlanView";
import { Counts, Empty, ErrorBox, HealthBadge } from "../components/ui";
import { useRepo } from "../state";

/** Every target's plan on one page: what applying everything would change, with the totals. */
export function AllChanges() {
  const { targets, plans, planAll, go, repo } = useRepo();
  const [open, setOpen] = useState<Set<string>>(new Set());
  const list = targets.state === "ok" ? targets.value : [];
  const withChanges = list.filter((t) => {
    const p = plans[t.name];
    return p?.state === "ok" && p.value.hasChanges;
  });
  const failing = list.filter((t) => plans[t.name]?.state === "error");
  const totals = { create: 0, update: 0, delete: 0 };
  for (const t of withChanges) {
    const p = plans[t.name];
    if (p?.state === "ok") {
      totals.create += p.value.summary.create;
      totals.update += p.value.summary.update;
      totals.delete += p.value.summary.delete;
    }
  }
  return (
    <div className="page">
      <header className="page-head">
        <div>
          <h1>All changes</h1>
          <p className="muted">What would change in every live system to match Git on <code>{repo.git.branch}</code>, as one plan.</p>
        </div>
        <button onClick={planAll}>Plan all</button>
      </header>
      <div className="plan-summary">
        <Counts summary={totals} /> <span className="muted">across {withChanges.length} of {list.length} targets</span>
      </div>
      {failing.map((t) => {
        const p = plans[t.name];
        return p?.state === "error" ? <ErrorBox key={t.name} error={{ ...p.error, message: `${t.name}: ${p.error.message}` }} /> : null;
      })}
      {withChanges.length === 0 && failing.length === 0 && <Empty title="Nothing to change">Every planned target matches Git.</Empty>}
      {withChanges.map((t) => {
        const p = plans[t.name];
        if (p?.state !== "ok") return null;
        const expanded = open.has(t.name);
        return (
          <section key={t.name} className="combined-target">
            <button className="combined-head" aria-expanded={expanded}
              onClick={() => setOpen((s) => { const n = new Set(s); if (n.has(t.name)) n.delete(t.name); else n.add(t.name); return n; })}>
              <strong>{t.name}</strong> <span className="muted small">{t.kind} · {t.endpoint}</span>
              <HealthBadge health={health(p)} /> <Counts summary={p.value.summary} />
              <span className="chevron" aria-hidden>{expanded ? "▾" : "▸"}</span>
            </button>
            {expanded && (
              <div className="combined-body">
                <PlanView plan={p.value} />
                <button className="link small" onClick={() => go({ name: "target", target: t.name })}>Open {t.name}</button>
              </div>
            )}
          </section>
        );
      })}
    </div>
  );
}
