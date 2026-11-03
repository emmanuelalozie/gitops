import { useEffect, useMemo, useState } from "react";
import { api } from "../lib/api";
import { type Load } from "../lib/model";
import type { AppError, Plan } from "../lib/types";
import { PlanView } from "../components/PlanView";
import { Empty, ErrorBox, Spinner } from "../components/ui";
import { useRepo } from "../state";

/**
 * Two targets of the same kind side by side, in Git (dev vs prod users, say). The engine works it out as a plan
 * that would make `to` look like `from`, so "create" is only in from, "delete" only in to.
 */
export function Compare() {
  const { targets, revision } = useRepo();
  const list = targets.state === "ok" ? targets.value : [];
  const kinds = useMemo(() => {
    const byKind = new Map<string, string[]>();
    list.forEach((t) => byKind.set(t.kind, [...(byKind.get(t.kind) ?? []), t.name]));
    return [...byKind].filter(([, names]) => names.length > 1);
  }, [list]);
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [result, setResult] = useState<Load<Plan>>({ state: "idle" });

  useEffect(() => {
    const first = kinds[0]?.[1];
    if (!from && first && first.length > 1) {
      setFrom(first.includes("dev") ? "dev" : first[0]!);
      setTo(first.includes("prod") ? "prod" : first[1]!);
    }
  }, [kinds, from]);
  useEffect(() => {
    if (!from || !to || from === to) return;
    setResult({ state: "loading" });
    api.compare(from, to).then((value) => setResult({ state: "ok", value, at: Date.now() }), (error: AppError) => setResult({ state: "error", error }));
  }, [from, to, revision]);

  const kindOf = (name: string) => list.find((t) => t.name === name)?.kind;

  return (
    <div className="page">
      <header className="page-head">
        <div>
          <h1>Compare</h1>
          <p className="muted">The Git config of two targets of the same kind. Nothing live is read.</p>
        </div>
      </header>
      {kinds.length === 0 ? (
        <Empty title="Nothing to compare">Comparing needs two targets of the same kind, such as dev and prod users.</Empty>
      ) : (
        <>
          <div className="toolbar start">
            <label className="inline">From <select value={from} onChange={(e) => { setFrom(e.target.value); if (kindOf(e.target.value) !== kindOf(to)) setTo(""); }}>
              {list.filter((t) => kinds.some(([k]) => k === t.kind)).map((t) => <option key={t.name} value={t.name}>{t.name} ({t.kind})</option>)}
            </select></label>
            <span aria-hidden>→</span>
            <label className="inline">To <select value={to} onChange={(e) => setTo(e.target.value)}>
              <option value="" disabled>Choose…</option>
              {list.filter((t) => t.name !== from && t.kind === kindOf(from)).map((t) => <option key={t.name} value={t.name}>{t.name}</option>)}
            </select></label>
          </div>
          {result.state === "loading" && <Spinner />}
          {result.state === "error" && <ErrorBox error={result.error} />}
          {result.state === "ok" && (
            <>
              <p className="muted small">What would change in <code>{to}</code>'s Git config to make it match <code>{from}</code>.</p>
              <PlanView plan={result.value} liveLabel={to} desiredLabel={from} />
            </>
          )}
        </>
      )}
    </div>
  );
}
