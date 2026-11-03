import { useCallback, useState } from "react";
import { api } from "./lib/api";
import type { RepoInfo } from "./lib/types";
import { RepoProvider, useRepo } from "./state";
import { Changes } from "./views/Changes";
import { Compare } from "./views/Compare";
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
    case "settings":
      return <Settings />;
  }
}
