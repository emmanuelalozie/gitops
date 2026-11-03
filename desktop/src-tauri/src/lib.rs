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
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use tauri::{AppHandle, Emitter, Manager, State};
use tauri_plugin_notification::NotificationExt;
use tauri_plugin_opener::OpenerExt;

/// An open repository: its engine and the watcher on its files.
struct Session {
    engine: Arc<Engine>,
    engine_info: Value,
    /// The token generation the engine started with; a later one means it needs a restart to see new tokens.
    generation: u64,
    _watcher: Option<notify::RecommendedWatcher>,
}

/// Several repositories can be open at once (the workspace); commands act on the active one.
pub struct AppState {
    sessions: Mutex<HashMap<PathBuf, Session>>,
    active: Mutex<Option<PathBuf>>,
    /// Bumped whenever a token is set or removed.
    token_generation: AtomicU64,
    github: github::GitHub,
    settings: Mutex<Settings>,
    settings_file: PathBuf,
    /// Whether the "still watching in the tray" notice was shown this run.
    told_about_tray: AtomicBool,
}

impl AppState {
    fn engine(&self) -> Result<Arc<Engine>> {
        let repo = self.repo()?;
        self.sessions.lock().unwrap().get(&repo).map(|s| s.engine.clone()).ok_or_else(|| AppError::repo("No repository is open"))
    }

