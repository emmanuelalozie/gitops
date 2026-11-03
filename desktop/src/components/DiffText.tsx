/** A unified diff from git, one coloured line each. */
export function DiffText({ text }: { text: string }) {
  if (!text.trim()) return <p className="muted">No textual change.</p>;
  return (
    <pre className="difftext">
      {text.split("\n").map((line, i) => {
        const kind = line.startsWith("+++") || line.startsWith("---") ? "meta"
          : line.startsWith("@@") ? "hunk"
          : line.startsWith("+") ? "add"
          : line.startsWith("-") ? "del"
          : "ctx";
        return <span key={i} className={`dl dl-${kind}`}>{line || " "}{"\n"}</span>;
      })}
    </pre>
  );
}
