import { useCallback, useEffect, useState } from "react";
import { api } from "../lib/api";
import type { AppError, MockStatus, Target } from "../lib/types";
import { useRepo } from "../state";
import { Spinner, Tag } from "./ui";

/**
 * Start and stop a target's local mock API ("mock" in target.json). The engine runs it from a copy of the seed,
 * so stopping and starting again resets it; targets on the same port share it and are re-planned together.
 */
export function MockControl({ target, compact = false }: { target: Target; compact?: boolean }) {
  const { targets, planTarget } = useRepo();
  const [status, setStatus] = useState<MockStatus | null>(null);
  const [busy, setBusy] = useState<"start" | "stop" | null>(null);
  const [error, setError] = useState<AppError | null>(null);

  const refresh = useCallback(() => {
    api.mockStatus(target.name).then(setStatus, (e: AppError) => setError(e));
  }, [target.name]);
  useEffect(refresh, [refresh]);

  if (!target.mock || !status?.configured) return null;

  const replanSharing = () => {
    const all = targets.state === "ok" ? targets.value : [];
    all.filter((t) => t.mock && t.endpoint === target.endpoint).forEach((t) => void planTarget(t.name));
  };
  const run = async (what: "start" | "stop") => {
    setBusy(what);
    setError(null);
    try {
      setStatus(await (what === "start" ? api.startMock(target.name) : api.stopMock(target.name)));
      replanSharing();
    } catch (e) {
      setError(e as AppError);
      refresh();
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className={compact ? "mock compact" : "mock"}>
      {!compact && <span className="muted small">Mock <code>{status.seed}</code></span>}
      <Tag tone={status.running ? "ok" : "neutral"} title={status.url}>{status.running ? "mock running" : "mock stopped"}</Tag>
      {busy === "start" && <Spinner label={compact ? undefined : "Starting (the first time downloads json-server)…"} />}
      {!busy && !status.running && <button className="link" onClick={() => void run("start")}>Start mock</button>}
      {!busy && status.running && status.startedHere && <button className="link" onClick={() => void run("stop")}>Stop</button>}
      {!busy && status.running && !status.startedHere && !compact && <span className="muted small">started outside the app</span>}
      {error && <span className="error-text small" title={error.message}>{error.message.split("\n")[0]}</span>}
    </div>
  );
}
