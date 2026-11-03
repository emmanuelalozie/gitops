# Engine protocol

The engine (the Java code in `src/`) is the only thing that compares Git with live systems. The CLI, the
GitHub workflows and the desktop app (`desktop/`) all ask it; none of them work out a diff on their own.
This page describes the machine-readable side: the JSON the CLI writes, and the JSON-RPC server the desktop
app talks to.

## Versioning

Every JSON document has `schemaVersion` (today `1`). Adding a field keeps the version; renaming, removing or
changing the meaning of one bumps it. The desktop app calls `engine.version` at startup and refuses to run with
an engine whose `schemaVersion` it doesn't know.

The plan format is pinned by `src/main/resources/schemas/output/plan.v1.schema.json` and by the golden files in
`desktop/src/fixtures/`, which the Java tests (`ContractTest`) and the app's TypeScript tests both check.
After a deliberate change, regenerate them with `./gradlew test -Dgitops.updateFixtures=true`.

## Command line

| Command | JSON |
| --- | --- |
| `plan --format json` | A plan (below) on stdout |
| `plan --json-out FILE` | The same plan in a file, next to text or Markdown output from the same live read |
| `apply --plan-id ID` | Applies only if the plan is still the one with that id; otherwise exits with 3 and changes nothing |
| `apply --respect-window` | A target outside its `applyWindow` isn't changed; prints when the window opens and exits 0 |
| `search --query TEXT` | Resources in Git across every target whose key or any value contains the text |
| `apply --format json` | Progress on stderr, the result on stdout |
| `validate --format json` | `{ok, targets: [{name, kind, files, ok, problems: [{file, pointer, message}]}], warnings}` |
| `targets --format json [--out FILE]` | `{targets: [{name, kind, keyField, endpoint, prune, verifyAfterApply, localApply, workflows, tokenVariable, tokenRequired, tokenPresent, resources, ...}]}` |
| `lint --format json` | `{ok, findings, requirements, checks}` (see Lint) |
| `version --format json` | `{engineVersion, schemaVersion, java}` |

With `--format json`, errors are JSON on stderr: `{"error": {"code", "message", "details"}}`.

| `code` | Meaning | `details` |
| --- | --- | --- |
| `invalid_config` | Config fails validation | `problems: [{file, pointer, message}]` |
| `unreachable` | The live system can't be reached | `method`, `url` |
| `api` | The live system answered with an error status | `method`, `url`, `status`, `body` |
| `plan_changed` | `--plan-id` doesn't match the current plan | `expected`, `actual` |
| `token_missing` | The target needs a token that isn't set | `variable` |
| `not_allowed` | Valid but not permitted, e.g. deleting everything, or a desktop apply to a non-`localApply` target | |
| `usage` | Wrong command, option or parameter | |
| `internal` | Anything else | |

Exit codes: 0 success, 1 error, 2 drift with `--fail-on-drift`, 3 plan changed.

## Plan

```json
{
  "schemaVersion": 1, "target": "dev", "description": "users @ http://127.0.0.1:3000",
  "kind": "users", "keyField": "username", "skipped": false,
  "planId": "8f9f…", "hasChanges": true,
  "summary": {"create": 1, "update": 2, "delete": 1},
  "changes": [{
    "action": "update", "key": "Bret", "replace": false,
    "desired": {"id": 1, "username": "Bret", "email": "new@b.io", "phone": "555-0100"},
    "live":    {"id": 1, "username": "Bret", "email": "old@b.io", "phone": "555-0100"},
    "diffs": [{"field": "email", "live": "old@b.io", "liveMissing": false, "desired": "new@b.io"}]
  }],
  "unmanaged": []
}
```

