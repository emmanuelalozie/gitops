// The repository as a graph, left to right: workflow jobs → the status checks they report → branch rules that
// require them → the targets that manage those rules → every target → the live system it changes.
// It answers "what depends on what": rename a CI job and you can see which rule stops being satisfied.

import type { Health } from "./model";
import type { Lint, Target } from "./types";

export const COLUMNS = ["Workflow jobs", "Required checks", "Branch rules", "Targets", "Live systems"] as const;

export type Tone = "neutral" | "danger" | Health;

export interface GraphNode {
  id: string;
  column: number;
  row: number;
  label: string;
  sub: string;
  tone: Tone;
  /** The target page to open when clicked. */
  target?: string;
}

export interface GraphEdge {
  from: string;
  to: string;
  broken: boolean;
}

export interface Graph {
  nodes: GraphNode[];
  edges: GraphEdge[];
  rows: number;
}

export function layoutGraph(targets: Target[], lint: Lint | null, health: (target: string) => Health): Graph {
  const columns: GraphNode[][] = COLUMNS.map(() => []);
  const edges: GraphEdge[] = [];
  const ids = new Set<string>();
  const add = (node: Omit<GraphNode, "row">) => {
    if (ids.has(node.id)) return;
    ids.add(node.id);
    columns[node.column]!.push({ ...node, row: columns[node.column]!.length });
  };
  const edge = (from: string, to: string, broken = false) => {
    if (!edges.some((e) => e.from === from && e.to === to)) edges.push({ from, to, broken });
  };

  // Right half first, in target order, so the left half can follow the same order and edges cross less.
  const sorted = [...targets].sort((a, b) => a.name.localeCompare(b.name));
  for (const t of sorted) {
    add({ id: `target:${t.name}`, column: 3, label: t.name, sub: t.kind, tone: health(t.name), target: t.name });
    const live = `live:${t.endpoint ?? t.name}`;
    add({ id: live, column: 4, label: t.endpoint ?? "(no endpoint)", sub: t.type === "github" ? "GitHub" : t.type === "rest" ? "REST API" : "users API", tone: "neutral" });
    edge(`target:${t.name}`, live);
  }
  for (const r of lint?.requirements ?? []) {
    const rule = `rule:${r.target}/${r.branch}`;
    add({ id: rule, column: 2, label: r.branch, sub: `rule in ${r.target}`, tone: "neutral", target: r.target });
    edge(rule, `target:${r.target}`);
    const check = `check:${r.check}`;
    const missing = r.satisfiedBy.length === 0;
    add({ id: check, column: 1, label: r.check, sub: missing ? "no job reports this" : "status check", tone: missing ? "danger" : "neutral" });
    edge(check, rule, missing);
    for (const s of r.satisfiedBy) {
      const job = `job:${s.file}#${s.job}`;
      add({ id: job, column: 0, label: s.job, sub: `${s.workflow} · ${s.file.replace(/^\.github\/workflows\//, "")}`, tone: "neutral" });
      edge(job, check);
    }
  }
  const nodes = columns.flat();
  return { nodes, edges, rows: Math.max(1, ...columns.map((c) => c.length)) };
}
