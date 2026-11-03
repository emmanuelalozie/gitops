//! GitOps desktop: a window onto the GitOps engine.
//!
//! The split of work (desktop/README.md): the Java engine owns everything about desired vs live state; this Rust
//! layer runs the engine, keeps tokens in the keychain, watches the repository and runs git; the frontend only
//! presents. Every command here is listed in build.rs and granted in capabilities/default.json.

mod engine;
mod error;
mod git;
mod github;
mod repo_check;
mod secrets;
mod settings;
mod watcher;

use engine::Engine;
use error::{AppError, Result};
use serde::Serialize;
use serde_json::{json, Value};
use settings::Settings;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use tauri::{AppHandle, Emitter, Manager, State};
use tauri_plugin_notification::NotificationExt;
use tauri_plugin_opener::OpenerExt;

/// An open repository: its engine and the watcher on its files.
struct Session {
    repo: PathBuf,
    engine: Arc<Engine>,
    engine_info: Value,
    _watcher: Option<notify::RecommendedWatcher>,
}

pub struct AppState {
    session: Mutex<Option<Session>>,
    github: github::GitHub,
    settings: Mutex<Settings>,
    settings_file: PathBuf,
}

impl AppState {
    fn engine(&self) -> Result<Arc<Engine>> {
        self.session
            .lock()
            .unwrap()
            .as_ref()
            .map(|s| s.engine.clone())
            .ok_or_else(|| AppError::repo("No repository is open"))
    }

    fn repo(&self) -> Result<PathBuf> {
        self.session.lock().unwrap().as_ref().map(|s| s.repo.clone()).ok_or_else(|| AppError::repo("No repository is open"))
    }

    fn github_token(&self) -> Option<String> {
        secrets::get("GITHUB_TOKEN").ok().flatten()
    }

