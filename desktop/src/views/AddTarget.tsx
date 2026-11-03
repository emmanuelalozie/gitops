import { useState } from "react";
import { api } from "../lib/api";
import type { AppError, Json } from "../lib/types";
import { ErrorBox, Spinner } from "../components/ui";
import { useRepo } from "../state";

type KindChoice = "labels" | "repository" | "branch-protection" | "actions-variables" | "users" | "rest";

const kinds: { id: KindChoice; label: string; help: string; github: boolean; token?: { name: string; required: boolean } }[] = [
  { id: "labels", label: "GitHub issue labels", help: "Name, colour and description of each label.", github: true, token: { name: "GITHUB_TOKEN", required: false } },
  { id: "repository", label: "GitHub repository settings", help: "Description, topics, merge options, wiki and issues.", github: true, token: { name: "GITOPS_ADMIN_TOKEN", required: true } },
  { id: "branch-protection", label: "GitHub branch protection", help: "Required checks, reviews and pushes, per branch.", github: true, token: { name: "GITOPS_ADMIN_TOKEN", required: true } },
  { id: "actions-variables", label: "GitHub Actions variables", help: "The vars.NAME values workflows read.", github: true, token: { name: "GITOPS_ADMIN_TOKEN", required: true } },
  { id: "rest", label: "A REST API collection", help: "Any API that lists, creates, updates and deletes JSON objects, such as /items of an inventory API.", github: false },
  { id: "users", label: "A users API (JSONPlaceholder-style)", help: "The demo kind: /users with username, email and phone.", github: false },
];

/**
 * Adds a target: a new folder under config/, written by the engine (which checks it loads), optionally filled with
 * what's live now. It lands in Changes like any edit, to go out through a pull request.
 */
