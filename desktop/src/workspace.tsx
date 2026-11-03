// The repositories open side by side. The active one is the RepoProvider's; this switches between them.

import { createContext, useContext } from "react";

export interface Workspace {
  switchTo: (path: string) => Promise<void>;
  openAnother: () => Promise<void>;
}

export const WorkspaceContext = createContext<Workspace>({ switchTo: async () => undefined, openAnother: async () => undefined });

export const useWorkspace = () => useContext(WorkspaceContext);
