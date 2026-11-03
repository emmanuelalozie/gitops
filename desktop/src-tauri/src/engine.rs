//! Runs the Java engine (`gitops serve`) as a child process and talks JSON-RPC to it over stdin/stdout.
//!
//! The engine is the only thing that compares Git with live systems; this module only moves messages.
//! One reader thread routes responses to the waiting call by id and hands notifications (apply progress)
//! to a callback. If the engine dies, every waiting call fails with its last lines of stderr, and the next
//! call reports that it has stopped, so the UI can offer to restart it.

use crate::error::{AppError, Result};
use serde_json::{json, Value};
use std::collections::{HashMap, VecDeque};
use std::io::{BufRead, BufReader, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::sync::oneshot;

/// The JSON schema version this app understands (docs/engine-protocol.md).
pub const SCHEMA_VERSION: u64 = 1;

/// Methods the frontend may call. Anything else is refused before it reaches the engine.
pub const ALLOWED_METHODS: &[&str] = &[
    "engine.version",
    "targets.list",
    "config.validate",
    "plan.compute",
    "live.fetch",
    "targets.compare",
    "kind.schema",
    "resources.list",
    "resource.check",
    // Writes go through the engine so files keep the canonical format and invalid ones are refused.
    "resource.write",
    "resource.delete",
    "repo.lint",
    // Adding a target (writes config/<name>/ in the working tree), importing live state into Git, and a first
    // schema for a REST API.
    "targets.create",
    "resources.search",
    "health.check",
    "targets.snapshot",
    "rest.inferSchema",
    // A target's local mock API ("mock" in target.json), run by the engine and stopped when it exits.
    "mock.status",
    "mock.start",
    "mock.stop",
    // Only for targets with "localApply": true, and only with the id of the plan the person approved.
    // The engine enforces both; engine_call checks localApply too, before the request leaves the app.
    "plan.apply",
];

const STDERR_LINES: usize = 40;

/// Where the engine is: a Java runtime and the folder of jars.
#[derive(Debug, Clone, PartialEq)]
pub struct Location {
    pub java: PathBuf,
    pub lib: PathBuf,
    /// Every jar in `lib`, passed to Java one by one rather than as `lib/*`.
    pub jars: Vec<PathBuf>,
}

impl Location {
    /// For error messages: which Java ran which engine.
    pub fn describe(&self) -> String {
        format!("{} with the jars in {}", self.java.display(), self.lib.display())
    }
}

/// Finds the engine, in order: `GITOPS_ENGINE_DIR`, the copy bundled with the app, then `build/engine` in the
/// opened repository (after `./gradlew engineBundle`, which is what `npm run tauri dev` runs first).
/// A folder counts when it has `lib/`; its `runtime/bin/java` is used when present, otherwise `JAVA_HOME`
/// or `java` on the PATH.
pub fn locate(env_dir: Option<PathBuf>, bundled: Option<PathBuf>, repo: &Path, java_home: Option<PathBuf>) -> Result<Location> {
    let candidates: Vec<PathBuf> = [env_dir, bundled, Some(repo.join("build").join("engine"))]
        .into_iter()
        .flatten()
        // On Windows a path can arrive as \\?\C:\..., which Java can't use on a classpath. Back to C:\...
        .map(|p| dunce::simplified(&p).to_path_buf())
        .collect();
    let mut skipped = Vec::new();
    for dir in &candidates {
        let lib = dir.join("lib");
        let jars = jars(&lib);
        // A folder without the engine's own jar is stale or half-copied: skip it rather than start a Java
        // that fails with "Could not find or load main class gitops.Main".
        if !jars.iter().any(|j| j.file_name().is_some_and(|n| n.to_string_lossy().starts_with("gitops-"))) {
            if lib.is_dir() {
                skipped.push(format!("{} (no gitops-*.jar in lib)", dir.display()));
            } else {
                skipped.push(format!("{} (missing)", dir.display()));
            }
            continue;
        }
        let exe = if cfg!(windows) { "java.exe" } else { "java" };
        let bundled_java = dir.join("runtime").join("bin").join(exe);
        let java = if bundled_java.is_file() {
            bundled_java
        } else if let Some(home) = java_home.as_ref().map(|h| h.join("bin").join(exe)).filter(|p| p.is_file()) {
            dunce::simplified(&home).to_path_buf()
        } else {
            PathBuf::from(exe)
        };
        return Ok(Location { java, lib, jars });
    }
    Err(AppError::new(
        "engine",
        "engine_missing",
        format!(
            "Couldn't find the GitOps engine. Looked in: {}. Run `./gradlew engineBundle` in the repository, or set GITOPS_ENGINE_DIR.",
            skipped.join(", ")
        ),
    ))
}

/// The jars in a folder, sorted, so the classpath is the same every time.
fn jars(lib: &Path) -> Vec<PathBuf> {
    let mut jars: Vec<PathBuf> = std::fs::read_dir(lib)
        .map(|entries| {
            entries
                .filter_map(|e| e.ok().map(|e| e.path()))
                .filter(|p| p.is_file() && p.extension().is_some_and(|x| x.eq_ignore_ascii_case("jar")))
                .collect()
        })
        .unwrap_or_default();
    jars.sort();
    jars
}

type Pending = Arc<Mutex<HashMap<u64, oneshot::Sender<std::result::Result<Value, AppError>>>>>;

pub struct Engine {
    stdin: Mutex<Option<ChildStdin>>,
    child: Mutex<Child>,
    pending: Pending,
    next_id: AtomicU64,
    alive: Arc<AtomicBool>,
    stderr: Arc<Mutex<VecDeque<String>>>,
    describe: String,
}

impl Engine {
    /// Starts `java -cp lib/* gitops.Main serve --config-dir <repo>/config` in the repository, with `env`
    /// (the tokens from the keychain) added to the environment.
    pub fn start(
        location: &Location,
        repo: &Path,
        env: &HashMap<String, String>,
        on_notification: impl Fn(&str, Value) + Send + 'static,
    ) -> Result<Engine> {
        // The jars one by one (";" on Windows, ":" elsewhere), so nothing depends on Java expanding "lib/*".
        let classpath = std::env::join_paths(&location.jars)
            .map_err(|e| AppError::new("engine", "engine_start", format!("Can't build the engine's classpath: {e}")))?;
        let describe = location.describe();
        let mut command = Command::new(&location.java);
        command
            .arg("-cp")
            .arg(classpath)
            .arg("gitops.Main")
            .arg("serve")
            .arg("--config-dir")
            .arg(repo.join("config"))
            .current_dir(repo)
            .envs(env)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            const CREATE_NO_WINDOW: u32 = 0x0800_0000;
            command.creation_flags(CREATE_NO_WINDOW);
        }
        let mut child = command.spawn().map_err(|e| {
            AppError::new("engine", "engine_start", format!("Couldn't start the engine ({describe}): {e}"))
        })?;
        let stdin = child.stdin.take().ok_or_else(|| AppError::internal("engine stdin missing"))?;
        let stdout = child.stdout.take().ok_or_else(|| AppError::internal("engine stdout missing"))?;
        let stderr_pipe = child.stderr.take().ok_or_else(|| AppError::internal("engine stderr missing"))?;

        let pending: Pending = Arc::new(Mutex::new(HashMap::new()));
        let alive = Arc::new(AtomicBool::new(true));
        let stderr = Arc::new(Mutex::new(VecDeque::new()));

        {
            let stderr = stderr.clone();
            std::thread::Builder::new().name("engine-stderr".into()).spawn(move || {
                for line in BufReader::new(stderr_pipe).lines().map_while(std::result::Result::ok) {
                    let mut tail = stderr.lock().unwrap();
                    if tail.len() == STDERR_LINES {
                        tail.pop_front();
                    }
                    tail.push_back(line);
                }
            })?;
        }
        {
            let pending = pending.clone();
            let alive = alive.clone();
            let stderr = stderr.clone();
            let describe = describe.clone();
            std::thread::Builder::new().name("engine-stdout".into()).spawn(move || {
                for line in BufReader::new(stdout).lines().map_while(std::result::Result::ok) {
                    route(&line, &pending, &on_notification);
                }
                // stdout closed: the engine exited. Fail everything still waiting.
                alive.store(false, Ordering::SeqCst);
                let tail = stderr.lock().unwrap().iter().cloned().collect::<Vec<_>>().join("\n");
                for (_, waiter) in pending.lock().unwrap().drain() {
                    let _ = waiter.send(Err(stopped(&tail, &describe)));
                }
            })?;
        }

        Ok(Engine {
            stdin: Mutex::new(Some(stdin)),
            child: Mutex::new(child),
            pending,
            next_id: AtomicU64::new(1),
            alive,
            stderr,
            describe,
        })
    }

    pub fn is_alive(&self) -> bool {
        self.alive.load(Ordering::SeqCst)
    }

    /// Sends one request and waits for its answer. Applies may take minutes; everything else gets two.
    pub async fn call(&self, method: &str, params: Value) -> Result<Value> {
        if !self.is_alive() {
            return Err(stopped(&self.stderr_tail(), &self.describe));
        }
        let id = self.next_id.fetch_add(1, Ordering::SeqCst);
        let (sender, receiver) = oneshot::channel();
        self.pending.lock().unwrap().insert(id, sender);
        let line = json!({"jsonrpc": "2.0", "id": id, "method": method, "params": params}).to_string();
        {
            let mut stdin = self.stdin.lock().unwrap();
            let pipe = stdin.as_mut().ok_or_else(|| stopped("", &self.describe))?;
            if let Err(e) = pipe.write_all(line.as_bytes()).and_then(|_| pipe.write_all(b"\n")).and_then(|_| pipe.flush()) {
                self.pending.lock().unwrap().remove(&id);
                return Err(AppError::new("engine", "engine_stopped", format!("Couldn't write to the engine: {e}")));
            }
        }
        // Applies may take long; starting a mock may first download json-server.
        let limit = match method {
            "plan.apply" => Duration::from_secs(30 * 60),
            "mock.start" => Duration::from_secs(5 * 60),
            _ => Duration::from_secs(120),
        };
        match tokio::time::timeout(limit, receiver).await {
            Ok(Ok(result)) => result,
            Ok(Err(_)) => Err(stopped(&self.stderr_tail(), &self.describe)),
            Err(_) => {
                self.pending.lock().unwrap().remove(&id);
                Err(AppError::new("engine", "timeout", format!("The engine didn't answer {method} within {}s", limit.as_secs())))
            }
        }
    }

    /// Starts the engine and checks that it speaks a JSON schema version this app understands.
    pub async fn handshake(&self) -> Result<Value> {
        let version = self.call("engine.version", json!({})).await?;
        let schema = version.get("schemaVersion").and_then(Value::as_u64).unwrap_or(0);
        if schema != SCHEMA_VERSION {
            return Err(AppError::new(
                "engine",
                "schema_mismatch",
                format!("The engine speaks JSON schema v{schema}, but this app needs v{SCHEMA_VERSION}. Update the app or the engine so they match."),
            )
            .with_details(version));
        }
        Ok(version)
    }

    fn stderr_tail(&self) -> String {
        self.stderr.lock().unwrap().iter().cloned().collect::<Vec<_>>().join("\n")
    }

    /// Closes stdin, which tells the engine to finish running requests and exit; kills it if it doesn't.
    pub fn stop(&self) {
        self.stdin.lock().unwrap().take();
        let mut child = self.child.lock().unwrap();
        for _ in 0..50 {
            if let Ok(Some(_)) = child.try_wait() {
                return;
            }
            std::thread::sleep(Duration::from_millis(100));
        }
        let _ = child.kill();
        let _ = child.wait();
    }
}

