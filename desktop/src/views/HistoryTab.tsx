import { useEffect, useState } from "react";
import { api } from "../lib/api";
import { repoRelative, type Load } from "../lib/model";
import type { AppError, Commit, ResourceEntry, Target } from "../lib/types";
import { DiffText } from "../components/DiffText";
import { ErrorBox, Spinner } from "../components/ui";
import { useRepo } from "../state";

/** Who changed what and when: commits touching the target's folder, or one resource's file. */
export function HistoryTab({ target }: { target: Target }) {
  const { repo, revision } = useRepo();
  const [resources, setResources] = useState<ResourceEntry[]>([]);
  const [path, setPath] = useState(`config/${target.name}`);
  const [commits, setCommits] = useState<Load<Commit[]>>({ state: "loading" });
  const [selected, setSelected] = useState<Commit | null>(null);
  const [diff, setDiff] = useState<Load<string>>({ state: "idle" });

  useEffect(() => {
    void api.resources(target.name).then(setResources, () => setResources([]));
  }, [target.name, revision]);
  useEffect(() => {
    setCommits({ state: "loading" });
    setSelected(null);
    api.history(path).then(
      (value) => setCommits({ state: "ok", value, at: Date.now() }),
      (error: AppError) => setCommits({ state: "error", error }),
    );
  }, [path, revision]);
  useEffect(() => {
    if (!selected) return setDiff({ state: "idle" });
    setDiff({ state: "loading" });
    api.commitDiff(selected.sha, path).then(
      (value) => setDiff({ state: "ok", value, at: Date.now() }),
      (error: AppError) => setDiff({ state: "error", error }),
    );
  }, [selected, path]);

  const github = repo.git.github;
  return (
    <section>
      <div className="toolbar">
        <label className="inline">
          Show history of{" "}
          <select value={path} onChange={(e) => setPath(e.target.value)}>
            <option value={`config/${target.name}`}>the whole target</option>
            {resources.map((r) => {
              const rel = repoRelative(r.file, repo.path);
              return <option key={rel} value={rel}>{r.key || rel}</option>;
            })}
          </select>
        </label>
        {repo.git.shallow && <span className="muted small">Shallow clone: older commits aren't here.</span>}
      </div>
      {commits.state === "loading" && <Spinner />}
      {commits.state === "error" && <ErrorBox error={commits.error} />}
      {commits.state === "ok" && commits.value.length === 0 && <p className="muted">No commits touch <code>{path}</code> yet.</p>}
      {commits.state === "ok" && commits.value.length > 0 && (
        <div className="history">
          <ol className="commits">
            {commits.value.map((c) => (
              <li key={c.sha}>
                <button className={selected?.sha === c.sha ? "commit active" : "commit"} onClick={() => setSelected(c)}>
                  <span className="commit-subject">{c.subject}</span>
                  <span className="muted small">
                    <code>{c.short}</code> · {c.author} · {new Date(c.date).toLocaleDateString()}
                  </span>
                </button>
                {c.pullRequest && github && (
                  <button className="link small" onClick={() => void api.openUrl(`https://github.com/${github.owner}/${github.name}/pull/${c.pullRequest}`)}>
                    #{c.pullRequest}
                  </button>
                )}
              </li>
            ))}
          </ol>
          <div className="commit-diff">
            {!selected && <p className="muted">Choose a commit to see what it changed.</p>}
            {diff.state === "loading" && <Spinner />}
            {diff.state === "error" && <ErrorBox error={diff.error} />}
            {diff.state === "ok" && <DiffText text={diff.value} />}
          </div>
        </div>
      )}
    </section>
  );
}
