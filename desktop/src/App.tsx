import { useCallback, useState } from "react";
import { api } from "./lib/api";
import type { RepoInfo } from "./lib/types";
import { RepoProvider, useRepo } from "./state";
import { AddTarget } from "./views/AddTarget";
import { AllChanges } from "./views/AllChanges";
import { Audit } from "./views/Audit";
import { Search } from "./views/Search";
import { Changes } from "./views/Changes";
import { Compare } from "./views/Compare";
import { GitHub } from "./views/GitHub";
import { Graph } from "./views/Graph";
import { Overview } from "./views/Overview";
import { Problems } from "./views/Problems";
import { Settings } from "./views/Settings";
import { Shell } from "./views/Shell";
import { TargetPage } from "./views/TargetPage";
import { Welcome } from "./views/Welcome";

export function App() {
  const [repo, setRepo] = useState<RepoInfo | null>(null);
  const close = useCallback(() => {
    void api.closeRepo();
    setRepo(null);
  }, []);
  if (!repo) return <Welcome onOpen={setRepo} />;
  return (
    // Keyed by path: opening another repository starts from a clean state.
    <RepoProvider key={repo.path} initial={repo} onClose={close}>
      <Shell>
        <Router />
      </Shell>
    </RepoProvider>
  );
}

function Router() {
  const { page } = useRepo();
  switch (page.name) {
    case "overview":
      return <Overview />;
    case "target":
      return <TargetPage name={page.target} />;
    case "problems":
      return <Problems />;
    case "changes":
      return <Changes />;
    case "compare":
      return <Compare />;
    case "github":
      return <GitHub />;
    case "graph":
      return <Graph />;
    case "add-target":
      return <AddTarget />;
    case "all-changes":
      return <AllChanges />;
    case "audit":
      return <Audit />;
    case "search":
      return <Search query={page.query} />;
    case "settings":
      return <Settings />;
  }
}
