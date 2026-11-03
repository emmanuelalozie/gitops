//! Decides whether a folder is a GitOps repository the app can open, and when it isn't, says why in a way the
//! welcome screen can act on: open the repository it sits inside, or explain how to manage the project from one.

use crate::error::{AppError, Result};
use crate::git;
use serde_json::json;
use std::path::{Path, PathBuf};

/// The targets in a folder: config/<name>/target.json.
pub fn target_names(folder: &Path) -> Vec<String> {
    let Ok(entries) = std::fs::read_dir(folder.join("config")) else { return Vec::new() };
    let mut names: Vec<String> = entries
        .filter_map(|e| e.ok())
        .filter(|e| e.path().join("target.json").is_file())
        .map(|e| e.file_name().to_string_lossy().into_owned())
        .collect();
    names.sort();
    names
}

/// Ok when the folder has at least one target. Otherwise an error with a code the UI understands:
/// `inside_gitops_repo` (details.suggestion is the repository to open), `no_targets`, `not_gitops`
/// (details.github is the project's GitHub repository, when it has one) or `not_a_folder`.
pub fn check(folder: &Path) -> Result<()> {
    if !folder.is_dir() {
        return Err(AppError::new("repo", "not_a_folder", format!("{} isn't a folder", folder.display()))
            .with_details(json!({"path": folder})));
    }
    if !target_names(folder).is_empty() {
        return Ok(());
    }
    if let Some(root) = folder.ancestors().skip(1).find(|a| !target_names(a).is_empty()) {
        return Err(AppError::new(
            "repo",
            "inside_gitops_repo",
            format!("This folder is inside the GitOps repository {}. Open that repository's top folder instead.", root.display()),
        )
        .with_details(json!({"path": folder, "suggestion": root})));
    }
    let name = folder.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
    if folder.join("config").is_dir() {
        return Err(AppError::new(
            "repo",
            "no_targets",
            format!(
                "{name} has a config/ folder, but no targets in it. Each target is a folder with a target.json, such as config/dev/target.json."
            ),
        )
        .with_details(json!({"path": folder, "name": name})));
    }
    let github = git::status(folder).ok().and_then(|s| s.github);
    Err(AppError::new(
        "repo",
        "not_gitops",
        format!(
            "{name} is a regular project, not a GitOps repository. The app opens a repository that describes live systems in config/<target>/target.json, such as your gitops repository."
        ),
    )
    .with_details(json!({"path": folder, "name": name, "github": github})))
}

/// The folder a person picked, as a plain path (no \\?\ prefix on Windows).
pub fn resolve(path: &str) -> Result<PathBuf> {
    dunce::canonicalize(PathBuf::from(path)).map_err(|e| {
        AppError::new("repo", "not_a_folder", format!("Can't open {path}: {e}")).with_details(json!({"path": path}))
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn gitops_repo(root: &Path) {
        std::fs::create_dir_all(root.join("config/dev/users")).unwrap();
        std::fs::write(root.join("config/dev/target.json"), "{}").unwrap();
    }

    #[test]
    fn a_folder_with_targets_opens() {
        let dir = tempfile::tempdir().unwrap();
        gitops_repo(dir.path());
        check(dir.path()).unwrap();
        assert_eq!(target_names(dir.path()), vec!["dev".to_string()]);
    }

    #[test]
    fn a_folder_inside_one_points_at_its_root() {
        let dir = tempfile::tempdir().unwrap();
        gitops_repo(dir.path());
        let error = check(&dir.path().join("config/dev/users")).unwrap_err();
        assert_eq!(error.code, "inside_gitops_repo");
        assert_eq!(error.details["suggestion"], json!(dir.path()));
    }

    #[test]
    fn an_empty_config_folder_says_targets_are_missing() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(dir.path().join("config/settings")).unwrap();
        assert_eq!(check(dir.path()).unwrap_err().code, "no_targets");
    }

    #[test]
    fn a_plain_project_is_explained_with_its_github_repository() {
        let lone = tempfile::tempdir().unwrap();
        let project = lone.path().join("inventory-manager");
        std::fs::create_dir_all(&project).unwrap();
        git::run(&project, &["init", "-q"]).unwrap();
        git::run(&project, &["remote", "add", "origin", "https://github.com/me/inventory-manager.git"]).unwrap();

        let error = check(&project).unwrap_err();
        assert_eq!(error.code, "not_gitops");
        assert_eq!(error.details["name"], "inventory-manager");
        assert_eq!(error.details["github"], json!({"owner": "me", "name": "inventory-manager"}));
        assert!(error.message.contains("regular project"));
    }

    #[test]
    fn a_missing_folder_says_so() {
        assert_eq!(resolve("/definitely/not/here").unwrap_err().code, "not_a_folder");
    }
}
