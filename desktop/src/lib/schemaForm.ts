// Turns a kind's JSON Schema into form fields, and form input back into a resource. The schema decides what can
// be edited; the engine decides whether the result is valid (resource.check), so there's one validator.

import type { Json, Problem, Resource } from "./types";

export type FieldType = "string" | "integer" | "number" | "boolean" | "list" | "json";

export interface Field {
  name: string;
  type: FieldType;
  required: boolean;
  description?: string;
  pattern?: string;
  minimum?: number;
  enum?: string[];
}

type Schema = { properties?: Record<string, Record<string, unknown>>; required?: string[] };

export function fieldsOf(schema: unknown): Field[] {
  const s = (schema ?? {}) as Schema;
  const required = new Set(s.required ?? []);
  return Object.entries(s.properties ?? {}).map(([name, p]) => {
    const t = Array.isArray(p.type) ? p.type.find((x) => x !== "null") : p.type;
    const items = (p.items ?? {}) as { type?: string };
    const type: FieldType =
      t === "string" || t === "integer" || t === "number" || t === "boolean" ? t
      : t === "array" && items.type === "string" ? "list"
      : "json";
    return {
      name,
      type,
      required: required.has(name),
      description: typeof p.description === "string" ? p.description : undefined,
      pattern: typeof p.pattern === "string" ? p.pattern : undefined,
      minimum: typeof p.minimum === "number" ? p.minimum : undefined,
      enum: Array.isArray(p.enum) ? p.enum.map(String) : undefined,
    };
  });
}

/** What an input shows for a value. Lists are one item per line. */
export function toInput(field: Field, value: Json | undefined): string | boolean {
  if (field.type === "boolean") return value === true;
  if (value === undefined || value === null) return "";
  if (field.type === "list" && Array.isArray(value)) return value.map(String).join("\n");
  if (field.type === "json") return JSON.stringify(value, null, 2);
  return String(value);
}

/**
 * An input's value as JSON. Numbers that don't parse stay as text, so the engine reports them against the
 * field instead of the form silently dropping them. Empty optional fields are left out.
 */
export function fromInput(field: Field, raw: string | boolean): Json | undefined {
  if (field.type === "boolean") return raw === true;
  const text = String(raw);
  if (text.trim() === "") return field.type === "list" ? [] : undefined;
  switch (field.type) {
    case "integer":
    case "number": {
      const n = Number(text.trim());
      return Number.isFinite(n) && (field.type === "number" || Number.isInteger(n)) ? n : text;
    }
    case "list":
      return text.split("\n").map((l) => l.trim()).filter((l) => l !== "");
    case "json":
      try {
        return JSON.parse(text) as Json;
      } catch {
        return text;
      }
    default:
      return text;
  }
}

export function buildResource(fields: Field[], inputs: Record<string, string | boolean>): Resource {
  const resource: Resource = {};
  for (const field of fields) {
    const value = fromInput(field, inputs[field.name] ?? (field.type === "boolean" ? false : ""));
    if (value !== undefined) resource[field.name] = value;
  }
  return resource;
}

export function inputsOf(fields: Field[], resource: Resource | null): Record<string, string | boolean> {
  return Object.fromEntries(fields.map((f) => [f.name, toInput(f, resource?.[f.name])]));
}

/** Problems keyed by the top-level field their pointer names ("/topics/2" → "topics"); "" for the whole resource. */
export function problemsByField(problems: Problem[]): Map<string, string[]> {
  const map = new Map<string, string[]>();
  for (const p of problems) {
    const field = (p.pointer.split("/")[1] ?? "").replace(/~1/g, "/").replace(/~0/g, "~");
    map.set(field, [...(map.get(field) ?? []), p.message.replace(/^\/[^:]*: /, "")]);
  }
  return map;
}

/** "Add Ann to dev" → "gitops/add-ann-to-dev". */
export function branchFor(title: string): string {
  const slug = title.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 48).replace(/-+$/, "");
  return `gitops/${slug || "config-change"}`;
}