Each change also has `file`, its path in Git (where it is, or would be), in plans the engine serves. `type` is `users`, `github` or `rest`; for a `rest` target `kind` is its resource (such as `items`). `desired` and `live` hold managed fields only (those in the kind's schema). `replace` marks an update the
provider makes by deleting and creating the resource (a user whose `id` changes). A target skipped for a
missing token has `skipped: true`, `skipReason`, `planId: null` and no changes.

**Plan ids.** `planId` is a sha256 over what the plan would change: each change's action, key and managed
fields (desired and live), and the unmanaged keys. Object keys and list items are sorted first, because the
planner compares lists as sets, so a reordered list or a change to a field Git doesn't manage keeps the id.
Anything that would change what apply does changes it. That's what makes "apply what I approved" safe: the
app shows a plan, the person approves it, and `apply` refuses if the live system moved in between.

## Engine server

`gitops serve --config-dir <repo>/config` reads JSON-RPC 2.0 requests from stdin, one per line, and writes one
response or notification per line to stdout. Nothing else is ever written to stdout; logs go to stderr. When
stdin closes, it waits for running requests (an apply finishes) and exits.

Requests run in parallel on a small pool; anything that changes a target (`plan.apply`, `resource.write`,
`resource.delete`) holds that target's lock. Errors use code `-32000` with the engine error above as `data`,
`-32601` for an unknown method and `-32700` for a line that isn't JSON.

| Method | Params | Result |
| --- | --- | --- |
| `engine.version` | | `{engineVersion, schemaVersion, java}` |
| `targets.list` | | as `targets --format json` |
| `config.validate` | `target?` | as `validate --format json` (without warnings) |
| `plan.compute` | `target` | a plan; a skipped plan when the target's token isn't set |
| `plan.apply` | `target`, `planId`, `allowDeleteAll?` | `{applied, verified, verifyAfterApply, planId, after, deferred, health}`; only for targets with `"localApply": true`; respects the apply window (`deferred` says why nothing ran) and runs the health check after verifying |
| `live.fetch` | `target` | `{resources: [...]}` (everything the live system returns) |
| `targets.compare` | `from`, `to` | a plan that would make `to`'s Git config match `from`'s, plus `from` and `to` |
| `resources.list` | `target` | `{resources: [{key, file, resource}]}` from Git; an unreadable file has `error` and `resource: null` |
| `kind.schema` | `target` (or `kind` for a built-in kind) | the JSON Schema of the target's resources; a `rest` target has its own |
| `resource.check` | `target`, `resource`, `previousKey?` | `{ok, problems}`: schema, duplicate key, unique fields |
| `resource.write` | `target`, `resource`, `previousKey?` | `{file, removed: [...]}`; writes in the canonical file format, refuses invalid resources |
| `resource.delete` | `target`, `key` | `{file, removed}` |
| `repo.lint` | | as `lint --format json` |
| `resources.search` | `query`, `limit?` | `{results: [{target, kind, key, file, fields, resource}], truncated}` |
| `health.check` | `target` | `{ok, url, status, message, seconds}`, or null without a health check |
| `targets.create` | `name`, `settings`, `schema?` | the new target, as in `targets.list`; writes `config/<name>/target.json` (and a `rest` target's schema), never overwriting, and removes what it wrote if it doesn't load |
| `targets.snapshot` | `target` | `{written, before, dir}`: the live state written into Git |
| `rest.inferSchema` | `baseUrl`, `path` | `{schema}`: a first JSON Schema from what the collection returns now |
| `mock.status`, `mock.start`, `mock.stop` | `target` | `{configured, seed, url, running, startedHere, via}`; only for targets with `mock`. Start waits until the mock serves; stop only stops what this server started; everything it started stops when it exits |

During `plan.apply`, the server sends `apply.progress` notifications:

```json
{"jsonrpc": "2.0", "method": "apply.progress",
 "params": {"requestId": 7, "target": "dev", "key": "Bret", "step": "delete-to-replace", "state": "finished"}}
```

`step` is `delete`, `delete-to-replace`, `update`, `create` or `create-replacement`, in the order they run;
`state` is `starting`, `finished` or `failed` (with `error`).

## Lint

`lint` checks what spans files. Today: every check that a branch protection rule requires must be reported
by a job of a workflow that runs on pull requests. A job's check name is its `name` (expressions such as
`${{ matrix.os }}` match anything) or, without one, its id (with ` (…)` for a matrix job). Findings are
warnings, since a required check can also come from a GitHub App or an outside CI; `validate` prints them
too. `requirements` maps each required check to the jobs that report it, and `checks` lists every job, which
is what the desktop app draws as a graph.
