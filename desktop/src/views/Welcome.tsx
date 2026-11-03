import { useEffect, useState } from "react";
import { api, inDesktop } from "../lib/api";
import type { AppError, AppInfo, RepoInfo } from "../lib/types";
import { ErrorBox, Spinner } from "../components/ui";
import { NotGitOps, notGitOpsCodes } from "../components/NotGitOps";

export function Welcome({ onOpen }: { onOpen: (repo: RepoInfo) => void }) {
  const [info, setInfo] = useState<AppInfo | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<AppError | null>(null);

  useEffect(() => {
    void api.appInfo().then(setInfo).catch(() => undefined);
  }, []);

  const open = async (path: string | null) => {
    if (!path) return;
    setBusy(path);
    setError(null);
    try {
      onOpen(await api.openRepo(path));
    } catch (e) {
      setError(e as AppError);
    } finally {
      setBusy(null);
    }
  };

  return (
    <main className="welcome">
      <div className="welcome-card">
        <div className="logo" aria-hidden>
          <svg viewBox="0 0 24 24" width="40" height="40"><circle cx="6" cy="5" r="2.2" /><circle cx="6" cy="19" r="2.2" /><circle cx="18" cy="9" r="2.2" /><path d="M6 7.2v9.6M18 11.2c0 4-6 3.5-11 5.8" fill="none" strokeWidth="1.8" /></svg>
        </div>
        <h1>GitOps</h1>
        <p className="lede">See what Git says, what's live, and what would change, for every system this repository manages.</p>
        {!inDesktop && (
          <p className="demo-note">You're viewing the UI in a browser, so it shows demo data. Run <code>npm run tauri dev</code> for the real app.</p>
        )}
        <button className="primary" disabled={busy !== null} onClick={() => void api.pickFolder().then(open)}>
          Open a repository…
        </button>
        {busy && <Spinner label={`Starting the engine for ${busy}…`} />}
        {error && error.kind === "repo" && notGitOpsCodes.includes(error.code) ? (
          <NotGitOps error={error} onOpen={(path) => void open(path)} onPickAnother={() => void api.pickFolder().then(open)} />
        ) : (
          error && <ErrorBox error={error} />
        )}
        {info && info.recentRepos.length > 0 && (
          <section className="recent">
            <h2>Recent</h2>
            <ul>
              {info.recentRepos.map((path) => (
                <li key={path}>
                  <button className="link" disabled={busy !== null} onClick={() => void open(path)}>{path}</button>
                </li>
              ))}
            </ul>
          </section>
        )}
      </div>
    </main>
  );
}