    fn save_settings(&self) -> Result<()> {
        self.settings.lock().unwrap().save(&self.settings_file)
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct RepoInfo {
    path: PathBuf,
    name: String,
    git: git::RepoStatus,
    engine: Value,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct AppInfo {
    version: String,
    recent_repos: Vec<PathBuf>,
    open_repo: Option<PathBuf>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct TokenStatus {
    name: String,
    present: bool,
}

/// Tokens from the keychain, as environment variables for the engine. Ones the keychain can't read are skipped
/// (the engine then reports them as missing, which the UI already explains).
fn token_env(settings: &Settings) -> HashMap<String, String> {
    settings
        .token_names
        .iter()
        .filter_map(|name| secrets::get(name).ok().flatten().map(|value| (name.clone(), value)))
        .collect()
}

/// Starts an engine for the repository and checks it speaks our schema version.
async fn start_engine(app: &AppHandle, state: &AppState, repo: &Path) -> Result<(Arc<Engine>, Value)> {
    let location = engine::locate(
        std::env::var_os("GITOPS_ENGINE_DIR").map(PathBuf::from),
        app.path().resource_dir().ok().map(|d| d.join("engine")),
        repo,
        std::env::var_os("JAVA_HOME").map(PathBuf::from),
    )?;
    let env = token_env(&state.settings.lock().unwrap());
    let handle = app.clone();
    let engine = Arc::new(Engine::start(&location, repo, &env, move |method, params| {
        let _ = handle.emit("engine-notification", json!({"method": method, "params": params}));
    })?);
    let info = engine.handshake().await?;
    Ok((engine, info))
}

/// Replaces the session; the old engine is stopped off the async runtime, since stopping can wait a little.
fn replace_session(state: &AppState, next: Option<Session>) {
    let previous = std::mem::replace(&mut *state.session.lock().unwrap(), next);
    if let Some(previous) = previous {
        std::thread::spawn(move || drop(previous));
    }
}

#[tauri::command]
fn app_info(state: State<'_, AppState>) -> AppInfo {
    AppInfo {
        version: env!("CARGO_PKG_VERSION").to_string(),
        recent_repos: state.settings.lock().unwrap().recent_repos.clone(),
        open_repo: state.repo().ok(),
    }
}

#[tauri::command]
async fn open_repo(app: AppHandle, state: State<'_, AppState>, path: String) -> Result<RepoInfo> {
    // Plain C:\... paths on Windows (Java and some git builds refuse \\?\C:\...).
    let repo = repo_check::resolve(&path)?;
    repo_check::check(&repo)?;
    let git = git::status(&repo)?;
    let (engine, engine_info) = start_engine(&app, &state, &repo).await?;
    let handle = app.clone();
    let watcher = watcher::watch(&repo, move |paths| {
        let _ = handle.emit("repo-changed", json!({"paths": paths}));
    })
    .ok();
    replace_session(&state, Some(Session { repo: repo.clone(), engine, engine_info: engine_info.clone(), _watcher: watcher }));
    state.settings.lock().unwrap().remember_repo(&repo);
    state.save_settings()?;
    Ok(RepoInfo {
        name: repo.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default(),
        path: repo,
        git,
        engine: engine_info,
    })
}

#[tauri::command]
fn close_repo(state: State<'_, AppState>) {
    replace_session(&state, None);
}

#[tauri::command]
fn repo_status(state: State<'_, AppState>) -> Result<git::RepoStatus> {
    git::status(&state.repo()?)
}

/// Calls the engine. Only methods in engine::ALLOWED_METHODS get through.
#[tauri::command]
async fn engine_call(state: State<'_, AppState>, method: String, params: Option<Value>) -> Result<Value> {
    if !engine::ALLOWED_METHODS.contains(&method.as_str()) {
        return Err(AppError::new("engine", "not_allowed", format!("The app doesn't call the engine method '{method}'")));
    }
    let engine = state.engine()?;
    let params = params.unwrap_or_else(|| json!({}));
    if method == "plan.apply" {
        check_local_apply(&engine, &params).await?;
    }
    engine.call(&method, params).await
}

/// A second check behind the engine's: the target must say "localApply": true and the call must carry a plan id.
async fn check_local_apply(engine: &Engine, params: &Value) -> Result<()> {
    let target = params.get("target").and_then(Value::as_str).unwrap_or_default();
    if params.get("planId").and_then(Value::as_str).is_none_or(str::is_empty) {
        return Err(AppError::new("engine", "not_allowed", "Applying needs the id of the plan you approved"));
    }
    let targets = engine.call("targets.list", json!({})).await?;
    let local = targets["targets"].as_array().into_iter().flatten().any(|t| t["name"] == target && t["localApply"] == true);
    if !local {
        return Err(AppError::new(
            "engine",
            "not_allowed",
            format!("'{target}' changes through pull requests, not from this app (it doesn't set \"localApply\": true)"),
        ));
    }
    Ok(())
}

/// Restarts the engine of the open repository (after its tokens change, or after it stopped).
#[tauri::command]
async fn restart_engine(app: AppHandle, state: State<'_, AppState>) -> Result<Value> {
    let repo = state.repo()?;
    let (engine, info) = start_engine(&app, &state, &repo).await?;
    let mut session = state.session.lock().unwrap();
    if let Some(session) = session.as_mut() {
        let old = std::mem::replace(&mut session.engine, engine);
        session.engine_info = info.clone();
        std::thread::spawn(move || drop(old));
    }
    Ok(info)
}

#[tauri::command]
fn engine_info(state: State<'_, AppState>) -> Result<Value> {
    let session = state.session.lock().unwrap();
    let session = session.as_ref().ok_or_else(|| AppError::repo("No repository is open"))?;
    let mut info = session.engine_info.clone();
    info["alive"] = json!(session.engine.is_alive());
    Ok(info)
}

/// Which of these token names have a value in the keychain. Never returns the values.
#[tauri::command]
fn token_status(state: State<'_, AppState>, names: Vec<String>) -> Result<Vec<TokenStatus>> {
    let stored = state.settings.lock().unwrap().token_names.clone();
    names
        .into_iter()
        .map(|name| {
            if !secrets::valid_name(&name) {
                return Err(AppError::new("keychain", "invalid_name", format!("'{name}' isn't a valid token name")));
            }
            let present = stored.contains(&name) && secrets::get(&name).map(|v| v.is_some()).unwrap_or(false);
            Ok(TokenStatus { name, present })
        })
        .collect()
}

#[tauri::command]
fn set_token(state: State<'_, AppState>, name: String, value: String) -> Result<()> {
    secrets::set(&name, &value)?;
    state.settings.lock().unwrap().remember_token(&name, true);
    state.save_settings()
}

#[tauri::command]
fn clear_token(state: State<'_, AppState>, name: String) -> Result<()> {
    secrets::delete(&name)?;
    state.settings.lock().unwrap().remember_token(&name, false);
    state.save_settings()
}

#[tauri::command]
fn config_changes(state: State<'_, AppState>) -> Result<Vec<git::ConfigChange>> {
    git::config_changes(&state.repo()?)
}

#[tauri::command]
fn working_diff(state: State<'_, AppState>, path: String) -> Result<String> {
    git::working_diff(&state.repo()?, &path)
}

#[tauri::command]
fn discard_change(state: State<'_, AppState>, path: String) -> Result<()> {
    git::discard(&state.repo()?, &path)
}

#[tauri::command]
fn file_history(state: State<'_, AppState>, path: String, limit: Option<usize>) -> Result<Vec<git::Commit>> {
    git::history(&state.repo()?, &path, limit.unwrap_or(100))
}

#[tauri::command]
fn commit_diff(state: State<'_, AppState>, sha: String, path: String) -> Result<String> {
    git::commit_diff(&state.repo()?, &sha, &path)
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ProposeResult {
    proposed: git::Proposed,
    base: String,
    /// The pull request the app opened, when it had a token to do it.
    pull_request: Option<Value>,
    /// Otherwise (or if opening it failed): GitHub's form for opening it by hand.
    compare_url: Option<String>,
    pull_request_error: Option<AppError>,
}

/// Commits the chosen config files to a new branch, pushes it and opens a pull request into the default branch.
#[tauri::command]
async fn propose(state: State<'_, AppState>, proposal: git::Proposal) -> Result<ProposeResult> {
    let repo = state.repo()?;
    let status = git::status(&repo)?;
    let worker_repo = repo.clone();
    let worker_proposal = proposal.clone();
    let proposed = tauri::async_runtime::spawn_blocking(move || git::propose(&worker_repo, &worker_proposal))
        .await
        .map_err(|e| AppError::internal(e.to_string()))??;
    let base = status.default_branch.clone().unwrap_or_else(|| proposed.from_branch.clone());
    let mut result = ProposeResult { proposed, base: base.clone(), pull_request: None, compare_url: None, pull_request_error: None };
    let Some(github) = status.github else { return Ok(result) };
    if !result.proposed.pushed {
        return Ok(result);
    }
    match state.github_token() {
        Some(token) => match state.github.create_pull(&github, &token, &result.proposed.branch, &base, &proposal.title, &proposal.body).await {
            Ok(pull) => {
                if !proposal.labels.is_empty() {
                    if let Some(number) = pull["number"].as_u64() {
                        // Best effort: a missing label shouldn't hide the pull request that was opened.
                        let _ = state.github.add_labels(&github, &token, number, &proposal.labels).await;
                    }
                }
                result.pull_request = Some(pull)
            }
            Err(e) => {
                result.pull_request_error = Some(e);
                result.compare_url = Some(github::compare_url(&github, &base, &result.proposed.branch));
            }
        },
        None => result.compare_url = Some(github::compare_url(&github, &base, &result.proposed.branch)),
    }
    Ok(result)
}

/// The GitHub repository of the open repository (its origin remote).
fn github_repo(state: &AppState) -> Result<git::GitHubRepo> {
    git::status(&state.repo()?)?.github.ok_or_else(|| {
        AppError::new("github", "no_github_remote", "This repository has no GitHub remote named origin, so there's nothing to show from GitHub")
    })
}

#[tauri::command]
async fn github_pulls(state: State<'_, AppState>) -> Result<Value> {
    let repo = github_repo(&state)?;
    state.github.pull_requests(&repo, state.github_token().as_deref()).await
}

#[tauri::command]
async fn github_pull_details(state: State<'_, AppState>, number: u64, sha: String) -> Result<Value> {
    let repo = github_repo(&state)?;
    state.github.pull_details(&repo, state.github_token().as_deref(), number, &sha).await
}

/// Drift issues and the recent runs of the drift workflow, each with one job per target.
#[tauri::command]
async fn github_drift(state: State<'_, AppState>, workflow: Option<String>, limit: Option<usize>) -> Result<Value> {
    let repo = github_repo(&state)?;
    let token = state.github_token();
    let workflow = workflow.unwrap_or_else(|| "drift.yml".into());
    let (issues, runs) = tokio::join!(
        state.github.drift_issues(&repo, token.as_deref()),
        state.github.workflow_runs_with_jobs(&repo, token.as_deref(), &workflow, limit.unwrap_or(21)),
    );
    Ok(json!({"issues": issues?, "runs": runs?}))
}

#[tauri::command]
async fn github_runs(state: State<'_, AppState>) -> Result<Value> {
    let repo = github_repo(&state)?;
    let workflows = github::dispatchable_workflows(&state.repo()?);
    let runs = state.github.recent_runs(&repo, state.github_token().as_deref()).await?;
    Ok(json!({"runs": runs, "dispatchable": workflows, "tokenPresent": state.github_token().is_some()}))
}

/// Starts a workflow by hand. Only files in .github/workflows with a workflow_dispatch trigger qualify.
#[tauri::command]
async fn github_dispatch(state: State<'_, AppState>, workflow: String, git_ref: String) -> Result<()> {
    let repo_dir = state.repo()?;
    if !github::dispatchable_workflows(&repo_dir).iter().any(|w| w["file"] == workflow.as_str()) {
        return Err(AppError::new("github", "not_dispatchable", format!("{workflow} has no workflow_dispatch trigger in this repository")));
    }
    if git_ref.is_empty() || git_ref.starts_with('-') || git_ref.chars().any(char::is_whitespace) {
        return Err(AppError::new("github", "invalid_ref", "Not a branch name"));
    }
    let token = state
        .github_token()
        .ok_or_else(|| AppError::new("github", "token_missing", "Starting a workflow needs GITHUB_TOKEN (with Actions: write) in Settings"))?;
    state.github.dispatch(&github_repo(&state)?, &token, &workflow, &git_ref).await
}

#[tauri::command]
fn watch_settings(state: State<'_, AppState>) -> settings::Watch {
    state.settings.lock().unwrap().watch.clone()
}

#[tauri::command]
fn set_watch_settings(state: State<'_, AppState>, enabled: bool, minutes: u32) -> Result<settings::Watch> {
    let watch = settings::Watch::clamped(enabled, minutes);
    state.settings.lock().unwrap().watch = watch.clone();
    state.save_settings()?;
    Ok(watch)
}

/// A desktop notification (drift found by the watch). Plain text, kept short; the page itself has no
/// notification permission.
#[tauri::command]
fn notify(app: AppHandle, title: String, body: String) -> Result<()> {
    let clip = |s: String, n: usize| if s.chars().count() > n { s.chars().take(n - 1).collect::<String>() + "…" } else { s };
    app.notification()
        .builder()
        .title(clip(title, 80))
        .body(clip(body, 300))
        .show()
        .map_err(|e| AppError::internal(format!("Couldn't show a notification: {e}")))
}

/// Opens a GitHub page in the browser. Nothing else: the webview can't open arbitrary URLs.
#[tauri::command]
fn open_url(app: AppHandle, url: String) -> Result<()> {
    if !url.starts_with("https://github.com/") || url.chars().any(char::is_whitespace) {
        return Err(AppError::new("internal", "not_allowed", "Only github.com links can be opened"));
    }
    app.opener().open_url(url, None::<&str>).map_err(|e| AppError::internal(e.to_string()))
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_notification::init())
        .setup(|app| {
            let settings_file = app.path().app_config_dir()?.join("settings.json");
            let api = std::env::var("GITHUB_API_URL").unwrap_or_else(|_| github::API.to_string());
            app.manage(AppState {
                session: Mutex::new(None),
                github: github::GitHub::new(&api)?,
                settings: Mutex::new(Settings::load(&settings_file)),
                settings_file,
            });
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            app_info,
            open_repo,
            close_repo,
            repo_status,
            engine_call,
            restart_engine,
            engine_info,
            token_status,
            set_token,
            clear_token,
            config_changes,
            working_diff,
            discard_change,
            file_history,
            commit_diff,
            propose,
            open_url,
            github_pulls,
            github_pull_details,
            github_drift,
            github_runs,
            github_dispatch,
            watch_settings,
            set_watch_settings,
            notify,
        ])
        .build(tauri::generate_context!())
        .expect("error while building the GitOps app")
        .run(|app, event| {
            if let tauri::RunEvent::Exit = event {
                // Let the engine finish anything running and exit before the app does.
                if let Some(state) = app.try_state::<AppState>() {
                    state.session.lock().unwrap().take();
                }
            }
        });
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Against this repository's own config: dev sets localApply, prod doesn't. Skipped without a built engine.
    #[tokio::test]
    async fn only_local_apply_targets_with_a_plan_id_get_through() {
        let repo = dunce::canonicalize(Path::new(env!("CARGO_MANIFEST_DIR")).join("../..")).unwrap();
        let Ok(location) = engine::locate(None, None, &repo, std::env::var_os("JAVA_HOME").map(PathBuf::from)) else {
            eprintln!("skipped: run ./gradlew engineBundle first");
            return;
        };
        let engine = Engine::start(&location, &repo, &HashMap::new(), |_, _| {}).unwrap();

        let prod = check_local_apply(&engine, &json!({"target": "prod", "planId": "abc"})).await.unwrap_err();
        assert_eq!(prod.code, "not_allowed");
        assert!(prod.message.contains("pull requests"));
        let no_id = check_local_apply(&engine, &json!({"target": "dev"})).await.unwrap_err();
        assert!(no_id.message.contains("plan you approved"));
        check_local_apply(&engine, &json!({"target": "dev", "planId": "abc"})).await.unwrap();
    }
}
