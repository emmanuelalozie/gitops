// What the drift watch tells people: only changes for the worse since the last look, so a target that stays in
// drift doesn't notify every half hour, and nothing notifies on the first look.

import { health, type Health, type Load } from "./model";
import type { DriftIssue, Plan } from "./types";

export interface Snapshot {
  health: Record<string, Health>;
  openIssues: number[];
}

export interface Alert {
  title: string;
  body: string;
}

export function snapshot(plans: Record<string, Load<Plan>>, issues: DriftIssue[] | null): Snapshot {
  return {
    health: Object.fromEntries(Object.entries(plans).map(([name, p]) => [name, health(p)])),
    openIssues: (issues ?? []).filter((i) => i.state === "open").map((i) => i.number),
  };
}

export function alerts(before: Snapshot | null, plans: Record<string, Load<Plan>>, issues: DriftIssue[] | null): Alert[] {
  if (!before) return [];
  const out: Alert[] = [];
  for (const [name, plan] of Object.entries(plans)) {
    const was = before.health[name];
    if (was === undefined) continue; // a target the last look didn't see (just added): nothing to compare with
    const now = health(plan);
    if (now === "drift" && was !== "drift" && plan.state === "ok") {
      const s = plan.value.summary;
      out.push({ title: `Drift in ${name}`, body: `Live differs from Git: ${s.create} to create, ${s.update} to update, ${s.delete} to delete.` });
    } else if (now === "error" && was !== "error" && plan.state === "error") {
      out.push({ title: `Can't plan ${name}`, body: plan.error.message.split("\n")[0]! });
    } else if (now === "in-sync" && (was === "drift" || was === "error")) {
      out.push({ title: `${name} is back in sync`, body: "Live matches Git again." });
    }
  }
  for (const issue of issues ?? []) {
    if (issue.state === "open" && !before.openIssues.includes(issue.number)) {
      out.push({ title: `Drift issue #${issue.number}`, body: `The daily check opened "${issue.title}".` });
    }
  }
  return out;
}