export function AddTarget() {
  const { repo, go, reload, planTarget } = useRepo();
  const github = repo.git.github;
  const [kind, setKind] = useState<KindChoice>("labels");
  const choice = kinds.find((k) => k.id === kind)!;
  const [repository, setRepository] = useState(github ? `${github.owner}/` : "");
  const [baseUrl, setBaseUrl] = useState("http://127.0.0.1:3000");
  const [resource, setResource] = useState("items");
  const [path, setPath] = useState("/items");
  const [key, setKey] = useState("id");
  const [idField, setIdField] = useState("id");
  const [schemaText, setSchemaText] = useState("");
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [prune, setPrune] = useState(false);
  const [workflows, setWorkflows] = useState(true);
  const [importLive, setImportLive] = useState(true);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<AppError | null>(null);

  const repoName = repository.split("/")[1] ?? "";
  const suggested = choice.github
    ? `${repoName || "repo"}-${kind === "repository" ? "settings" : kind === "branch-protection" ? "branches" : kind === "actions-variables" ? "variables" : "labels"}`
    : kind === "rest" ? resource || "items" : "users";
  const targetName = (name || suggested).toLowerCase().replace(/[^a-z0-9._-]+/g, "-");

  const infer = async () => {
    setBusy("Reading the API…");
    setError(null);
    try {
      setSchemaText(JSON.stringify(await api.inferSchema(baseUrl, path), null, 2));
    } catch (e) {
      setError(e as AppError);
    } finally {
      setBusy(null);
    }
  };

  const create = async () => {
    setError(null);
    let schema: Record<string, Json> | undefined;
    if (kind === "rest") {
      try {
        schema = JSON.parse(schemaText) as Record<string, Json>;
      } catch {
        setError({ kind: "internal", code: "invalid_schema", message: "The schema isn't valid JSON. Use “Draft from the API” to start from what the API returns." });
        return;
      }
    }
    const settings: Record<string, Json> = {
      description: description || null,
      kind,
      ...(choice.github ? { repository } : { baseUrl }),
      ...(kind === "rest" ? { resource, path, key, idField } : {}),
      prune,
      verifyAfterApply: true,
      workflows,
      ...(choice.token && choice.token.name !== "GITHUB_TOKEN" ? { tokenEnv: choice.token.name } : {}),
      ...(choice.token?.required ? { tokenRequired: true } : {}),
    };
    try {
      setBusy("Writing config…");
      await api.createTarget(targetName, settings, schema);
      if (importLive) {
        setBusy("Importing what's live…");
        try {
          await api.snapshot(targetName);
        } catch (e) {
          // The target exists; importing can be retried from its page once the token or API is sorted out.
          setError({ ...(e as AppError), message: `Created config/${targetName}, but importing failed: ${(e as AppError).message}` });
        }
      }
      await reload();
      void planTarget(targetName);
      go({ name: "target", target: targetName, tab: "config" });
    } catch (e) {
      setError(e as AppError);
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className="page">
      <header className="page-head">
        <div>
          <h1>Add a target</h1>
          <p className="muted">A live system for this repository to manage. It's written to <code>config/{targetName}/</code> in your working tree; propose it from Changes like any edit.</p>
        </div>
      </header>
      <form className="editor add-target" onSubmit={(e) => { e.preventDefault(); void create(); }}>
        <fieldset>
          <legend>What to manage</legend>
          {kinds.map((k) => (
            <label key={k.id} className="choice">
              <input type="radio" name="kind" checked={kind === k.id} onChange={() => setKind(k.id)} />
              <span><strong>{k.label}</strong> <span className="muted small">{k.help}</span></span>
            </label>
          ))}
        </fieldset>

        {choice.github ? (
          <label className="field">
            <span>Repository <span className="muted small">owner/name, such as {github ? `${github.owner}/inventory-manager` : "me/inventory-manager"}</span></span>
            <input value={repository} onChange={(e) => setRepository(e.target.value.trim())} spellCheck={false} required />
          </label>
        ) : (
          <label className="field">
            <span>API base URL</span>
            <input value={baseUrl} onChange={(e) => setBaseUrl(e.target.value.trim())} spellCheck={false} required />
          </label>
        )}

        {kind === "rest" && (
          <>
            <div className="field-row">
              <label className="field"><span>Collection path</span><input value={path} onChange={(e) => setPath(e.target.value.trim())} spellCheck={false} /></label>
              <label className="field"><span>Folder name</span><input value={resource} onChange={(e) => setResource(e.target.value.trim())} spellCheck={false} /></label>
              <label className="field"><span>Key field <span className="muted small">such as sku</span></span><input value={key} onChange={(e) => setKey(e.target.value.trim())} spellCheck={false} /></label>
              <label className="field"><span>Id field <span className="muted small">used in URLs</span></span><input value={idField} onChange={(e) => setIdField(e.target.value.trim())} spellCheck={false} /></label>
            </div>
            <label className="field">
              <span>
                Schema <span className="muted small">only the fields it declares are managed</span>{" "}
                <button type="button" className="link" onClick={() => void infer()}>Draft from the API</button>
              </span>
              <textarea rows={10} value={schemaText} onChange={(e) => setSchemaText(e.target.value)} spellCheck={false}
                placeholder='{"type": "object", "properties": {"id": {"type": "integer"}, ...}, "required": [...]}' />
            </label>
          </>
        )}

        <div className="field-row">
          <label className="field"><span>Target name</span><input value={name} placeholder={suggested} onChange={(e) => setName(e.target.value)} spellCheck={false} /></label>
          <label className="field grow"><span>Description</span><input value={description} onChange={(e) => setDescription(e.target.value)} /></label>
        </div>

        {choice.token && (
          <p className="small muted">
            Uses <code>{choice.token.name}</code>{choice.token.required ? " (required: add it in Settings)" : " when set"}.
          </p>
        )}

        <label className="check"><input type="checkbox" checked={importLive} onChange={(e) => setImportLive(e.target.checked)} /> Import what's live now, so Git starts out matching it</label>
        <label className="check"><input type="checkbox" checked={prune} onChange={(e) => setPrune(e.target.checked)} /> Prune: delete live resources that aren't in Git <span className="muted small">(off leaves them alone, listed as unmanaged)</span></label>
        <label className="check"><input type="checkbox" checked={workflows} onChange={(e) => setWorkflows(e.target.checked)} /> Plan, apply and check for drift in the GitHub workflows once merged</label>

        {error && <ErrorBox error={error} />}
        <div className="editor-actions">
          <button type="submit" className="primary" disabled={busy !== null}>Add {targetName}</button>
          <button type="button" onClick={() => go({ name: "overview" })} disabled={busy !== null}>Cancel</button>
          {busy && <Spinner label={busy} />}
        </div>
      </form>
    </div>
  );
}
