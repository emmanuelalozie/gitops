import { parse, type Inline } from "../lib/markdown";

function Inlines({ parts }: { parts: Inline[] }) {
  return (
    <>
      {parts.map((p, i) =>
        p.type === "break" ? <br key={i} /> : p.type === "code" ? <code key={i}>{p.text}</code> : p.type === "bold" ? <strong key={i}>{p.text}</strong> : <span key={i}>{p.text}</span>,
      )}
    </>
  );
}

/** The subset of Markdown the Plan workflow writes, rendered as React text (never as HTML). */
export function Markdown({ text }: { text: string }) {
  return (
    <div className="markdown">
      {parse(text).map((block, i) => {
        switch (block.type) {
          case "heading":
            return block.level <= 2 ? <h3 key={i}><Inlines parts={block.content} /></h3> : <h4 key={i}><Inlines parts={block.content} /></h4>;
          case "paragraph":
            return <p key={i}><Inlines parts={block.content} /></p>;
          case "list":
            return <ul key={i}>{block.items.map((item, j) => <li key={j}><Inlines parts={item} /></li>)}</ul>;
          case "table":
            return (
              <table key={i} className="md-table">
                <thead><tr>{block.header.map((h, j) => <th key={j}><Inlines parts={h} /></th>)}</tr></thead>
                <tbody>{block.rows.map((r, j) => <tr key={j}>{r.map((c, k) => <td key={k}><Inlines parts={c} /></td>)}</tr>)}</tbody>
              </table>
            );
        }
      })}
    </div>
  );
}