    fn repo(&self) -> Result<PathBuf> {
        self.active.lock().unwrap().clone().ok_or_else(|| AppError::repo("No repository is open"))
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
    let repo_name = repo.display().to_string();
    let engine = Arc::new(Engine::start(&location, repo, &env, move |method, params| {
        let _ = handle.emit("engine-notification", json!({"repo": repo_name, "method": method, "params": params}));
    })?);
    let info = engine.handshake().await?;
    Ok((engine, info))
}

/// Stops a session's engine off the async runtime, since stopping can wait a little.
fn drop_later(session: Session) {
    std::thread::spawn(move || drop(session));
}

fn repo_info(repo: &Path, engine_info: Value) -> Result<RepoInfo> {
    Ok(RepoInfo {
        name: repo.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default(),
        path: repo.to_path_buf(),
        git: git::status(repo)?,
        engine: engine_info,
    })
}

/// Starts the engine and file watcher of a repository. Events say which repository they're about.
async fn start_session(app: &AppHandle, state: &AppState, repo: &Path) -> Result<Session> {
    let generation = state.token_generation.load(Ordering::SeqCst);
    let (engine, engine_info) = start_engine(app, state, repo).await?;
    let handle = app.clone();
    let repo_name = repo.display().to_string();
    let watcher = watcher::watch(repo, move |paths| {
        let _ = handle.emit("repo-changed", json!({"repo": repo_name, "paths": paths}));
    })
    .ok();
    Ok(Session { engine, engine_info, generation, _watcher: watcher })
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
    git::status(&repo)?;
    let open = state.sessions.lock().unwrap().contains_key(&repo);
    if !open {
        let session = start_session(&app, &state, &repo).await?;
        if let Some(old) = state.sessions.lock().unwrap().insert(repo.clone(), session) {
            drop_later(old);
        }
    }
    *state.active.lock().unwrap() = Some(repo.clone());
    state.settings.lock().unwrap().remember_repo(&repo);
    state.save_settings()?;
    activate(&app, &state, &repo).await
}

/// Makes an open repository the active one, restarting its engine first if tokens changed while it was in the
/// background.
async fn activate(app: &AppHandle, state: &AppState, repo: &Path) -> Result<RepoInfo> {
    let stale = state
        .sessions
        .lock()
        .unwrap()
        .get(repo)
        .map(|s| s.generation != state.token_generation.load(Ordering::SeqCst))
        .ok_or_else(|| AppError::repo(format!("{} isn't open", repo.display())))?;
    if stale {
        let fresh = start_session(app, state, repo).await?;
        if let Some(old) = state.sessions.lock().unwrap().insert(repo.to_path_buf(), fresh) {
            drop_later(old);
        }
    }
    *state.active.lock().unwrap() = Some(repo.to_path_buf());
    let info = state.sessions.lock().unwrap().get(repo).map(|s| s.engine_info.clone()).unwrap_or(Value::Null);
    repo_info(repo, info)
}

#[tauri::command]
async fn switch_repo(app: AppHandle, state: State<'_, AppState>, path: String) -> Result<RepoInfo> {
    activate(&app, &state, Path::new(&path)).await
}

/// Closes a repository (the active one when no path is given) and returns the one that's active now, if any.
#[tauri::command]
async fn close_repo(app: AppHandle, state: State<'_, AppState>, path: Option<String>) -> Result<Option<RepoInfo>> {
    let repo = match path {
        Some(p) => PathBuf::from(p),
        None => match state.repo() {
            Ok(r) => r,
            Err(_) => return Ok(None),
        },
    };
    if let Some(old) = state.sessions.lock().unwrap().remove(&repo) {
        drop_later(old);
    }
    let next = {
        let mut active = state.active.lock().unwrap();
        if active.as_ref() == Some(&repo) {
            *active = state.sessions.lock().unwrap().keys().next().cloned();
        }
        active.clone()
    };
    match next {
        Some(next) => Ok(Some(activate(&app, &state, &next).await?)),
        None => Ok(None),
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct WorkspaceEntry {
    path: PathBuf,
    name: String,
    active: bool,
    git: Option<git::RepoStatus>,
    engine_alive: bool,
}

/// Every open repository.
#[tauri::command]
fn workspace(state: State<'_, AppState>) -> Vec<WorkspaceEntry> {
    let active = state.active.lock().unwrap().clone();
    let sessions = state.sessions.lock().unwrap();
    let mut list: Vec<WorkspaceEntry> = sessions
        .iter()
        .map(|(path, session)| WorkspaceEntry {
            name: path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default(),
            active: active.as_ref() == Some(path),
            git: git::status(path).ok(),
            engine_alive: session.engine.is_alive(),
            path: path.clone(),
        })
        .collect();
    list.sort_by(|a, b| a.name.cmp(&b.name));
    list
}

/// Read-only engine calls on any open repository, for the workspace overview.
#[tauri::command]
async fn workspace_call(state: State<'_, AppState>, path: String, method: String, params: Option<Value>) -> Result<Value> {
    if !["targets.list", "plan.compute", "config.validate"].contains(&method.as_str()) {
        return Err(AppError::new("engine", "not_allowed", format!("The workspace view doesn't call '{method}'")));
    }
    let engine = state
        .sessions
        .lock()
        .unwrap()
        .get(Path::new(&path))
        .map(|s| s.engine.clone())
        .ok_or_else(|| AppError::repo(format!("{path} isn't open")))?;
    engine.call(&method, params.unwrap_or_else(|| json!({}))).await
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
    let generation = state.token_generation.load(Ordering::SeqCst);
    let (engine, info) = start_engine(&app, &state, &repo).await?;
    let mut sessions = state.sessions.lock().unwrap();
    if let Some(session) = sessions.get_mut(&repo) {
        let old = std::mem::replace(&mut session.engine, engine);
        session.engine_info = info.clone();
        session.generation = generation;
        std::thread::spawn(move || drop(old));
    }
    Ok(info)
}

#[tauri::command]
fn engine_info(state: State<'_, AppState>) -> Result<Value> {
    let repo = state.repo()?;
    let sessions = state.sessions.lock().unwrap();
    let session = sessions.get(&repo).ok_or_else(|| AppError::repo("No repository is open"))?;
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
    state.token_generation.fetch_add(1, Ordering::SeqCst);
    state.settings.lock().unwrap().remember_token(&name, true);
    state.save_settings()
}

#[tauri::command]
fn clear_token(state: State<'_, AppState>, name: String) -> Result<()> {
    secrets::delete(&name)?;
    state.token_generation.fetch_add(1, Ordering::SeqCst);
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
    open_pull(&state, status, proposed, &proposal.title, &proposal.body, &proposal.labels).await
}

/// After a branch is pushed: opens its pull request when there's a token, otherwise hands back GitHub's form.
async fn open_pull(
    state: &AppState,
    status: git::RepoStatus,
    proposed: git::Proposed,
    title: &str,
    body: &str,
    labels: &[String],
) -> Result<ProposeResult> {
    let base = status.default_branch.clone().unwrap_or_else(|| proposed.from_branch.clone());
    let mut result = ProposeResult { proposed, base: base.clone(), pull_request: None, compare_url: None, pull_request_error: None };
    let Some(github) = status.github else { return Ok(result) };
    if !result.proposed.pushed {
        return Ok(result);
    }
    match state.github_token() {
        Some(token) => match state.github.create_pull(&github, &token, &result.proposed.branch, &base, title, body).await {
            Ok(pull) => {
                if !labels.is_empty() {
                    if let Some(number) = pull["number"].as_u64() {
                        // Best effort: a missing label shouldn't hide the pull request that was opened.
                        let _ = state.github.add_labels(&github, &token, number, labels).await;
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

/// Undoes a past config change through a pull request: a branch with the revert, pushed, and a PR into the
/// default branch.
#[tauri::command]
async fn revert_change(state: State<'_, AppState>, sha: String, subject: String) -> Result<ProposeResult> {
    let repo = state.repo()?;
    let status = git::status(&repo)?;
    let short: String = sha.chars().take(7).collect();
    let branch = format!("gitops/revert-{short}");
    let worker = (repo.clone(), sha.clone(), branch.clone());
    let proposed = tauri::async_runtime::spawn_blocking(move || git::revert(&worker.0, &worker.1, &worker.2))
        .await
        .map_err(|e| AppError::internal(e.to_string()))??;
    let title = format!("Revert \"{subject}\"");
    let body = format!("Undoes {sha} through the GitOps app. The Plan workflow comments with what merging this changes.");
    open_pull(&state, status, proposed, &title, &body, &["config-change".to_string()]).await
}

#[tauri::command]
fn blame_field(state: State<'_, AppState>, path: String, field: String) -> Result<git::Blame> {
    git::blame_field(&state.repo()?, &path, &field)
}

#[tauri::command]
fn audit_log(state: State<'_, AppState>, limit: Option<usize>) -> Result<Vec<git::AuditEntry>> {
    git::audit(&state.repo()?, limit.unwrap_or(50))
}

/// Recent runs of one workflow with their jobs (Apply runs, for the audit view).
#[tauri::command]
async fn github_workflow_runs(state: State<'_, AppState>, workflow: String, limit: Option<usize>) -> Result<Value> {
    let repo = github_repo(&state)?;
    state.github.workflow_runs_with_jobs(&repo, state.github_token().as_deref(), &workflow, limit.unwrap_or(30)).await
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

/// The tray (menu bar) icon: open the window again, or quit (which also stops every engine and mock).
fn tray(app: &tauri::App) -> tauri::Result<()> {
    use tauri::menu::{Menu, MenuItem};
    use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
    let show = MenuItem::with_id(app, "show", "Open GitOps", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "Quit GitOps", true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&show, &quit])?;
    let mut builder = TrayIconBuilder::with_id("main").tooltip("GitOps").menu(&menu).show_menu_on_left_click(false);
    if let Some(icon) = app.default_window_icon() {
        builder = builder.icon(icon.clone());
    }
    builder
        .on_menu_event(|app, event| match event.id.as_ref() {
            "show" => show_window(app),
            "quit" => app.exit(0),
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, .. } = event {
                show_window(tray.app_handle());
            }
        })
        .build(app)?;
    Ok(())
}

fn show_window(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
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
                sessions: Mutex::new(HashMap::new()),
                active: Mutex::new(None),
                token_generation: AtomicU64::new(0),
                told_about_tray: AtomicBool::new(false),
                github: github::GitHub::new(&api)?,
                settings: Mutex::new(Settings::load(&settings_file)),
                settings_file,
            });
            tray(app)?;
            Ok(())
        })
        .on_window_event(|window, event| {
            // While watching for drift, closing the window keeps the app (and the watch) running in the tray.
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                let app = window.app_handle();
                let state = app.state::<AppState>();
                let watching = state.settings.lock().unwrap().watch.enabled;
                if watching {
                    api.prevent_close();
                    let _ = window.hide();
                    if !state.told_about_tray.swap(true, Ordering::SeqCst) {
                        let _ = app
                            .notification()
                            .builder()
                            .title("GitOps is still watching for drift")
                            .body("It's in the tray (menu bar). Choose Quit there to stop it.")
                            .show();
                    }
                }
            }
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
            revert_change,
            blame_field,
            audit_log,
            github_workflow_runs,
            switch_repo,
            workspace,
            workspace_call,
        ])
        .build(tauri::generate_context!())
        .expect("error while building the GitOps app")
        .run(|app, event| {
            if let tauri::RunEvent::Exit = event {
                // Let the engine finish anything running and exit before the app does.
                if let Some(state) = app.try_state::<AppState>() {
                    state.sessions.lock().unwrap().clear();
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
