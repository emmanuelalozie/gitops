import { useEffect, useState } from "react";
import { api, asAppError } from "../lib/api";
import { columns, formatValue, health, hintFor, relativeTime, type Load } from "../lib/model";
import type { Resource, Target } from "../lib/types";
import { PlanView } from "../components/PlanView";
import { Empty, ErrorBox, HealthBadge, Hint, Spinner, Tabs, Tag } from "../components/ui";
import { useRepo } from "../state";
import { ApplyDialog } from "../components/ApplyDialog";
import { MockControl } from "../components/MockControl";
import { ConfigTab } from "./ConfigTab";
import { HistoryTab } from "./HistoryTab";

type Tab = "plan" | "config" | "live" | "history";

export function TargetPage({ name }: { name: string }) {
  const { targets, plans, planTarget, page, go, repo } = useRepo();
  const target = targets.state === "ok" ? targets.value.find((t) => t.name === name) : undefined;
  if (!target) return <Empty title={`No target '${name}'`}>It may have been removed from config/.</Empty>;
  const plan = plans[name];
  const tab = ((page.name === "target" && page.tab) || "plan") as Tab;
  const [applying, setApplying] = useState(false);
  const canApply = target.localApply && plan?.state === "ok" && plan.value.hasChanges && !plan.value.skipped && plan.value.planId;

  return (
    <div className="page">
      <header className="page-head">
        <div>
          <h1>
            {target.name} <HealthBadge health={health(plan)} />
          </h1>
          <p className="muted">{target.description}</p>
          <TargetFacts target={target} />
          {target.mock && <MockControl target={target} />}
        </div>
        <div className="head-actions">
          {canApply && <button className="danger" onClick={() => setApplying(true)}>Apply…</button>}
          <button onClick={() => void planTarget(name)} disabled={plan?.state === "loading"}>Re-plan</button>
        </div>
      </header>
      {applying && plan?.state === "ok" && (
        <ApplyDialog target={target} plan={plan.value} onClose={(applied) => { setApplying(false); if (applied) void planTarget(name); }} />
      )}
      <Tabs<Tab>
        tabs={[
          { id: "plan", label: "Plan" },
          { id: "config", label: "Git config" },
          { id: "live", label: "Live state" },
          { id: "history", label: "History" },
        ]}
        value={tab}
        onChange={(t) => go({ name: "target", target: name, tab: t })}
      />
      {tab === "plan" && (
        <section>
          {(!plan || plan.state === "idle") && <Empty title="Not planned yet" />}
          {plan?.state === "loading" && (plan.previous ? <div className="stale"><Spinner label="Re-planning…" /><PlanView plan={plan.previous} /></div> : <Spinner label="Asking the live system…" />)}
          {plan?.state === "error" && <ErrorBox error={plan.error} action={<Hint text={hintFor(plan.error, target)} />} />}
          {plan?.state === "ok" && (
            <>
              <p className="muted small">
                Planned {relativeTime(plan.at)} against <code>{target.endpoint}</code>.
                {!target.localApply && plan.value.hasChanges && " This target changes through pull requests: merge to apply, or start the Apply workflow."}
              </p>
              <PlanView plan={plan.value} repoRoot={repo.path} />
            </>
          )}
        </section>
      )}
      {tab === "config" && <ConfigTab target={target} />}
      {tab === "live" && <LiveState target={target} />}
      {tab === "history" && <HistoryTab target={target} />}
    </div>
  );
}

function TargetFacts({ target }: { target: Target }) {
  const [health, setHealth] = useState<string | null>(null);
  const check = async () => {
    setHealth("checking…");
    try {
      const r = await api.healthCheck(target.name);
      setHealth(r ? `${r.ok ? "healthy" : "unhealthy"}: ${r.message}` : "no health check");
    } catch (e) {
      setHealth((e as { message: string }).message);
    }
  };
  return (
    <div className="facts">
      {target.environment && target.workflows && <Tag title="The GitHub environment whose reviewers approve the Apply workflow for this target">approved in {target.environment}</Tag>}
      {target.owners && target.owners.length > 0 && <Tag title="Reviewers of changes to this target (CODEOWNERS)">owners {target.owners.join(" ")}</Tag>}
      {target.applyWindow && (
        <Tag tone={target.applyWindow.open ? "ok" : "warn"} title={`Next opening ${new Date(target.applyWindow.nextOpening).toLocaleString()}`}>
          applies {target.applyWindow.describe} ({target.applyWindow.open ? "open now" : "closed"})
        </Tag>
      )}
      {target.healthCheck && (
        <Tag title={target.healthCheck}>
          health check <button className="link small" onClick={() => void check()}>check now</button>{health && ` · ${health}`}
        </Tag>
      )}
      <Tag>{target.kind}</Tag>
      <Tag title={target.endpointOverridden ? `Overridden by ${target.endpointVariable}` : undefined}>
        {target.endpoint}{target.endpointOverridden ? " (overridden)" : ""}
      </Tag>
      <Tag tone={target.prune ? "warn" : "neutral"}>{target.prune ? "prune on" : "prune off"}</Tag>
      <Tag>{target.verifyAfterApply ? "verifies after apply" : "no verification"}</Tag>
      <Tag tone={target.localApply ? "info" : "neutral"}>{target.localApply ? "apply from this app" : "applies through pull requests"}</Tag>
      {target.tokenVariable && (
        <Tag tone={target.tokenPresent ? "ok" : target.tokenRequired ? "danger" : "neutral"}>
          {target.tokenVariable}: {target.tokenPresent ? "set" : "not set"}
        </Tag>
      )}
    </div>
  );
}

/** Everything the live system returns; the fields Git manages come first and are marked. */
function LiveState({ target }: { target: Target }) {
  const [live, setLive] = useState<Load<Resource[]>>({ state: "idle" });
  const [managed, setManaged] = useState<string[]>([]);

  const fetch = () => {
    setLive({ state: "loading" });
    api.fetchLive(target.name).then(
      (value) => setLive({ state: "ok", value, at: Date.now() }),
      (error) => setLive({ state: "error", error: asAppError(error) }),
    );
  };
  useEffect(() => {
    void api.schema(target.name).then((s) => setManaged(Object.keys((s.properties as Record<string, unknown>) ?? {}))).catch(() => undefined);
  }, [target.name]);

  if (live.state === "idle") {
    return (
      <Empty title="Live state isn't loaded">
        <p>This reads everything the live system returns for {target.kind}.</p>
        <button onClick={fetch}>Fetch live state</button>
      </Empty>
    );
  }
  if (live.state === "loading") return <Spinner label="Fetching…" />;
  if (live.state === "error") return <ErrorBox error={live.error} action={<button onClick={fetch}>Try again</button>} />;
  const cols = columns(live.value, managed);
  return (
    <section>
      <p className="muted small">
        {live.value.length} {target.kind}, fetched {relativeTime(live.at)}. <span className="managed-key">Managed</span> columns are compared with Git; the rest are ignored.{" "}
        <button className="link" onClick={fetch}>Refresh</button>
      </p>
      <div className="table-wrap">
        <table className="grid">
          <thead>
            <tr>{cols.map((c) => <th key={c} className={managed.includes(c) ? "managed" : undefined}>{c}</th>)}</tr>
          </thead>
          <tbody>
            {live.value.map((r, i) => (
              <tr key={i}>
                {cols.map((c) => (
                  <td key={c} className={managed.includes(c) ? "managed" : undefined}>
                    <code>{c in r ? (typeof r[c] === "string" ? String(r[c]) : formatValue(r[c])) : ""}</code>
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}
