import { useMemo } from "react";
import { COLUMNS, layoutGraph, type GraphNode } from "../lib/graph";
import { health } from "../lib/model";
import { Empty, ErrorBox, Spinner } from "../components/ui";
import { useRepo } from "../state";

const W = 172, H = 46, GAP_X = 46, GAP_Y = 18, TOP = 36;

/** How the parts depend on each other, from CI jobs to live systems. Click a target or rule to open it. */
export function Graph() {
  const { targets, lint, plans, go } = useRepo();
  const graph = useMemo(
    () => (targets.state === "ok" ? layoutGraph(targets.value, lint.state === "ok" ? lint.value : null, (t) => health(plans[t])) : null),
    [targets, lint, plans],
  );
  if (targets.state === "loading" || lint.state === "loading") return <Spinner />;
  if (targets.state === "error") return <ErrorBox error={targets.error} />;
  if (!graph || graph.nodes.length === 0) return <Empty title="Nothing to draw" />;

  const x = (n: GraphNode) => n.column * (W + GAP_X);
  const y = (n: GraphNode) => TOP + n.row * (H + GAP_Y);
  const byId = new Map(graph.nodes.map((n) => [n.id, n]));
  const width = COLUMNS.length * (W + GAP_X) - GAP_X;
  const height = TOP + graph.rows * (H + GAP_Y);
  const broken = graph.nodes.filter((n) => n.tone === "danger");

  return (
    <div className="page wide">
      <header className="page-head">
        <div>
          <h1>Graph</h1>
          <p className="muted">From the CI jobs a branch rule requires, through the targets that manage them, to the live systems they change.</p>
        </div>
      </header>
      {broken.length > 0 && (
        <p className="notice warn">
          {broken.length} required {broken.length === 1 ? "check has" : "checks have"} no job reporting {broken.length === 1 ? "it" : "them"}:{" "}
          {broken.map((b) => <code key={b.id}>{b.label}</code>)}. Pull requests into those branches would wait forever.
        </p>
      )}
      <div className="graph-wrap">
        <svg className="graph" viewBox={`-2 0 ${width + 4} ${height}`} width={width} height={height} role="img" aria-label="Dependency graph">
          {COLUMNS.map((c, i) => (
            <text key={c} className="graph-col" x={i * (W + GAP_X)} y={14}>{c}</text>
          ))}
          {graph.edges.map((e) => {
            const a = byId.get(e.from)!, b = byId.get(e.to)!;
            const x1 = x(a) + W, y1 = y(a) + H / 2, x2 = x(b), y2 = y(b) + H / 2, mid = (x1 + x2) / 2;
            return <path key={`${e.from}>${e.to}`} className={e.broken ? "graph-edge broken" : "graph-edge"} d={`M${x1},${y1} C${mid},${y1} ${mid},${y2} ${x2},${y2}`} />;
          })}
          {graph.nodes.map((n) => {
            const clickable = Boolean(n.target);
            return (
              <g key={n.id} className={`graph-node tone-${n.tone}${clickable ? " clickable" : ""}`} transform={`translate(${x(n)},${y(n)})`}
                onClick={clickable ? () => go({ name: "target", target: n.target! }) : undefined}
                onKeyDown={clickable ? (e) => e.key === "Enter" && go({ name: "target", target: n.target! }) : undefined}
                tabIndex={clickable ? 0 : undefined} role={clickable ? "link" : undefined}>
                <title>{`${n.label}\n${n.sub}`}</title>
                <rect width={W} height={H} rx={7} />
                <text x={10} y={19} className="graph-label">{clip(n.label, 21)}</text>
                <text x={10} y={35} className="graph-sub">{clip(n.sub, 27)}</text>
              </g>
            );
          })}
        </svg>
      </div>
    </div>
  );
}

const clip = (text: string, max: number) => (text.length > max ? text.slice(0, max - 1) + "…" : text);
