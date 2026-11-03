import { useState } from "react";
import { api } from "../lib/api";
import type { Blame } from "../lib/types";
import { formatValue, shortId } from "../lib/model";
import type { Change, Plan, Resource } from "../lib/types";
import { Counts, Tag } from "./ui";

const verbs = { create: "Create", update: "Update", delete: "Delete" } as const;
const symbols = { create: "+", update: "~", delete: "−" } as const;

/**
 * A plan as the engine computed it. Nothing here works out a difference itself: every row is a change or a
 * field diff from the engine. `liveLabel`/`desiredLabel` rename the sides for comparisons between targets.
 */
export function PlanView({ plan, liveLabel = "Live", desiredLabel = "Git", repoRoot }: { plan: Plan; liveLabel?: string; desiredLabel?: string; repoRoot?: string }) {
  if (plan.skipped) {
    return <p className="muted">{plan.skipReason}</p>;
  }
  return (
    <div className="plan">
      <div className="plan-summary">
        {plan.hasChanges ? <Counts summary={plan.summary} /> : <span className="ok-text">No changes. {liveLabel} matches {desiredLabel}.</span>}
        {plan.planId && !plan.from && (
          <span className="muted" title={plan.planId}>
            plan <code>{shortId(plan.planId)}</code>
          </span>
        )}
      </div>
      {plan.changes.length > 0 && (
        <ul className="changes">
          {plan.changes.map((change) => (
            <ChangeRow key={`${change.action}:${change.key}`} change={change} liveLabel={liveLabel} desiredLabel={desiredLabel} repoRoot={repoRoot} />
          ))}
        </ul>
      )}
      {plan.unmanaged.length > 0 && (
        <details className="unmanaged">
          <summary>
            {plan.unmanaged.length} {plan.unmanaged.length === 1 ? "resource exists" : "resources exist"} live but not in Git, left alone because prune is off
          </summary>
          <div className="chips">
            {plan.unmanaged.map((key) => <code key={key}>{key}</code>)}
          </div>
        </details>
      )}
    </div>
  );
}

function ChangeRow({ change, liveLabel, desiredLabel, repoRoot }: { change: Change; liveLabel: string; desiredLabel: string; repoRoot?: string }) {
  const [open, setOpen] = useState(change.action === "update");
  const [why, setWhy] = useState<Record<string, Blame | string> | null>(null);
  const explain = async () => {
    const path = relativeTo(change.file!, repoRoot!);
    const entries = await Promise.all(change.diffs.map(async (d) => {
      try {
        return [d.field, await api.blameField(path, d.field)] as const;
      } catch (e) {
        return [d.field, (e as { message: string }).message] as const;
      }
    }));
    setWhy(Object.fromEntries(entries));
  };
  return (
    <li className={`change action-${change.action}`}>
      <button className="change-head" aria-expanded={open} onClick={() => setOpen(!open)}>
        <span className="symbol" aria-hidden>{symbols[change.action]}</span>
        <span className="verb">{verbs[change.action]}</span>
        <code className="key">{change.key}</code>
        {change.replace && <Tag tone="warn" title="This change can't be made in place: the resource is deleted and created again">replaces</Tag>}
        {change.action === "update" && (
          <span className="muted fields">{change.diffs.map((d) => d.field).join(", ")}</span>
        )}
        <span className="chevron" aria-hidden>{open ? "▾" : "▸"}</span>
      </button>
      {open && (
        <table className="diff">
          <thead>
            <tr><th>Field</th><th>{liveLabel}</th><th>{desiredLabel}</th></tr>
          </thead>
          <tbody>
            {rows(change).map((row) => (
              <tr key={row.field} className={row.changed ? "changed" : undefined}>
                <th scope="row"><code>{row.field}</code></th>
                <td className="side-live"><code>{row.live}</code></td>
                <td className="side-desired"><code>{row.desired}</code></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {open && change.action === "update" && change.file && repoRoot && (
        <div className="why">
          {!why && <button className="link small" onClick={() => void explain()}>Why does Git say this?</button>}
          {why && change.diffs.map((d) => {
            const b = why[d.field];
            return (
              <p key={d.field} className="small">
                <code>{d.field}</code>:{" "}
                {typeof b === "string" ? <span className="muted">{b}</span>
                  : b?.commit ? <>set to this in <code>{b.commit.short}</code> by {b.commit.author}, {new Date(b.commit.date).toLocaleDateString()}: “{b.commit.subject}”{b.commit.pullRequest ? ` (#${b.commit.pullRequest})` : ""}. Live differs, so it was changed outside Git since, or never applied.</>
                  : <span className="muted">an uncommitted edit in your working tree.</span>}
              </p>
            );
          })}
        </div>
      )}
    </li>
  );
}

interface Row { field: string; live: string; desired: string; changed: boolean }

/** Updates show every managed field with the differing ones marked; creates and deletes show the one side. */
export function rows(change: Change): Row[] {
  const diffs = new Map(change.diffs.map((d) => [d.field, d]));
  const fields = new Set<string>([...Object.keys(change.desired ?? {}), ...Object.keys(change.live ?? {})]);
  const value = (r: Resource | null, f: string) => (r === null ? "" : formatValue(r[f], !(f in r)));
  return [...fields].map((field) => {
    const diff = diffs.get(field);
    if (diff) return { field, live: formatValue(diff.live, diff.liveMissing), desired: formatValue(diff.desired), changed: true };
    return { field, live: value(change.live, field), desired: value(change.desired, field), changed: change.action !== "update" };
  });
}

function relativeTo(path: string, root: string): string {
  const p = path.replace(/\\/g, "/");
  const r = root.replace(/\\/g, "/").replace(/\/+$/, "");
  return p.startsWith(r + "/") ? p.slice(r.length + 1) : p;
}
