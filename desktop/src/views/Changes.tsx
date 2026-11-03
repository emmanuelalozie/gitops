import { useEffect, useMemo, useState } from "react";
import { api } from "../lib/api";
import { type Load } from "../lib/model";
import { branchFor } from "../lib/schemaForm";
import type { AppError, ConfigChange, ProposeResult } from "../lib/types";
import { DiffText } from "../components/DiffText";
import { Counts, Empty, ErrorBox, Spinner, Tag } from "../components/ui";
import { useRepo } from "../state";

/**
 * Uncommitted changes under config/, and the way out: a branch, a commit of just the chosen files, a push and a
 * pull request. Git stays the only way in, so the Plan and Apply workflows take it from there.
 */
export function Changes() {
  const { repo, revision, plans, reload } = useRepo();
  const [changes, setChanges] = useState<Load<ConfigChange[]>>({ state: "loading" });
  const [chosen, setChosen] = useState<Set<string>>(new Set());
  const [preview, setPreview] = useState<string | null>(null);
  const [diff, setDiff] = useState<Load<string>>({ state: "idle" });
  const [title, setTitle] = useState("");
  const [branch, setBranch] = useState("");
  const [branchEdited, setBranchEdited] = useState(false);
  const [body, setBody] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<AppError | null>(null);
  const [result, setResult] = useState<ProposeResult | null>(null);
  const [confirmDiscard, setConfirmDiscard] = useState<string | null>(null);

  const load = () =>
    api.configChanges().then(
      (value) => {
        setChanges({ state: "ok", value, at: Date.now() });
        setChosen((c) => new Set(value.map((v) => v.path).filter((p) => c.size === 0 || c.has(p))));
      },
      (e: AppError) => setChanges({ state: "error", error: e }),
    );
  useEffect(() => {
    void load();
  }, [revision]);
  useEffect(() => {
    if (!preview) return setDiff({ state: "idle" });
    setDiff({ state: "loading" });
    api.workingDiff(preview).then((value) => setDiff({ state: "ok", value, at: Date.now() }), (e: AppError) => setDiff({ state: "error", error: e }));
  }, [preview, revision]);

  const list = changes.state === "ok" ? changes.value : [];
  const targets = useMemo(() => [...new Set(list.filter((c) => chosen.has(c.path)).map((c) => c.target).filter(Boolean))] as string[], [list, chosen]);
  const suggestedTitle = targets.length ? `Update ${targets.join(", ")} config` : "Update config";
  const effectiveTitle = title || suggestedTitle;
  const effectiveBranch = branchEdited ? branch : branchFor(effectiveTitle);

  /** The body lists the plan of each touched target as it stands, so reviewers see the effect, not just the files. */
  const planNotes = targets
    .map((t) => {
      const p = plans[t];
      if (p?.state !== "ok" || p.value.skipped) return `- \`${t}\`: not planned in the app`;
      const s = p.value.summary;
      return `- \`${t}\`: ${s.create} to create, ${s.update} to update, ${s.delete} to delete (plan ${p.value.planId?.slice(0, 12)})`;
    })
    .join("\n");

  const propose = async () => {
    setBusy(true);
    setError(null);
    try {
      const text = [body.trim(), targets.length ? `Plan from the GitOps app, against the live systems right now:\n\n${planNotes}` : ""].filter(Boolean).join("\n\n");
      setResult(await api.propose({ branch: effectiveBranch, title: effectiveTitle, body: text, paths: [...chosen] }));
      setTitle("");
      setBody("");
      setBranchEdited(false);
      setPreview(null);
      await reload();
      await load();
    } catch (e) {
      setError(e as AppError);
    } finally {
      setBusy(false);
    }
  };

  const discard = async (path: string) => {
    setConfirmDiscard(null);
    try {
      await api.discardChange(path);
      if (preview === path) setPreview(null);
      await reload();
      await load();
    } catch (e) {
      setError(e as AppError);
    }
  };

  return (
    <div className="page">
      <header className="page-head">
        <div>
          <h1>Changes</h1>
          <p className="muted">Uncommitted edits under <code>config/</code> on <code>{repo.git.branch ?? "a detached HEAD"}</code>.</p>
        </div>
      </header>
      {result && <ProposedNotice result={result} onClose={() => setResult(null)} />}
      {changes.state === "loading" && <Spinner />}
      {changes.state === "error" && <ErrorBox error={changes.error} />}
      {changes.state === "ok" && list.length === 0 && !result && (
        <Empty title="No changes">Edit resources in a target's Git config tab, or in your editor; they show up here.</Empty>
      )}
      {list.length > 0 && (
        <div className="changes-layout">
          <div>
            <ul className="file-list">
              {list.map((c) => (
                <li key={c.path} className={preview === c.path ? "active" : undefined}>
                  <input type="checkbox" aria-label={`Include ${c.path}`} checked={chosen.has(c.path)}
                    onChange={(e) => setChosen((s) => { const n = new Set(s); if (e.target.checked) n.add(c.path); else n.delete(c.path); return n; })} />
                  <button className="link file" onClick={() => setPreview(c.path)}>{c.path.replace(/^config\//, "")}</button>
                  <Tag tone={c.status === "deleted" ? "danger" : c.status === "untracked" || c.status === "added" ? "ok" : "warn"}>{c.status === "untracked" ? "new" : c.status}</Tag>
                  {confirmDiscard === c.path ? (
                    <span className="confirm small">Discard? <button className="danger-link" onClick={() => void discard(c.path)}>Yes</button> <button className="link" onClick={() => setConfirmDiscard(null)}>No</button></span>
                  ) : (
                    <button className="danger-link small" onClick={() => setConfirmDiscard(c.path)}>Discard</button>
                  )}
                </li>
              ))}
            </ul>
            <div className="diff-preview">
              {diff.state === "idle" && <p className="muted small">Choose a file to see its diff.</p>}
              {diff.state === "loading" && <Spinner />}
              {diff.state === "error" && <ErrorBox error={diff.error} />}
              {diff.state === "ok" && <DiffText text={diff.value} />}
            </div>
          </div>
          <form className="propose" onSubmit={(e) => { e.preventDefault(); void propose(); }}>
            <h2>Propose as a pull request</h2>
            {targets.map((t) => {
              const p = plans[t];
              return (
                <div key={t} className="plan-line">
                  <code>{t}</code>
                  {p?.state === "ok" && !p.value.skipped ? <Counts summary={p.value.summary} /> : <span className="muted small">not planned</span>}
                </div>
              );
            })}
            <label>Title<input value={title} placeholder={suggestedTitle} onChange={(e) => setTitle(e.target.value)} /></label>
            <label>Branch<input value={effectiveBranch} onChange={(e) => { setBranch(e.target.value); setBranchEdited(true); }} spellCheck={false} /></label>
            <label>Description<textarea rows={4} value={body} onChange={(e) => setBody(e.target.value)} placeholder="Why this change? The plan summary is added for you." /></label>
            {error && <ErrorBox error={error} />}
            <button type="submit" className="primary" disabled={busy || chosen.size === 0 || !repo.git.branch}>
              {busy ? "Pushing…" : `Commit ${chosen.size} ${chosen.size === 1 ? "file" : "files"} and open pull request`}
            </button>
            <p className="muted small">
              Creates <code>{effectiveBranch}</code> from your current branch, commits only the chosen files, pushes it, opens a pull
              request into <code>{repo.git.defaultBranch ?? repo.git.branch}</code>, and switches you back. The files leave your working tree:
              they're on that branch now.
            </p>
          </form>
        </div>
      )}
    </div>
  );
}

function ProposedNotice({ result, onClose }: { result: ProposeResult; onClose: () => void }) {
  const p = result.proposed;
  return (
    <div className={p.pushed ? "notice" : "notice warn"} role="status">
      {p.pushed ? (
        <>
          Pushed <code>{p.branch}</code> ({p.commit.slice(0, 7)}).{" "}
          {result.pullRequest ? (
            <>Opened <button className="link" onClick={() => void api.openUrl(result.pullRequest!.url)}>pull request #{result.pullRequest.number}</button>. The Plan workflow will comment with what it changes.</>
          ) : result.compareUrl ? (
            <>
              {result.pullRequestError ? `Couldn't open the pull request (${result.pullRequestError.message}). ` : "No GITHUB_TOKEN in Settings, so open it on GitHub: "}
              <button className="link" onClick={() => void api.openUrl(result.compareUrl!)}>open pull request form</button>.
            </>
          ) : (
            "Open a pull request for it on your Git host."
          )}
        </>
      ) : (
        <>
          Committed to <code>{p.branch}</code> but the push failed: {p.pushError}. Push it yourself with{" "}
          <code>git push -u origin {p.branch}</code>.
        </>
      )}
      <button className="link push-right" onClick={onClose}>Dismiss</button>
    </div>
  );
}
