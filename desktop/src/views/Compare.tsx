import { useEffect, useMemo, useState } from "react";
import { api } from "../lib/api";
import { repoRelative, type Load } from "../lib/model";
import type { AppError, Plan } from "../lib/types";
import { PlanView } from "../components/PlanView";
import { changeId, defaultChoice, promotionOps, promotionTitle } from "../lib/promote";
import { Empty, ErrorBox, Spinner } from "../components/ui";
import { useRepo } from "../state";

/**
 * Two targets of the same kind side by side, in Git (dev vs prod users, say). The engine works it out as a plan
 * that would make `to` look like `from`, so "create" is only in from, "delete" only in to.
 */
export function Compare() {
  const { targets, revision, go, setDraft, reload, repo } = useRepo();
  const [chosen, setChosen] = useState<Set<string>>(new Set());
  const [promoting, setPromoting] = useState(false);
  const [failures, setFailures] = useState<{ key: string; error: AppError }[]>([]);
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
    api.compare(from, to).then(
      (value) => {
        setResult({ state: "ok", value, at: Date.now() });
        setChosen(defaultChoice(value));
      },
      (error: AppError) => setResult({ state: "error", error }),
    );
  }, [from, to, revision]);

  const kindOf = (name: string) => list.find((t) => t.name === name)?.kind;

  /** Writes each chosen resource through the engine (which checks it against the schema and the target's other files). */
  const promote = async () => {
    if (result.state !== "ok") return;
    setPromoting(true);
    setFailures([]);
    const ops = promotionOps(result.value, chosen);
    const failed: { key: string; error: AppError }[] = [];
    const paths: string[] = [];
    for (const op of ops) {
      try {
        if (op.type === "write") {
          const written = await api.writeResource(to, op.resource, op.previousKey);
          paths.push(written.file, ...written.removed);
        } else {
          paths.push((await api.deleteResource(to, op.key)).file);
        }
      } catch (e) {
        failed.push({ key: op.key, error: e as AppError });
      }
    }
    setPromoting(false);
    setFailures(failed);
    await reload();
    const copied = ops.filter((o) => !failed.some((f) => f.key === o.key)).map((o) => o.key);
    if (copied.length > 0 && failed.length === 0) {
      setDraft({
        title: promotionTitle(from, to, copied),
        body: `Promotes ${copied.map((k) => `\`${k}\``).join(", ")} from \`${from}\` to \`${to}\`, as they are in \`${from}\`'s config.`,
        labels: ["promotion"],
        paths: paths.map((p) => repoRelative(p, repo.path)),
      });
      go({ name: "changes" });
    }
  };

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
              {result.value.changes.length > 0 && (
                <section className="promote">
                  <h2>Promote to {to}</h2>
                  <p className="muted small">Copies the chosen resources into <code>{to}</code>'s Git config. Nothing changes live until the pull request is merged.</p>
                  <ul className="promote-list">
                    {result.value.changes.map((c) => (
                      <li key={changeId(c)}>
                        <label>
                          <input type="checkbox" checked={chosen.has(changeId(c))}
                            onChange={(e) => setChosen((s) => { const n = new Set(s); if (e.target.checked) n.add(changeId(c)); else n.delete(changeId(c)); return n; })} />
                          {c.action === "delete" ? <>Remove <code>{c.key}</code> from {to}</> : c.action === "create" ? <>Add <code>{c.key}</code> to {to}</> : <>Update <code>{c.key}</code> in {to} ({c.diffs.map((d) => d.field).join(", ")})</>}
                        </label>
                      </li>
                    ))}
                  </ul>
                  {failures.map((f) => <ErrorBox key={f.key} error={{ ...f.error, message: `${f.key}: ${f.error.message}` }} />)}
                  <button className="primary" disabled={promoting || chosen.size === 0} onClick={() => void promote()}>
                    {promoting ? "Copying…" : `Copy ${chosen.size} to ${to}`}
                  </button>
                </section>
              )}
            </>
          )}
        </>
      )}
    </div>
  );
}
