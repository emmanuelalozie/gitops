import { useEffect, useState } from "react";
import { api } from "../lib/api";
import { checkSummary, driftTimeline, issueTarget, openDays, type Cell } from "../lib/github";
import { relativeTime, type Load } from "../lib/model";
import type { AppError, DriftData, PullDetails, PullRequest, RunsData } from "../lib/types";
import { Markdown } from "../components/Markdown";
import { Empty, ErrorBox, Spinner, Tabs, Tag } from "../components/ui";
import { useRepo } from "../state";

type Tab = "pulls" | "drift" | "workflows";

function useLoad<T>(fetch: () => Promise<T>, deps: unknown[]): [Load<T>, () => void] {
  const [state, setState] = useState<Load<T>>({ state: "loading" });
  const run = () => {
    setState((s) => ({ state: "loading", previous: s.state === "ok" ? s.value : undefined }));
    fetch().then((value) => setState({ state: "ok", value, at: Date.now() }), (error: AppError) => setState({ state: "error", error }));
  };
  useEffect(run, deps);
  return [state, run];
}

/** What GitHub knows: open pull requests with their plan, the drift record, and the workflows. */
export function GitHub() {
  const { repo, page, go } = useRepo();
  const tab = ((page.name === "github" && page.tab) || "pulls") as Tab;
  if (!repo.git.github) {
    return (
      <div className="page">
        <Empty title="No GitHub remote">This repository's origin isn't on GitHub, so there are no pull requests or workflows to show.</Empty>
      </div>
    );
  }
  return (
    <div className="page">
      <header className="page-head">
        <div>
          <h1>GitHub</h1>
          <p className="muted">
            <button className="link" onClick={() => void api.openUrl(`https://github.com/${repo.git.github!.owner}/${repo.git.github!.name}`)}>
              {repo.git.github.owner}/{repo.git.github.name}
            </button>
          </p>
        </div>
      </header>
      <Tabs<Tab>
        tabs={[{ id: "pulls", label: "Pull requests" }, { id: "drift", label: "Drift" }, { id: "workflows", label: "Workflows" }]}
        value={tab}
        onChange={(t) => go({ name: "github", tab: t })}
      />
      {tab === "pulls" && <Pulls />}
      {tab === "drift" && <Drift />}
      {tab === "workflows" && <Workflows />}
    </div>
  );
}

function Pulls() {
  const [pulls, reload] = useLoad(api.pulls, []);
  const [open, setOpen] = useState<number | null>(null);
  if (pulls.state === "loading") return <Spinner label="Asking GitHub…" />;
  if (pulls.state === "error") return <ErrorBox error={pulls.error} action={<button onClick={reload}>Try again</button>} />;
  if (pulls.state !== "ok") return null;
  if (pulls.value.length === 0) return <Empty title="No open pull requests" />;
  return (
    <ul className="pulls">
      {pulls.value.map((p) => (
        <li key={p.number} className="pull">
          <button className="pull-head" aria-expanded={open === p.number} onClick={() => setOpen(open === p.number ? null : p.number)}>
            <span className="pull-number">#{p.number}</span>
            <span className="pull-title">{p.title}</span>
            {p.draft && <Tag>draft</Tag>}
            <span className="muted small">{p.author} · <code>{p.branch}</code> → <code>{p.base}</code> · {relativeTime(Date.parse(p.updatedAt))}</span>
          </button>
          {open === p.number && <PullBody pull={p} />}
        </li>
      ))}
    </ul>
  );
}

