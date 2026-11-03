import type { ReactNode } from "react";
import { health } from "../lib/model";
import { inDesktop } from "../lib/api";
import { useRepo, type Page } from "../state";

function NavButton({ page, current, children, count, tone }: { page: Page; current: Page; children: ReactNode; count?: number; tone?: "info" }) {
  const { go } = useRepo();
  const active = page.name === current.name && (page.name !== "target" || (current.name === "target" && current.target === page.target));
  return (
    <button className={active ? "nav active" : "nav"} aria-current={active ? "page" : undefined} onClick={() => go(page)}>
      {children}
      {count !== undefined && count > 0 && <span className={tone ? `nav-count ${tone}` : "nav-count"}>{count}</span>}
    </button>
  );
}

export function Shell({ children }: { children: ReactNode }) {
  const { repo, targets, plans, page, validation, lint, changeCount, close } = useRepo();
  const problemCount =
    (validation.state === "ok" ? validation.value.targets.reduce((n, t) => n + t.problems.length, 0) : 0) +
    (lint.state === "ok" ? lint.value.findings.length : 0);
  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="repo">
          <div className="repo-name" title={repo.path}>{repo.name}</div>
          <div className="repo-meta">
            {repo.git.branch ? <code>{repo.git.branch}</code> : <span>detached HEAD</span>}
            {repo.git.github && <span> · {repo.git.github.owner}/{repo.git.github.name}</span>}
          </div>
          {!inDesktop && <div className="demo-pill">Demo data</div>}
        </div>
        <nav>
          <NavButton page={{ name: "overview" }} current={page}>Overview</NavButton>
          <NavButton page={{ name: "changes" }} current={page} count={changeCount} tone="info">Changes</NavButton>
          <NavButton page={{ name: "problems" }} current={page} count={problemCount}>Problems</NavButton>
          <NavButton page={{ name: "compare" }} current={page}>Compare</NavButton>
          <NavButton page={{ name: "graph" }} current={page}>Graph</NavButton>
          {repo.git.github && <NavButton page={{ name: "github" }} current={page}>GitHub</NavButton>}
          <div className="nav-heading">Targets</div>
          {targets.state === "ok" &&
            targets.value.map((t) => (
              <NavButton key={t.name} page={{ name: "target", target: t.name }} current={page}>
                <span className={`dot health-${health(plans[t.name])}`} aria-hidden />
                <span className="nav-target">{t.name}</span>
                <span className="nav-kind">{t.kind}</span>
              </NavButton>
            ))}
        </nav>
        <div className="sidebar-foot">
          <NavButton page={{ name: "settings" }} current={page}>Settings</NavButton>
          <button className="nav" onClick={close}>Close repository</button>
        </div>
      </aside>
      <main className="content">{children}</main>
    </div>
  );
}
