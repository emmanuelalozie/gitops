import { useEffect, useMemo, useRef, useState } from "react";
import { api } from "../lib/api";
import { columns, formatValue, type Load } from "../lib/model";
import { buildResource, fieldsOf, inputsOf, problemsByField, type Field } from "../lib/schemaForm";
import type { AppError, Problem, Resource, ResourceEntry, Target } from "../lib/types";
import { ErrorBox, Spinner, Tag } from "../components/ui";
import { useRepo } from "../state";

/**
 * The resources Git holds for a target, and an editor for them. Saving writes the file through the engine, which
 * refuses anything invalid and keeps the canonical format. It changes the working tree only: the change reaches
 * the live system through Changes → pull request → merge → the Apply workflow.
 */
export function ConfigTab({ target }: { target: Target }) {
  const { revision, go } = useRepo();
  const [entries, setEntries] = useState<Load<ResourceEntry[]>>({ state: "loading" });
  const [fields, setFields] = useState<Field[]>([]);
  const [editing, setEditing] = useState<ResourceEntry | "new" | null>(null);
  const [saved, setSaved] = useState<string | null>(null);

  const load = () =>
    api.resources(target.name).then(
      (value) => setEntries({ state: "ok", value, at: Date.now() }),
      (error: AppError) => setEntries({ state: "error", error }),
    );
  // Reload when the target changes or files change on disk (revision).
  useEffect(() => {
    void load();
  }, [target.name, revision]);
  useEffect(() => {
    void api.schema(target.name).then((s) => setFields(fieldsOf(s)));
  }, [target.name]);

  if (entries.state === "loading" || entries.state === "idle") return <Spinner label="Reading config…" />;
  if (entries.state === "error") return <ErrorBox error={entries.error} />;

  if (editing) {
    return (
      <Editor
        target={target}
        fields={fields}
        entry={editing === "new" ? null : editing}
        onDone={(message) => {
          setEditing(null);
          setSaved(message);
          void load();
        }}
      />
    );
  }

  const resources = entries.value.map((e) => e.resource ?? {});
  const cols = columns(resources, fields.map((f) => f.name)).slice(0, 5);
  return (
    <section>
      <div className="toolbar">
        <p className="muted small">
          {entries.value.length} files in <code>{target.resourceDir.split(/[\\/]/).slice(-3).join("/")}</code>. Edits change your working
          tree; propose them from <button className="link" onClick={() => go({ name: "changes" })}>Changes</button>.
        </p>
        {target.kind !== "repository" && <button onClick={() => setEditing("new")}>New {target.kind === "users" ? "user" : "resource"}</button>}
      </div>
      {saved && <p className="notice" role="status">{saved}</p>}
      <div className="table-wrap">
        <table className="grid selectable">
          <thead><tr>{cols.map((c) => <th key={c}>{c}</th>)}<th /></tr></thead>
          <tbody>
            {entries.value.map((entry) => (
              <tr key={entry.file} onClick={() => entry.resource && setEditing(entry)} tabIndex={0}
                onKeyDown={(e) => e.key === "Enter" && entry.resource && setEditing(entry)}>
                {entry.resource
                  ? cols.map((c) => <td key={c}><code>{c in entry.resource! ? display(entry.resource![c]) : ""}</code></td>)
                  : <td colSpan={cols.length} className="error-text">{entry.error}</td>}
                <td className="row-action">{entry.resource ? "Edit" : ""}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

const display = (v: Resource[string] | undefined) => (typeof v === "string" ? v : formatValue(v));

function Editor({ target, fields, entry, onDone }: { target: Target; fields: Field[]; entry: ResourceEntry | null; onDone: (message: string) => void }) {
  const [inputs, setInputs] = useState(() => inputsOf(fields, entry?.resource ?? null));
  const [problems, setProblems] = useState<Problem[]>([]);
  const [checking, setChecking] = useState(false);
  const [error, setError] = useState<AppError | null>(null);
  const [busy, setBusy] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const resource = useMemo(() => buildResource(fields, inputs), [fields, inputs]);
  const previousKey = entry?.key || undefined;
  const sequence = useRef(0);

  // Ask the engine as you type (debounced); only its answer decides whether Save is allowed.
  useEffect(() => {
    const id = ++sequence.current;
    setChecking(true);
    const timer = setTimeout(() => {
      api.checkResource(target.name, resource, previousKey).then(
        (r) => id === sequence.current && (setProblems(r.problems), setChecking(false)),
        (e: AppError) => id === sequence.current && (setError(e), setChecking(false)),
      );
    }, 250);
    return () => clearTimeout(timer);
  }, [target.name, resource, previousKey]);

  const byField = problemsByField(problems);
  const changed = JSON.stringify(resource) !== JSON.stringify(buildResource(fields, inputsOf(fields, entry?.resource ?? null)));
  const key = String(resource[target.keyField] ?? "");

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      const result = await api.writeResource(target.name, resource, previousKey);
      onDone(`Saved ${key}${result.removed.length ? ` (renamed from ${previousKey})` : ""}. The plan now shows what merging it would change.`);
    } catch (e) {
      const err = e as AppError;
      setError(err);
      if (err.code === "invalid_config") setProblems((err.details as { problems?: Problem[] })?.problems ?? []);
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    setBusy(true);
    try {
      await api.deleteResource(target.name, entry!.key);
      onDone(`Removed ${entry!.key} from Git.${target.prune ? " With prune on, merging this deletes it live." : " Prune is off, so merging leaves it live, unmanaged."}`);
    } catch (e) {
      setError(e as AppError);
      setBusy(false);
    }
  };

  return (
    <section className="editor">
      <h2>{entry ? <>Edit <code>{entry.key}</code></> : `New ${target.kind === "users" ? "user" : "resource"}`}</h2>
      <form onSubmit={(e) => { e.preventDefault(); void save(); }}>
        {fields.map((field) => (
          <FieldInput key={field.name} field={field} value={inputs[field.name] ?? ""} problems={byField.get(field.name) ?? []}
            onChange={(v) => setInputs((i) => ({ ...i, [field.name]: v }))} />
        ))}
        {(byField.get("") ?? []).map((m, i) => <p key={i} className="field-problem">{m}</p>)}
        {error && error.code !== "invalid_config" && <ErrorBox error={error} />}
        <div className="editor-actions">
          <button type="submit" className="primary" disabled={busy || checking || problems.length > 0 || !changed}>
            {busy ? "Saving…" : "Save to working tree"}
          </button>
          <button type="button" onClick={() => onDone("")} disabled={busy}>Cancel</button>
          <span className="muted small">
            {checking ? "Checking…" : problems.length > 0 ? `${problems.length} ${problems.length === 1 ? "problem" : "problems"}` : changed ? "Valid" : "No changes"}
          </span>
          {entry && target.kind !== "repository" && (
            confirmDelete ? (
              <span className="confirm">
                Remove <code>{entry.key}</code> from Git? <button type="button" className="danger" onClick={() => void remove()} disabled={busy}>Remove</button>
                <button type="button" onClick={() => setConfirmDelete(false)}>Keep</button>
              </span>
            ) : (
              <button type="button" className="danger-link push-right" onClick={() => setConfirmDelete(true)}>Remove…</button>
            )
          )}
        </div>
      </form>
    </section>
  );
}

function FieldInput({ field, value, problems, onChange }: { field: Field; value: string | boolean; problems: string[]; onChange: (v: string | boolean) => void }) {
  const id = `field-${field.name}`;
  const invalid = problems.length > 0;
  const common = { id, "aria-invalid": invalid, "aria-describedby": invalid ? `${id}-problems` : undefined };
  return (
    <div className={invalid ? "field invalid" : "field"}>
      <label htmlFor={id}>
        <code>{field.name}</code> {field.required ? <Tag>required</Tag> : <span className="muted small">optional</span>}
      </label>
      {field.type === "boolean" ? (
        <input type="checkbox" {...common} checked={value === true} onChange={(e) => onChange(e.target.checked)} />
      ) : field.enum ? (
        <select {...common} value={String(value)} onChange={(e) => onChange(e.target.value)}>
          {!field.required && <option value="" />}
          {field.enum.map((o) => <option key={o}>{o}</option>)}
        </select>
      ) : field.type === "list" || field.type === "json" ? (
        <textarea {...common} rows={field.type === "list" ? 4 : 6} value={String(value)} onChange={(e) => onChange(e.target.value)}
          placeholder={field.type === "list" ? "One per line" : "JSON"} spellCheck={false} />
      ) : (
        <input {...common} type="text" inputMode={field.type === "integer" || field.type === "number" ? "numeric" : undefined}
          value={String(value)} onChange={(e) => onChange(e.target.value)} spellCheck={false} />
      )}
      {(field.description || field.pattern) && (
        <span className="hint">{field.description}{field.pattern && <> Pattern <code>{field.pattern}</code></>}</span>
      )}
      {invalid && <span id={`${id}-problems`} className="field-problem">{problems.join("; ")}</span>}
    </div>
  );
}
