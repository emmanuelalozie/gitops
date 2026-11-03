//! Watches config/ and .github/workflows/ in the open repository, so the UI re-validates and re-plans when
//! files change (edited here, in an editor, or by switching branch). Bursts are collapsed into one event.

use notify::{RecursiveMode, Watcher};
use std::collections::BTreeSet;
use std::path::{Path, PathBuf};
use std::sync::mpsc;
use std::time::Duration;

const QUIET: Duration = Duration::from_millis(300);

/// Calls `on_change` with the changed paths (relative to the repository) after each burst of changes.
pub fn watch(repo: &Path, on_change: impl Fn(Vec<String>) + Send + 'static) -> notify::Result<notify::RecommendedWatcher> {
    let (sender, receiver) = mpsc::channel::<PathBuf>();
    let mut watcher = notify::recommended_watcher(move |event: notify::Result<notify::Event>| {
        if let Ok(event) = event {
            for path in event.paths {
                let _ = sender.send(path);
            }
        }
    })?;
    for dir in [repo.join("config"), repo.join(".github").join("workflows")] {
        if dir.is_dir() {
            watcher.watch(&dir, RecursiveMode::Recursive)?;
        }
    }
    let root = repo.to_path_buf();
    std::thread::Builder::new()
        .name("repo-watcher".into())
        .spawn(move || {
            // Ends when the watcher (and with it the sender) is dropped.
            while let Ok(first) = receiver.recv() {
                let mut changed = BTreeSet::new();
                changed.insert(first);
                while let Ok(next) = receiver.recv_timeout(QUIET) {
                    changed.insert(next);
                }
                on_change(relative(&root, changed));
            }
        })
        .map_err(|e| notify::Error::generic(&e.to_string()))?;
    Ok(watcher)
}

fn relative(root: &Path, paths: BTreeSet<PathBuf>) -> Vec<String> {
    paths
        .into_iter()
        .filter_map(|p| p.strip_prefix(root).ok().map(|r| r.to_string_lossy().replace('\\', "/")))
        .filter(|p| !p.ends_with(".tmp") && !p.contains("/.#"))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reports_bursts_of_changes_once_with_relative_paths() {
        let dir = tempfile::tempdir().unwrap();
        let repo = dir.path().canonicalize().unwrap();
        std::fs::create_dir_all(repo.join("config/dev/users")).unwrap();
        let (sender, receiver) = mpsc::channel();
        let _watcher = watch(&repo, move |paths| sender.send(paths).unwrap()).unwrap();
        std::thread::sleep(Duration::from_millis(200));

        std::fs::write(repo.join("config/dev/users/a.json"), "{}").unwrap();
        std::fs::write(repo.join("config/dev/users/b.json"), "{}").unwrap();

        let paths = receiver.recv_timeout(Duration::from_secs(5)).unwrap();
        assert!(paths.contains(&"config/dev/users/a.json".to_string()), "{paths:?}");
        assert!(paths.contains(&"config/dev/users/b.json".to_string()), "{paths:?}");
    }
}
