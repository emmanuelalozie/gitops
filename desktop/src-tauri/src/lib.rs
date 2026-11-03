//! GitOps desktop: a window onto the GitOps engine.
//!
//! The split of work (docs/desktop.md): the Java engine owns everything about desired vs live state; this Rust
//! layer runs the engine, keeps tokens in the keychain, watches the repository and runs git; the frontend only
//! presents. Every command here is listed in build.rs and granted in capabilities/default.json.

mod engine;
mod error;
mod git;
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

/// An open repository: its engine and the watcher on its files.
struct Session {
    repo: PathBuf,
    engine: Arc<Engine>,
    engine_info: Value,
    _watcher: Option<notify::RecommendedWatcher>,
}

pub struct AppState {
    session: Mutex<Option<Session>>,
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
    let repo = PathBuf::from(&path)
        .canonicalize()
        .map_err(|e| AppError::repo(format!("Can't open {path}: {e}")))?;
    if !repo.join("config").is_dir() {
        return Err(AppError::repo(format!(
            "{} has no config/ folder, so it isn't a GitOps repository (one target per config/<name>/target.json).",
            repo.display()
        )));
    }
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
    engine.call(&method, params.unwrap_or_else(|| json!({}))).await
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

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .setup(|app| {
            let settings_file = app.path().app_config_dir()?.join("settings.json");
            app.manage(AppState {
                session: Mutex::new(None),
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
