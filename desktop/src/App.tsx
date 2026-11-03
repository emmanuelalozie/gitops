import { useCallback, useMemo, useState } from "react";
import { WorkspaceContext, type Workspace } from "./workspace";
import { WorkspacePage } from "./views/Workspace";
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
    // Closing one repository shows the next open one, or the welcome screen.
    void api.closeRepo().then(setRepo, () => setRepo(null));
  }, []);
  const workspace = useMemo<Workspace>(() => ({
    switchTo: async (path: string) => setRepo(await api.switchRepo(path)),
    openAnother: async () => {
      const path = await api.pickFolder();
      if (path) setRepo(await api.openRepo(path));
    },
  }), []);
  if (!repo) return <Welcome onOpen={setRepo} />;
  return (
    <WorkspaceContext.Provider value={workspace}>
      {/* Keyed by path: switching repository starts that one's view from a clean state. */}
      <RepoProvider key={repo.path} initial={repo} onClose={close}>
        <Shell>
          <Router />
        </Shell>
      </RepoProvider>
    </WorkspaceContext.Provider>
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
    case "workspace":
      return <WorkspacePage />;
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
