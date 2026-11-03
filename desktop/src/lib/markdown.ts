// A tiny Markdown reader for the Plan workflow's comment: headings, paragraphs, bullet lists and tables with
// inline code, bold and <br>. It produces data, which React renders as text, so nothing in a comment is ever
// interpreted as HTML.

export type Inline = { type: "text" | "code" | "bold"; text: string } | { type: "break" };
export type Block =
  | { type: "heading"; level: number; content: Inline[] }
  | { type: "paragraph"; content: Inline[] }
  | { type: "list"; items: Inline[][] }
  | { type: "table"; header: Inline[][]; rows: Inline[][][] };

export function inline(text: string): Inline[] {
  const out: Inline[] = [];
  const pattern = /(`[^`]+`|\*\*[^*]+\*\*|<br\s*\/?>)/gi;
  let last = 0;
  for (const m of text.matchAll(pattern)) {
    if (m.index! > last) out.push({ type: "text", text: text.slice(last, m.index) });
    const token = m[0];
    if (token.startsWith("`")) out.push({ type: "code", text: token.slice(1, -1) });
    else if (token.startsWith("**")) out.push({ type: "bold", text: token.slice(2, -2) });
    else out.push({ type: "break" });
    last = m.index! + token.length;
  }
  if (last < text.length) out.push({ type: "text", text: text.slice(last) });
  return out;
}

function cells(line: string): string[] {
  // Split on | that isn't escaped as \|.
  return line.trim().replace(/^\||\|$/g, "").split(/(?<!\\)\|/).map((c) => c.trim().replace(/\\\|/g, "|"));
}

export function parse(markdown: string): Block[] {
  const blocks: Block[] = [];
  const lines = markdown.replace(/\r\n/g, "\n").split("\n");
  let i = 0;
  while (i < lines.length) {
    const line = lines[i]!;
    if (!line.trim() || /^<!--.*-->$/.test(line.trim())) { i++; continue; }
    const heading = /^(#{1,6})\s+(.*)$/.exec(line);
    if (heading) { blocks.push({ type: "heading", level: heading[1]!.length, content: inline(heading[2]!) }); i++; continue; }
    if (line.trim().startsWith("|") && /^\s*\|?\s*:?-{3,}/.test(lines[i + 1] ?? "")) {
      const header = cells(line).map(inline);
      i += 2;
      const rows: Inline[][][] = [];
      while (i < lines.length && lines[i]!.trim().startsWith("|")) rows.push(cells(lines[i++]!).map(inline));
      blocks.push({ type: "table", header, rows });
      continue;
    }
    if (/^\s*[-*]\s+/.test(line)) {
      const items: Inline[][] = [];
      while (i < lines.length && /^\s*[-*]\s+/.test(lines[i]!)) items.push(inline(lines[i++]!.replace(/^\s*[-*]\s+/, "")));
      blocks.push({ type: "list", items });
      continue;
    }
    const para: string[] = [];
    while (i < lines.length && lines[i]!.trim() && !/^(#{1,6}\s|\s*\||\s*[-*]\s)/.test(lines[i]!)) para.push(lines[i++]!);
    blocks.push({ type: "paragraph", content: inline(para.join(" ")) });
  }
  return blocks;
}
