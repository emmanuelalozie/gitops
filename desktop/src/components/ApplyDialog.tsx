import { useEffect, useRef, useState } from "react";
import { api } from "../lib/api";
import { foldProgress, stepLabel } from "../lib/apply";
import { shortId } from "../lib/model";
import type { AppError, ApplyProgress, ApplyResult, Plan, Target } from "../lib/types";
import { Counts, ErrorBox, Spinner } from "./ui";

/**
 * Applies the plan the person is looking at, and only that plan: the engine refuses if the live system moved
 * since (plan_changed). Only targets with "localApply" get here, and only after typing the target's name.
 */
export function ApplyDialog({ target, plan, onClose }: { target: Target; plan: Plan; onClose: (applied: boolean) => void }) {
  const [typed, setTyped] = useState("");
  const [phase, setPhase] = useState<"confirm" | "running" | "done">("confirm");
  const [events, setEvents] = useState<ApplyProgress[]>([]);
  const [result, setResult] = useState<ApplyResult | null>(null);
  const [error, setError] = useState<AppError | null>(null);
  const dialog = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    dialog.current?.showModal?.();
  }, []);
  useEffect(() => {
    const off = api.on("engine-notification", (payload) => {
      const { method, params } = payload as { method: string; params: ApplyProgress };
      if (method === "apply.progress" && params.target === target.name) setEvents((e) => [...e, params]);
    });
    return () => void off.then((f) => f());
  }, [target.name]);

  const run = async () => {
    setPhase("running");
    try {
      setResult(await api.apply(target.name, plan.planId!));
    } catch (e) {
      setError(e as AppError);
    } finally {
      setPhase("done");
    }
  };

  const rows = foldProgress(events);
  const close = () => {
    dialog.current?.close?.();
    onClose(result?.applied ?? events.length > 0);
  };

  return (
    <dialog ref={dialog} className="dialog" aria-labelledby="apply-title" onCancel={(e) => { e.preventDefault(); if (phase !== "running") close(); }}>
      <h2 id="apply-title">Apply to {target.name}</h2>
      <p className="muted">
        <code>{target.endpoint}</code> · plan <code title={plan.planId ?? ""}>{shortId(plan.planId)}</code> · <Counts summary={plan.summary} />
      </p>
      {phase === "confirm" && (
        <form onSubmit={(e) => { e.preventDefault(); void run(); }}>
          <ul className="apply-list">
            {plan.changes.map((c) => (
              <li key={`${c.action}:${c.key}`} className={`action-${c.action}`}>
                <span className="verb">{c.action}</span> <code>{c.key}</code>
                {c.replace && <span className="muted small"> (deleted and created again)</span>}
                {c.action === "update" && !c.replace && <span className="muted small"> {c.diffs.map((d) => d.field).join(", ")}</span>}
              </li>
            ))}
          </ul>
          <p className="small">
            This changes the live system now, bypassing pull requests. If anything changed live since this plan was made, nothing is applied.
            {target.verifyAfterApply ? " Afterwards the engine plans again to check that it worked." : ""}
          </p>
          <label className="confirm-input">
            Type <code>{target.name}</code> to confirm
            <input value={typed} onChange={(e) => setTyped(e.target.value)} autoFocus spellCheck={false} autoComplete="off" />
          </label>
          <div className="editor-actions">
            <button type="submit" className="danger" disabled={typed !== target.name}>Apply {plan.changes.length} {plan.changes.length === 1 ? "change" : "changes"}</button>
            <button type="button" onClick={close}>Cancel</button>
          </div>
        </form>
      )}
      {phase !== "confirm" && (
        <>
          <ul className="apply-list">
            {rows.map((r) => (
              <li key={r.key}>
                <code>{r.key}</code>{" "}
                {r.steps.map((s) => (
                  <span key={s.step} className={`step step-${s.state}`}>
                    {stepLabel[s.step]} {s.state === "starting" ? "…" : s.state === "finished" ? "✓" : "✗"}
                  </span>
                ))}
              </li>
            ))}
          </ul>
          {phase === "running" && <Spinner label="Applying…" />}
          {result && result.verified && <p className="ok-text">Applied and verified: the live system matches Git.</p>}
          {result && result.applied && !result.verifyAfterApply && <p className="muted">Applied. This target doesn't verify after applying.</p>}
          {result && result.applied && result.verifyAfterApply && !result.verified && (
            <ErrorBox error={{ kind: "engine", code: "not_converged", message: `The apply ran but ${result.after?.changes.length ?? "some"} changes remain. The live system may ignore some writes.` }} />
          )}
          {error && (
            <ErrorBox
              error={error}
              action={error.code === "plan_changed" ? <p className="small">Close this and review the new plan.</p> : events.length > 0 ? <p className="small">Some steps ran before the failure; re-plan to see where things stand.</p> : undefined}
            />
          )}
          {phase === "done" && <div className="editor-actions"><button className="primary" onClick={close}>Close</button></div>}
        </>
      )}
    </dialog>
  );
}
