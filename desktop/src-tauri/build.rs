// Every command the frontend can invoke is listed here; each must also be granted in capabilities/default.json.
const COMMANDS: &[&str] = &[
    "app_info",
    "open_repo",
    "close_repo",
    "repo_status",
    "engine_call",
    "restart_engine",
    "engine_info",
    "token_status",
    "set_token",
    "clear_token",
    "config_changes",
    "working_diff",
    "discard_change",
    "file_history",
    "commit_diff",
    "propose",
    "open_url",
    "github_pulls",
    "github_pull_details",
    "github_drift",
    "github_runs",
    "github_dispatch",
    "watch_settings",
    "set_watch_settings",
    "notify",
];

fn main() {
    tauri_build::try_build(
        tauri_build::Attributes::new().app_manifest(tauri_build::AppManifest::new().commands(COMMANDS)),
    )
    .expect("failed to run tauri-build");
}
