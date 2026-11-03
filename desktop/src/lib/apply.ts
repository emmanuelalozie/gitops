// Folds the engine's apply.progress notifications into one row per resource, in the order they started.

import type { ApplyProgress } from "./types";

export interface StepState {
  step: ApplyProgress["step"];
  state: ApplyProgress["state"];
  error?: string;
}

export interface ProgressRow {
  key: string;
  steps: StepState[];
}

export function foldProgress(events: ApplyProgress[]): ProgressRow[] {
  const rows: ProgressRow[] = [];
  for (const e of events) {
    let row = rows.find((r) => r.key === e.key);
    if (!row) rows.push((row = { key: e.key, steps: [] }));
    const existing = row.steps.find((s) => s.step === e.step);
    const next: StepState = { step: e.step, state: e.state, error: e.error?.message };
    if (existing) Object.assign(existing, next);
    else row.steps.push(next);
  }
  return rows;
}

export const stepLabel: Record<ApplyProgress["step"], string> = {
  delete: "delete",
  "delete-to-replace": "delete (to replace)",
  update: "update",
  create: "create",
  "create-replacement": "create (replacement)",
};
