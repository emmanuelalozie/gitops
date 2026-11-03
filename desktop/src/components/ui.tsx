import type { ReactNode } from "react";
import { healthLabel, type Health } from "../lib/model";
import type { AppError, Problem } from "../lib/types";

export function HealthBadge({ health }: { health: Health }) {
  return (
    <span className={`badge health-${health}`}>
      <span className="dot" aria-hidden />
      {healthLabel[health]}
    </span>
  );
}

export function Tag({ children, tone = "neutral", title }: { children: ReactNode; tone?: "neutral" | "info" | "warn" | "danger" | "ok"; title?: string }) {
  return <span className={`tag tone-${tone}`} title={title}>{children}</span>;
}

export function Counts({ summary }: { summary: { create: number; update: number; delete: number } }) {
  return (
    <span className="counts" aria-label={`${summary.create} to create, ${summary.update} to update, ${summary.delete} to delete`}>
      <span className="c-create">+{summary.create}</span>
      <span className="c-update">~{summary.update}</span>
      <span className="c-delete">−{summary.delete}</span>
    </span>
  );
}

export function Spinner({ label }: { label?: string }) {
  return (
    <span className="spinner" role="status">
      <span className="spin" aria-hidden />
      {label}
    </span>
  );
}

/** An error with its code, plus every validation problem when the engine sent them. */
export function ErrorBox({ error, action }: { error: AppError; action?: ReactNode }) {
  const problems = (error.details as { problems?: Problem[] } | undefined)?.problems;
  return (
    <div className="error-box" role="alert">
      <div className="error-head">
        <strong>{headline(error)}</strong>
        <code>{error.code}</code>
      </div>
      <pre className="error-message">{error.message}</pre>
      {problems && problems.length > 0 && (
        <ul className="problem-list">
          {problems.map((p, i) => (
            <li key={i}>
              {p.file && <code>{p.file}</code>} {p.pointer && <code className="pointer">{p.pointer}</code>} {p.message}
            </li>
          ))}
        </ul>
      )}
      {action}
    </div>
  );
}

function headline(error: AppError): string {
  switch (error.code) {
    case "invalid_config": return "The config doesn't pass validation";
    case "unreachable": return "Couldn't reach the live system";
    case "api": return "The live system returned an error";
    case "plan_changed": return "The plan changed since you approved it";
    case "token_missing": return "A token is missing";
    case "not_allowed": return "Not allowed";
    case "engine_stopped": return "The engine stopped";
    case "engine_missing": return "The engine isn't installed";
    default: return "Something went wrong";
  }
}

export function Empty({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="empty">
      <strong>{title}</strong>
      {children && <div>{children}</div>}
    </div>
  );
}

export function Tabs<T extends string>({ tabs, value, onChange }: { tabs: { id: T; label: ReactNode }[]; value: T; onChange: (id: T) => void }) {
  return (
    <div className="tabs" role="tablist">
      {tabs.map((t) => (
        <button key={t.id} role="tab" aria-selected={t.id === value} className={t.id === value ? "tab active" : "tab"} onClick={() => onChange(t.id)}>
          {t.label}
        </button>
      ))}
    </div>
  );
}
