import type { AppError } from "../lib/types";

export const notGitOpsCodes = ["not_gitops", "inside_gitops_repo", "no_targets", "not_a_folder"];

interface Details {
  path?: string;
  name?: string;
  suggestion?: string;
  github?: { owner: string; name: string } | null;
}

/** Why a folder can't be opened, and what to do instead. Shown on the welcome screen in place of an error. */
export function NotGitOps({ error, onOpen, onPickAnother }: { error: AppError; onOpen: (path: string) => void; onPickAnother: () => void }) {
  const d = (error.details ?? {}) as Details;
  const name = d.name || "this project";
  const repo = d.github ? `${d.github.owner}/${d.github.name}` : `<owner>/${d.name || "repository"}`;
  const title = {
    inside_gitops_repo: "That's a folder inside a GitOps repository",
    no_targets: "No targets in config/ yet",
    not_a_folder: "That folder can't be opened",
    not_gitops: `${name} isn't a GitOps repository`,
  }[error.code] ?? "That folder can't be opened";

  return (
    <section className="not-gitops" role="alert" aria-labelledby="not-gitops-title">
      <h2 id="not-gitops-title">{title}</h2>
      <p>{error.message}</p>

      {error.code === "inside_gitops_repo" && d.suggestion && (
        <button className="primary" onClick={() => onOpen(d.suggestion!)}>Open {d.suggestion}</button>
      )}

      {(error.code === "not_gitops" || error.code === "no_targets") && (
        <>
          <p className="muted small">Laid out like this, one folder per live system:</p>
          <pre className="layout">{`config/
  <target>/target.json      what the system is and where it lives
  <target>/<kind>/*.json    one file per resource, as it should be`}</pre>
        </>
      )}

      {error.code === "not_gitops" && (
        <>
          <h3>To manage {name} with GitOps</h3>
          <p className="small">
            Keep {name} as it is and describe it from your GitOps repository instead. Add a target there that points at it,
            for example its issue labels{d.github ? "" : " (fill in its GitHub owner)"}:
          </p>
          <pre className="layout">{`config/${(d.name || "project").toLowerCase()}-labels/target.json
{
  "kind": "labels",
  "repository": "${repo}",
  "prune": false
}`}</pre>
          <p className="small muted">
            Then run <code>./gradlew snapshot -Ptarget={(d.name || "project").toLowerCase()}-labels</code> to write its current labels into
            Git, and open the GitOps repository here. The kinds available today are <code>labels</code>, <code>repository</code> (settings
            and topics), <code>branch-protection</code> and <code>users</code>.
          </p>
        </>
      )}

      <div className="editor-actions">
        <button onClick={onPickAnother}>Choose another folder…</button>
      </div>
    </section>
  );
}
