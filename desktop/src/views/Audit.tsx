import { useEffect, useState } from "react";
import { api } from "../lib/api";
import { auditRows } from "../lib/audit";
import { relativeTime, type Load } from "../lib/model";
import type { AppError, AuditEntry, WorkflowRun } from "../lib/types";
import { Empty, ErrorBox, Spinner, Tag } from "../components/ui";
import { useRepo } from "../state";

/**
 * Who changed which live system, when, through which pull request, and whether the Apply workflow then applied
 * it: the mainline of config/ from Git, joined with the Apply workflow's runs by commit.
 */
export function Audit() {
  const { repo, go } = useRepo();
  const [entries, setEntries] = useState<Load<AuditEntry[]>>({ state: "loading" });
  const [runs, setRuns] = useState<WorkflowRun[] | null>(null);
  const [runsError, setRunsError] = useState<AppError | null>(null);

  useEffect(() => {
    api.audit(100).then((value) => setEntries({ state: "ok", value, at: Date.now() }), (error: AppError) => setEntries({ state: "error", error }));
    if (repo.git.github) api.workflowRuns("apply.yml", 50).then(setRuns, setRunsError);
  }, [repo.git.github]);

  if (entries.state === "loading" || entries.state === "idle") return <Spinner label="Reading history…" />;
  if (entries.state === "error") return <ErrorBox error={entries.error} />;
  const rows = auditRows(entries.value, runs ?? []);
  const github = repo.git.github;
  return (
    <div className="page">
      <header className="page-head">
        <div>
          <h1>Audit</h1>
          <p className="muted">
            Every change to <code>config/</code> on <code>{repo.git.branch}</code>, newest first, and what the Apply workflow did with it.
            {repo.git.branch !== repo.git.defaultBranch && repo.git.defaultBranch && <> Switch to <code>{repo.git.defaultBranch}</code> to see what reached the live systems.</>}
          </p>
        </div>
      </header>
      {runsError && <p className="muted small">Couldn't read the Apply workflow's runs ({runsError.message}), so apply results aren't shown.</p>}
      {repo.git.shallow && <p className="muted small">Shallow clone: older history isn't here.</p>}
      {rows.length === 0 ? (
        <Empty title="No changes to config/ yet" />
      ) : (
        <table className="grid audit">
          <thead><tr><th>When</th><th>Who</th><th>Change</th><th>Targets</th><th>Applied</th></tr></thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.sha}>
                <td title={new Date(r.date).toLocaleString()}>{relativeTime(Date.parse(r.date))}</td>
                <td>{r.author}</td>
                <td>
                  {r.subject} <code className="muted">{r.short}</code>{" "}
                  {r.pullRequest && github && (
                    <button className="link" onClick={() => void api.openUrl(`https://github.com/${github.owner}/${github.name}/pull/${r.pullRequest}`)}>#{r.pullRequest}</button>
                  )}
                </td>
                <td>
                  {r.targets.map((t) => <button key={t} className="link chip-link" onClick={() => go({ name: "target", target: t, tab: "history" })}>{t}</button>)}
                </td>
                <td>
                  {r.applied.length === 0 && <span className="muted small">{runs ? "no Apply run" : "—"}</span>}
                  {r.applied.map((a) => (
                    <Tag key={a.target} tone={a.result === "success" ? "ok" : a.result === "failure" ? "danger" : a.result === "running" ? "info" : "neutral"}
                      title={`Apply ${a.target}: ${a.result}`}>
                      {a.target} {a.result === "success" ? "✓" : a.result === "failure" ? "✗" : a.result === "running" ? "…" : "–"}
                    </Tag>
                  ))}
                  {r.runUrl && <button className="link small" onClick={() => void api.openUrl(r.runUrl!)}>run</button>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
