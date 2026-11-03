import { Empty, ErrorBox, Spinner, Tag } from "../components/ui";
import { useRepo } from "../state";

/** Validation problems (they stop plan and apply) and lint warnings (they don't), re-checked on every file change. */
export function Problems() {
  const { validation, lint, go } = useRepo();
  if (validation.state === "loading" || validation.state === "idle") return <Spinner label="Validating…" />;
  if (validation.state === "error") return <ErrorBox error={validation.error} />;
  const failing = validation.value.targets.filter((t) => !t.ok);
  const findings = lint.state === "ok" ? lint.value.findings : [];

  return (
    <div className="page">
      <header className="page-head">
        <div>
          <h1>Problems</h1>
          <p className="muted">Checked again whenever a file under config/ or .github/workflows/ changes.</p>
        </div>
      </header>
      <section>
        <h2>Validation</h2>
        {failing.length === 0 ? (
          <p className="ok-text">Every target passes its schema, file name and uniqueness checks.</p>
        ) : (
          failing.map((t) => (
            <div key={t.name} className="problem-group">
              <h3>
                <button className="link" onClick={() => go({ name: "target", target: t.name })}>{t.name}</button>{" "}
                <Tag tone="danger">{t.problems.length} {t.problems.length === 1 ? "problem" : "problems"}</Tag>
              </h3>
              <p className="muted small">Plan and apply refuse to run for this target until these are fixed.</p>
              <ul className="problem-list">
                {t.problems.map((p, i) => (
                  <li key={i}>
                    {p.file && <code>{p.file}</code>} {p.pointer && <code className="pointer">{p.pointer}</code>} {p.message}
                  </li>
                ))}
              </ul>
            </div>
          ))
        )}
      </section>
      <section>
        <h2>Lint</h2>
        {lint.state === "error" && <ErrorBox error={lint.error} />}
        {lint.state === "ok" && findings.length === 0 && (
          <p className="ok-text">Every required status check is reported by a workflow job that runs on pull requests.</p>
        )}
        {findings.length > 0 && (
          <ul className="problem-list">
            {findings.map((f, i) => (
              <li key={i}>
                <Tag tone="warn">{f.code}</Tag> <code>{f.file}</code> <code className="pointer">{f.pointer}</code> {f.message}
              </li>
            ))}
          </ul>
        )}
        {findings.length === 0 && validation.value.ok && <Empty title="Nothing to fix" />}
      </section>
    </div>
  );
}