impl Drop for Engine {
    fn drop(&mut self) {
        self.stop();
    }
}

fn stopped(stderr: &str, describe: &str) -> AppError {
    let mut message = format!("The engine stopped ({describe}).");
    if !stderr.trim().is_empty() {
        message.push_str(" Its last output:\n");
        message.push_str(stderr.trim());
    }
    AppError::new("engine", "engine_stopped", message)
}

/// Hands a line from the engine to the call waiting for it, or to the notification callback.
fn route(line: &str, pending: &Pending, on_notification: &impl Fn(&str, Value)) {
    let Ok(message) = serde_json::from_str::<Value>(line) else {
        return; // the engine never writes anything but JSON to stdout; ignore rather than crash
    };
    if let Some(method) = message.get("method").and_then(Value::as_str) {
        on_notification(method, message.get("params").cloned().unwrap_or(Value::Null));
        return;
    }
    let Some(id) = message.get("id").and_then(Value::as_u64) else {
        return;
    };
    let Some(waiter) = pending.lock().unwrap().remove(&id) else {
        return;
    };
    let _ = waiter.send(to_result(message));
}

/// A JSON-RPC response as a result. Engine errors keep the engine's code and details.
pub fn to_result(message: Value) -> std::result::Result<Value, AppError> {
    if let Some(error) = message.get("error") {
        let text = error.get("message").and_then(Value::as_str).unwrap_or("engine error").to_string();
        return Err(match error.get("data") {
            Some(data) => AppError::new("engine", data.get("code").and_then(Value::as_str).unwrap_or("internal"), text)
                .with_details(data.get("details").cloned().unwrap_or(Value::Null)),
            None => AppError::new("engine", "protocol", text),
        });
    }
    Ok(message.get("result").cloned().unwrap_or(Value::Null))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn engine_errors_keep_their_code_and_details() {
        let error = to_result(json!({"jsonrpc": "2.0", "id": 1, "error": {"code": -32000, "message": "nope",
            "data": {"code": "plan_changed", "message": "nope", "details": {"expected": "a", "actual": "b"}}}}))
        .unwrap_err();
        assert_eq!(error.kind, "engine");
        assert_eq!(error.code, "plan_changed");
        assert_eq!(error.details["actual"], "b");
    }

    #[test]
    fn results_pass_through() {
        assert_eq!(to_result(json!({"id": 1, "result": {"ok": true}})).unwrap()["ok"], true);
    }

    #[test]
    fn notifications_go_to_the_callback_and_responses_to_their_caller() {
        let pending: Pending = Arc::new(Mutex::new(HashMap::new()));
        let (sender, mut receiver) = oneshot::channel();
        pending.lock().unwrap().insert(7, sender);
        let seen = Arc::new(Mutex::new(Vec::new()));
        let sink = seen.clone();
        let callback = move |method: &str, _: Value| sink.lock().unwrap().push(method.to_string());

        route(r#"{"jsonrpc":"2.0","method":"apply.progress","params":{}}"#, &pending, &callback);
        route("not json", &pending, &callback);
        route(r#"{"jsonrpc":"2.0","id":7,"result":42}"#, &pending, &callback);

        assert_eq!(*seen.lock().unwrap(), vec!["apply.progress".to_string()]);
        assert_eq!(receiver.try_recv().unwrap().unwrap(), json!(42));
    }

    fn engine_dir(dir: &Path, with_jar: bool) {
        std::fs::create_dir_all(dir.join("lib")).unwrap();
        std::fs::write(dir.join("lib/jackson-core.jar"), "").unwrap();
        if with_jar {
            std::fs::write(dir.join("lib/gitops-0.2.0.jar"), "").unwrap();
        }
    }

    #[test]
    fn locate_prefers_the_explicit_folder_then_the_bundle_then_the_repo() {
        let root = tempfile::tempdir().unwrap();
        let repo = root.path().join("repo");
        engine_dir(&repo.join("build/engine"), true);
        let bundled = root.path().join("bundle");
        engine_dir(&bundled, true);

        let found = locate(None, Some(bundled.clone()), &repo, None).unwrap();
        assert_eq!(found.lib, bundled.join("lib"));
        // Every jar is named on the classpath, the engine's own included.
        assert_eq!(found.jars, vec![bundled.join("lib/gitops-0.2.0.jar"), bundled.join("lib/jackson-core.jar")]);
        assert_eq!(locate(None, None, &repo, None).unwrap().lib, repo.join("build/engine/lib"));
        let missing = locate(Some(root.path().join("nope")), None, &root.path().join("empty"), None).unwrap_err();
        assert_eq!(missing.code, "engine_missing");
    }

    #[test]
    fn locate_skips_a_folder_without_the_engine_jar() {
        let root = tempfile::tempdir().unwrap();
        let repo = root.path().join("repo");
        engine_dir(&repo.join("build/engine"), true);
        let stale = root.path().join("stale");
        engine_dir(&stale, false);

        assert_eq!(locate(None, Some(stale.clone()), &repo, None).unwrap().lib, repo.join("build/engine/lib"));
        let error = locate(None, Some(stale), &root.path().join("other"), None).unwrap_err();
        assert!(error.message.contains("no gitops-*.jar"), "{}", error.message);
    }

    #[test]
    fn only_known_methods_are_allowed() {
        assert!(ALLOWED_METHODS.contains(&"plan.compute"));
        assert!(!ALLOWED_METHODS.contains(&"engine.shutdown"));
    }

    /// The real engine from ./gradlew engineBundle, against this repository's own config. Skipped when the
    /// engine hasn't been built (CI builds it first).
    #[tokio::test]
    async fn talks_to_the_real_engine() {
        let repo = dunce::canonicalize(Path::new(env!("CARGO_MANIFEST_DIR")).join("../..")).unwrap();
        let Ok(location) = locate(None, None, &repo, std::env::var_os("JAVA_HOME").map(PathBuf::from)) else {
            eprintln!("skipped: run ./gradlew engineBundle first");
            return;
        };
        let engine = Engine::start(&location, &repo, &HashMap::new(), |_, _| {}).unwrap();

        let version = engine.handshake().await.unwrap();
        assert_eq!(version["schemaVersion"], SCHEMA_VERSION);
        let targets = engine.call("targets.list", json!({})).await.unwrap();
        assert!(targets["targets"].as_array().unwrap().iter().any(|t| t["name"] == "dev"));
        let error = engine.call("plan.compute", json!({"target": "no-such-target"})).await.unwrap_err();
        assert_eq!(error.code, "usage");

        engine.stop();
        assert!(engine.call("targets.list", json!({})).await.is_err());
    }
}
