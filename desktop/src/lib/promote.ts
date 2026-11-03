// Promotion: copy chosen resources from one target's Git config to another's (dev → prod), as file writes the
// engine checks. The comparison is the engine's plan that would make `to` match `from`.

import type { Change, Plan, Resource } from "./types";

export type PromotionOp =
  | { type: "write"; key: string; resource: Resource; previousKey?: string }
  | { type: "delete"; key: string };

export function promotionOps(plan: Plan, chosen: Set<string>): PromotionOp[] {
  return plan.changes
    .filter((c) => chosen.has(changeId(c)))
    .map((c): PromotionOp =>
      c.action === "delete"
        ? { type: "delete", key: c.key }
        : { type: "write", key: c.key, resource: c.desired!, previousKey: c.action === "update" ? String(c.live?.[plan.keyField] ?? c.key) : undefined },
    );
}

export const changeId = (c: Change) => `${c.action}:${c.key}`;

/** By default promote additions and updates; removing things from the target is opt-in. */
export function defaultChoice(plan: Plan): Set<string> {
  return new Set(plan.changes.filter((c) => c.action !== "delete").map(changeId));
}

export function promotionTitle(from: string, to: string, keys: string[]): string {
  const list = keys.length <= 3 ? keys.join(", ") : `${keys.slice(0, 2).join(", ")} and ${keys.length - 2} more`;
  return `Promote ${list} from ${from} to ${to}`;
}