function PullBody({ pull }: { pull: PullRequest }) {
  const [details] = useLoad<PullDetails>(() => api.pullDetails(pull.number, pull.sha), [pull.number, pull.sha]);
  if (details.state === "loading") return <div className="pull-body"><Spinner /></div>;
  if (details.state === "error") return <div className="pull-body"><ErrorBox error={details.error} /></div>;
  if (details.state !== "ok") return null;
  const d = details.value;
  const s = checkSummary(d.checks);
  const config = d.files.filter((f) => f.path.startsWith("config/"));
  return (
    <div className="pull-body">
      <div className="pull-meta">
        <span>
          Checks: <span className="ok-text">{s.passed} passed</span>
          {s.failed > 0 && <>, <span className="error-text">{s.failed} failed</span></>}
          {s.pending > 0 && <>, {s.pending} running</>}
        </span>
        <button className="link" onClick={() => void api.openUrl(pull.url)}>Open on GitHub</button>
      </div>
      <div className="checks">
        {d.checks.map((c) => (
          <Tag key={c.name} tone={c.status !== "completed" ? "info" : c.conclusion === "success" ? "ok" : c.conclusion === "skipped" || c.conclusion === "neutral" ? "neutral" : "danger"}>
            {c.name}
          </Tag>
        ))}
      </div>
      <p className="small muted">
        {config.length === 0 ? "Changes no config files." : <>Config files: {config.map((f) => <code key={f.path} className="file-chip">{f.path.replace(/^config\//, "")}</code>)}</>}
      </p>
      {d.planComment ? (
        <div className="plan-comment">
          <div className="muted small">Plan comment by {d.planComment.author}, updated {relativeTime(Date.parse(d.planComment.updatedAt))}</div>
          <Markdown text={d.planComment.body} />
        </div>
      ) : (
        <p className="muted">No plan comment yet. The Plan workflow posts one on pull requests that touch config/, src/ or build.gradle.</p>
      )}
    </div>
  );
}

const cellLabel: Record<Cell, string> = { "in-sync": "In sync", drift: "Drift", skipped: "Skipped", running: "Running", none: "Not checked" };

function Drift() {
  const { targets } = useRepo();
  const [drift, reload] = useLoad<DriftData>(() => api.drift(), []);
  if (drift.state === "loading") return <Spinner label="Reading the drift workflow's runs…" />;
  if (drift.state === "error") return <ErrorBox error={drift.error} action={<button onClick={reload}>Try again</button>} />;
  if (drift.state !== "ok") return null;
  const known = targets.state === "ok" ? targets.value.map((t) => t.name) : [];
  const timeline = driftTimeline(drift.value.runs, known);
  const open = drift.value.issues.filter((i) => i.state === "open");
  return (
    <section>
      {open.length > 0 ? (
        <div className="notice warn">
          {open.length} open drift {open.length === 1 ? "issue" : "issues"}:{" "}
          {open.map((i) => <button key={i.number} className="link" onClick={() => void api.openUrl(i.url)}>#{i.number} {issueTarget(i) ?? i.title}</button>)}
        </div>
      ) : (
        <p className="ok-text">No open drift issues.</p>
      )}
      <h2>Daily drift checks</h2>
      {timeline.rows.length === 0 ? (
        <Empty title="No drift runs yet">The Drift detection workflow runs daily at 06:17 UTC, or start it from the Workflows tab.</Empty>
      ) : (
        <div className="timeline-wrap">
          <table className="timeline" aria-label="Drift check results per target and day">
            <thead>
              <tr>
                <th />
                {timeline.runs.map((r) => (
                  <th key={r.id} scope="col" title={new Date(r.createdAt).toLocaleString()}>
                    {new Date(r.createdAt).toLocaleDateString(undefined, { month: "numeric", day: "numeric" })}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {timeline.rows.map((row) => (
                <tr key={row.target}>
                  <th scope="row">{row.target}</th>
                  {row.cells.map((cell, i) => (
                    <td key={i}>
                      <button className={`cell cell-${cell}`} title={`${row.target}, ${new Date(timeline.runs[i]!.createdAt).toLocaleString()}: ${cellLabel[cell]}`}
                        aria-label={`${row.target}, ${new Date(timeline.runs[i]!.createdAt).toLocaleDateString()}: ${cellLabel[cell]}`}
                        onClick={() => void api.openUrl(timeline.runs[i]!.url)} />
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
          <div className="legend">
            {(["in-sync", "drift", "skipped", "running", "none"] as Cell[]).map((c) => <span key={c}><span className={`cell cell-${c}`} aria-hidden /> {cellLabel[c]}</span>)}
          </div>
        </div>
      )}
      <h2>Drift issues</h2>
      {drift.value.issues.length === 0 ? (
        <p className="muted">None so far.</p>
      ) : (
        <table className="grid">
          <thead><tr><th>Issue</th><th>Target</th><th>State</th><th>Opened</th><th>Open for</th></tr></thead>
          <tbody>
            {drift.value.issues.map((i) => (
              <tr key={i.number}>
                <td><button className="link" onClick={() => void api.openUrl(i.url)}>#{i.number}</button></td>
                <td>{issueTarget(i) ?? i.title}</td>
                <td><Tag tone={i.state === "open" ? "warn" : "ok"}>{i.state === "open" ? "open" : "fixed"}</Tag></td>
                <td>{new Date(i.createdAt).toLocaleDateString()}</td>
                <td>{openDays(i)} {openDays(i) === 1 ? "day" : "days"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

function Workflows() {
  const { repo } = useRepo();
  const [runs, reload] = useLoad<RunsData>(api.runs, []);
  const [confirm, setConfirm] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<AppError | null>(null);
  const branch = repo.git.defaultBranch ?? repo.git.branch ?? "develop";

  const start = async (file: string) => {
    setConfirm(null);
    setError(null);
    try {
      await api.dispatch(file, branch);
      setMessage(`Started ${file} on ${branch}. It shows up below in a few seconds.`);
      setTimeout(reload, 4000);
    } catch (e) {
      setError(e as AppError);
    }
  };

  if (runs.state === "loading") return <Spinner />;
  if (runs.state === "error") return <ErrorBox error={runs.error} action={<button onClick={reload}>Try again</button>} />;
  if (runs.state !== "ok") return null;
  return (
    <section>
      <h2>Start by hand</h2>
      {!runs.value.tokenPresent && <p className="muted small">Starting a workflow needs GITHUB_TOKEN with Actions: write, in Settings.</p>}
      {message && <p className="notice" role="status">{message}</p>}
      {error && <ErrorBox error={error} />}
      <div className="dispatch">
        {runs.value.dispatchable.map((w) => (
          <div key={w.file} className="dispatch-row">
            <div>
              <strong>{w.name}</strong> <code className="muted">{w.file}</code>
              {w.file === "apply.yml" && <div className="small muted">Applies every workflow target from <code>{branch}</code>, in the production environment (it waits for approval if one is required).</div>}
            </div>
            {confirm === w.file ? (
              <span className="confirm">
                Start {w.name} on <code>{branch}</code>?
                <button className={w.file === "apply.yml" ? "danger" : "primary"} onClick={() => void start(w.file)}>Start</button>
                <button onClick={() => setConfirm(null)}>Cancel</button>
              </span>
            ) : (
              <button disabled={!runs.value.tokenPresent} onClick={() => setConfirm(w.file)}>Run…</button>
            )}
          </div>
        ))}
      </div>
      <h2>Recent runs <button className="link small" onClick={reload}>Refresh</button></h2>
      <table className="grid">
        <thead><tr><th>Workflow</th><th>Run</th><th>Branch</th><th>Event</th><th>Result</th><th>When</th></tr></thead>
        <tbody>
          {runs.value.runs.map((r) => (
            <tr key={r.id}>
              <td>{r.workflow}</td>
              <td><button className="link" onClick={() => void api.openUrl(r.url)}>{r.title || r.id}</button></td>
              <td><code>{r.branch}</code></td>
              <td>{r.event}</td>
              <td>
                <Tag tone={r.status !== "completed" ? "info" : r.conclusion === "success" ? "ok" : r.conclusion === "failure" ? "danger" : "neutral"}>
                  {r.status !== "completed" ? r.status.replace("_", " ") : r.conclusion}
                </Tag>
              </td>
              <td>{relativeTime(Date.parse(r.createdAt))}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}
