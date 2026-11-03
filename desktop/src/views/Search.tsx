import { useEffect, useState } from "react";
import { api } from "../lib/api";
import { formatValue, type Load } from "../lib/model";
import type { AppError, SearchResult } from "../lib/types";
import { Empty, ErrorBox, Spinner } from "../components/ui";
import { useRepo } from "../state";

/** Resources in Git across every target, by key or any value ("where is WID-001?", "who has @corp.com?"). */
export function Search({ query }: { query: string }) {
  const { go, revision } = useRepo();
  const [result, setResult] = useState<Load<SearchResult>>({ state: "idle" });

  useEffect(() => {
    if (!query.trim()) return setResult({ state: "idle" });
    setResult({ state: "loading" });
    const timer = setTimeout(() => {
      api.search(query).then((value) => setResult({ state: "ok", value, at: Date.now() }), (error: AppError) => setResult({ state: "error", error }));
    }, 200);
    return () => clearTimeout(timer);
  }, [query, revision]);

  return (
    <div className="page">
      <header className="page-head"><h1>Search</h1></header>
      {result.state === "idle" && <Empty title="Type in the search box">Finds resources in Git, in every target, by their key or any value.</Empty>}
      {result.state === "loading" && <Spinner />}
      {result.state === "error" && <ErrorBox error={result.error} />}
      {result.state === "ok" && (
        <>
          <p className="muted small">
            {result.value.results.length} {result.value.results.length === 1 ? "resource" : "resources"} with “{result.value.query}”
            {result.value.truncated && " (the first 100)"}
          </p>
          <ul className="search-results">
            {result.value.results.map((hit) => (
              <li key={hit.file}>
                <button className="link" onClick={() => go({ name: "target", target: hit.target, tab: "config" })}>
                  <strong>{hit.key}</strong>
                </button>{" "}
                <span className="muted small">in {hit.target} ({hit.kind})</span>
                <div className="hit-fields">
                  {hit.fields.map((f) => (
                    <span key={f}><code>{f}</code>: <Highlight text={typeof hit.resource[f] === "string" ? String(hit.resource[f]) : formatValue(hit.resource[f])} query={result.value.query} /></span>
                  ))}
                </div>
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  );
}

function Highlight({ text, query }: { text: string; query: string }) {
  const i = text.toLowerCase().indexOf(query.toLowerCase().trim());
  if (i < 0 || !query.trim()) return <>{text}</>;
  const q = query.trim();
  return <>{text.slice(0, i)}<mark>{text.slice(i, i + q.length)}</mark>{text.slice(i + q.length)}</>;
}
