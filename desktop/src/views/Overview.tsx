import { health, hintFor, relativeTime } from "../lib/model";
import type { Target } from "../lib/types";
import { Counts, ErrorBox, HealthBadge, Hint, Spinner, Tag } from "../components/ui";
import { useRepo } from "../state";
import { MockControl } from "../components/MockControl";

export function Overview() {
  const { targets, plans, planAll, go } = useRepo();
  if (targets.state === "loading" || targets.state === "idle") return <Spinner label="Loading targets…" />;
  if (targets.state === "error") return <ErrorBox error={targets.error} />;

  const counts: Record<string, number> = {};
  for (const t of targets.value) {
    const h = health(plans[t.name]);
    counts[h] = (counts[h] ?? 0) + 1;
  }
  const extra = (["skipped", "error", "loading"] as const)
    .filter((h) => counts[h])
    .map((h) => ` · ${counts[h]} ${h === "loading" ? "planning" : h === "error" ? "failed" : "skipped"}`)
    .join("");

  return (
    <div className="page">
      <header className="page-head">
        <div>
          <h1>Overview</h1>
          <p className="muted">
            {targets.value.length} targets · {counts["in-sync"] ?? 0} in sync · {counts.drift ?? 0} with changes{extra}
          </p>
        </div>
        <div className="head-actions">
          <button onClick={() => go({ name: "add-target" })}>Add target…</button>
          <button onClick={planAll}>Plan all</button>
        </div>
      </header>
      <div className="cards">
        {targets.value.map((t) => <TargetCard key={t.name} target={t} />)}
      </div>
    </div>
  );
}

function TargetCard({ target }: { target: Target }) {
  const { plans, go, planTarget } = useRepo();
  const plan = plans[target.name];
  const h = health(plan);
  return (
    <article className={`card health-border-${h}`}>
      <header className="card-head">
        <button className="link card-title" onClick={() => go({ name: "target", target: target.name })}>{target.name}</button>
        <HealthBadge health={h} />
      </header>
      <div className="card-sub">
        <span>{target.kind}</span> · <code title={target.endpoint ?? ""}>{target.endpoint ?? "no endpoint"}</code>
      </div>
      <div className="card-body">
        {plan?.state === "ok" && !plan.value.skipped && <Counts summary={plan.value.summary} />}
        {plan?.state === "ok" && plan.value.skipped && <span className="muted">{plan.value.skipReason}</span>}
        {plan?.state === "ok" && plan.value.unmanaged.length > 0 && (
          <span className="muted">{plan.value.unmanaged.length} unmanaged</span>
        )}
        {plan?.state === "error" && (
          <div>
            <span className="error-text" title={plan.error.message}>{plan.error.code}: {plan.error.message.split("\n")[0]}</span>
            <Hint text={hintFor(plan.error, target)} />
          </div>
        )}
        {plan?.state === "loading" && <Spinner />}
      </div>
      {target.mock && <MockControl target={target} compact />}
      <footer className="card-foot">
        <div className="tags">
          <Tag>{target.resources} in Git</Tag>
          {target.prune && <Tag tone="warn" title="Live resources that aren't in Git are deleted">prune</Tag>}
          {target.localApply && <Tag tone="info" title="Can be applied from this app">local apply</Tag>}
          {!target.workflows && <Tag title="The plan, apply and drift workflows skip this target">no workflows</Tag>}
          {target.tokenVariable && (
            <Tag tone={target.tokenPresent ? "ok" : target.tokenRequired ? "danger" : "neutral"} title={`Token: ${target.tokenVariable}`}>
              {target.tokenPresent ? "token set" : "no token"}
            </Tag>
          )}
        </div>
        <span className="muted small">
          {plan?.state === "ok" ? `planned ${relativeTime(plan.at)}` : ""}
          <button className="link" onClick={() => void planTarget(target.name)} disabled={plan?.state === "loading"}>Re-plan</button>
        </span>
      </footer>
    </article>
  );
}
