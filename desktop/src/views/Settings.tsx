import { useCallback, useEffect, useState } from "react";
import { api } from "../lib/api";
import { tokenNames } from "../lib/model";
import type { AppError, EngineVersion, TokenStatus } from "../lib/types";
import { ErrorBox, Tag } from "../components/ui";
import { useRepo } from "../state";

export function Settings() {
  const { repo, targets, reload, planAll } = useRepo();
  const names = targets.state === "ok" ? tokenNames(targets.value) : ["GITHUB_TOKEN"];
  const [tokens, setTokens] = useState<TokenStatus[]>([]);
  const [engine, setEngine] = useState<EngineVersion | null>(null);
  const [error, setError] = useState<AppError | null>(null);
  const [restarting, setRestarting] = useState(false);

  const refresh = useCallback(() => {
    api.tokenStatus(names).then(setTokens, (e: AppError) => setError(e));
    api.engineInfo().then(setEngine, () => undefined);
  }, [names.join(",")]);
  useEffect(refresh, [refresh]);

  /** Tokens reach the engine as environment variables, so it's restarted to pick them up. */
  const restart = async () => {
    setRestarting(true);
    setError(null);
    try {
      setEngine(await api.restartEngine());
      await reload();
      planAll();
    } catch (e) {
      setError(e as AppError);
    } finally {
      setRestarting(false);
      refresh();
    }
  };

  return (
    <div className="page">
      <header className="page-head"><h1>Settings</h1></header>
      {error && <ErrorBox error={error} />}
      <section>
        <h2>Tokens</h2>
        <p className="muted">
          Stored in your system keychain. The app never shows them again: they're only passed to the engine and sent to GitHub.
        </p>
        <ul className="token-list">
          {tokens.map((t) => (
            <TokenRow key={t.name} token={t} onChanged={restart} onError={setError} />
          ))}
        </ul>
      </section>
      <section>
        <h2>Engine</h2>
        {engine && (
          <p>
            <Tag tone={engine.alive === false ? "danger" : "ok"}>{engine.alive === false ? "stopped" : "running"}</Tag>{" "}
            Engine {engine.engineVersion} · JSON schema v{engine.schemaVersion} · Java {engine.java}
          </p>
        )}
        <button onClick={() => void restart()} disabled={restarting}>{restarting ? "Restarting…" : "Restart engine"}</button>
      </section>
      <section>
        <h2>Repository</h2>
        <dl className="facts-list">
          <dt>Folder</dt><dd><code>{repo.path}</code></dd>
          <dt>Branch</dt><dd><code>{repo.git.branch ?? "detached"}</code> (default <code>{repo.git.defaultBranch ?? "unknown"}</code>)</dd>
          <dt>GitHub</dt><dd>{repo.git.github ? `${repo.git.github.owner}/${repo.git.github.name}` : "no GitHub remote named origin"}</dd>
          {repo.git.shallow && (<><dt>History</dt><dd>Shallow clone: history views show only the fetched commits.</dd></>)}
        </dl>
      </section>
    </div>
  );
}

function TokenRow({ token, onChanged, onError }: { token: TokenStatus; onChanged: () => Promise<void>; onError: (e: AppError) => void }) {
  const [value, setValue] = useState("");
  const save = async () => {
    try {
      await api.setToken(token.name, value);
      setValue("");
      await onChanged();
    } catch (e) {
      onError(e as AppError);
    }
  };
  const clear = async () => {
    try {
      await api.clearToken(token.name);
      await onChanged();
    } catch (e) {
      onError(e as AppError);
    }
  };
  return (
    <li className="token-row">
      <code>{token.name}</code>
      <Tag tone={token.present ? "ok" : "neutral"}>{token.present ? "set" : "not set"}</Tag>
      <input
        type="password"
        autoComplete="off"
        spellCheck={false}
        placeholder={token.present ? "Replace…" : "Paste a token"}
        value={value}
        onChange={(e) => setValue(e.target.value)}
        aria-label={`Value for ${token.name}`}
      />
      <button disabled={!value.trim()} onClick={() => void save()}>Save</button>
      {token.present && <button className="danger-link" onClick={() => void clear()}>Remove</button>}
    </li>
  );
}
