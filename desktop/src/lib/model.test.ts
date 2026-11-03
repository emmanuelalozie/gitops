import { affectedTargets, columns, formatValue, health, relativeTime, shortId, targetOfPath, tokenNames } from "./model";
import { demoTargets } from "./demo";
import type { Plan } from "./types";
import planFixture from "../fixtures/plan.json";

const plan = planFixture as Plan;

describe("health", () => {
  it("follows the plan", () => {
    expect(health(undefined)).toBe("unknown");
    expect(health({ state: "loading" })).toBe("loading");
    expect(health({ state: "error", error: { kind: "engine", code: "api", message: "x" } })).toBe("error");
    expect(health({ state: "ok", value: plan, at: 0 })).toBe("drift");
    expect(health({ state: "ok", value: { ...plan, hasChanges: false, changes: [] }, at: 0 })).toBe("in-sync");
    expect(health({ state: "ok", value: { ...plan, skipped: true }, at: 0 })).toBe("skipped");
  });
});

describe("formatValue", () => {
  it("prints values like the CLI", () => {
    expect(formatValue("a")).toBe('"a"');
    expect(formatValue(3)).toBe("3");
    expect(formatValue(["x", "y"])).toBe('["x","y"]');
    expect(formatValue(null, true)).toBe("(unset)");
  });
});

describe("paths and targets", () => {
  it("finds the target a config file belongs to", () => {
    expect(targetOfPath("config/dev/users/bret.json")).toBe("dev");
    expect(targetOfPath("config\\prod\\target.json")).toBe("prod");
    expect(targetOfPath(".github/workflows/ci.yml")).toBeNull();
  });

  it("lists the targets a burst of changes touches", () => {
    expect(affectedTargets(["config/prod/users/a.json", "config/dev/users/b.json", "config/nope/x.json", "README.md"], demoTargets))
      .toEqual(["dev", "prod"]);
  });
});

it("asks for the tokens the targets use", () => {
  expect(tokenNames(demoTargets)).toEqual(["GITHUB_TOKEN", "GITOPS_ADMIN_TOKEN"]);
});

it("keeps ids short", () => {
  expect(shortId("8f9fd60b75ee9d255d98")).toBe("8f9fd60b75ee");
  expect(shortId(null)).toBe("—");
});

it("says how long ago", () => {
  expect(relativeTime(0, 5_000)).toBe("just now");
  expect(relativeTime(0, 90_000)).toBe("2 min ago");
  expect(relativeTime(0, 3 * 86_400_000)).toBe("3 days ago");
});

it("orders columns by schema first", () => {
  expect(columns([{ website: "x", id: 1, username: "a" }], ["id", "username", "email"])).toEqual(["id", "username", "website"]);
});
