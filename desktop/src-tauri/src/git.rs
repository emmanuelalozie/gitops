//! Git, through the `git` command the person already uses, so their credential helper, SSH keys, hooks and
//! commit signing all apply. Arguments are always passed as a list (never through a shell), and paths are
//! passed after `--`, so nothing in a file or branch name can turn into an option.

use crate::error::{AppError, Result};
use serde::Serialize;
use std::path::Path;
use std::process::Command;

pub fn run(repo: &Path, args: &[&str]) -> Result<String> {
    let mut command = Command::new("git");
    command.arg("-C").arg(repo).args(args).env("GIT_TERMINAL_PROMPT", "0").env("LC_ALL", "C");
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x0800_0000);
    }
    let output = command.output().map_err(|e| {
        AppError::new("git", "git_missing", format!("Couldn't run git ({e}). Install Git and make sure it's on the PATH."))
    })?;
    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();
        return Err(AppError::new("git", "git_failed", format!("git {} failed: {stderr}", args.join(" "))));
    }
    Ok(String::from_utf8_lossy(&output.stdout).into_owned())
}

/// The GitHub repository a remote points at.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct GitHubRepo {
    pub owner: String,
    pub name: String,
}

impl GitHubRepo {
    pub fn full_name(&self) -> String {
        format!("{}/{}", self.owner, self.name)
    }
}

/// Reads owner/name from https://github.com/o/r(.git), git@github.com:o/r(.git) or ssh://git@github.com/o/r.
pub fn parse_github_remote(url: &str) -> Option<GitHubRepo> {
    let url = url.trim();
    let rest = url
        .strip_prefix("https://github.com/")
        .or_else(|| url.strip_prefix("http://github.com/"))
        .or_else(|| url.strip_prefix("git@github.com:"))
        .or_else(|| url.strip_prefix("ssh://git@github.com/"))
        .or_else(|| {
            // https://user@github.com/o/r or https://x-access-token:...@github.com/o/r
            let after_scheme = url.strip_prefix("https://")?;
            let (_, path) = after_scheme.split_once("@github.com/")?;
            Some(path)
        })?;
    let rest = rest.trim_end_matches('/').trim_end_matches(".git");
    let (owner, name) = rest.split_once('/')?;
    if owner.is_empty() || name.is_empty() || name.contains('/') {
        return None;
    }
    Some(GitHubRepo { owner: owner.to_string(), name: name.to_string() })
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RepoStatus {
    pub branch: Option<String>,
    pub github: Option<GitHubRepo>,
    pub default_branch: Option<String>,
    pub shallow: bool,
}

pub fn status(repo: &Path) -> Result<RepoStatus> {
    run(repo, &["rev-parse", "--git-dir"])
        .map_err(|_| AppError::repo(format!("{} isn't a Git repository", repo.display())))?;
    let branch = run(repo, &["symbolic-ref", "--quiet", "--short", "HEAD"]).ok().map(|b| b.trim().to_string());
    let github = run(repo, &["remote", "get-url", "origin"]).ok().and_then(|url| parse_github_remote(&url));
    let default_branch = run(repo, &["symbolic-ref", "--quiet", "--short", "refs/remotes/origin/HEAD"])
        .ok()
        .map(|r| r.trim().trim_start_matches("origin/").to_string());
    let shallow = run(repo, &["rev-parse", "--is-shallow-repository"]).map(|s| s.trim() == "true").unwrap_or(false);
    Ok(RepoStatus { branch, github, default_branch, shallow })
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    /// A fresh repository with one commit, for tests that need real git.
    pub fn repo() -> tempfile::TempDir {
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path();
        run(p, &["init", "-q", "-b", "develop"]).unwrap();
        run(p, &["config", "user.email", "t@example.com"]).unwrap();
        run(p, &["config", "user.name", "Test"]).unwrap();
        run(p, &["config", "commit.gpgsign", "false"]).unwrap();
        std::fs::create_dir_all(p.join("config/dev/users")).unwrap();
        std::fs::write(p.join("config/dev/target.json"), "{\"kind\": \"users\"}\n").unwrap();
        std::fs::write(p.join("config/dev/users/bret.json"), "{\"username\": \"Bret\"}\n").unwrap();
        run(p, &["add", "-A"]).unwrap();
        run(p, &["commit", "-q", "-m", "Initial config"]).unwrap();
        dir
    }

    #[test]
    fn parses_github_remotes() {
        let expected = Some(GitHubRepo { owner: "o".into(), name: "r".into() });
        for url in [
            "https://github.com/o/r",
            "https://github.com/o/r.git",
            "git@github.com:o/r.git",
            "ssh://git@github.com/o/r",
            "https://user@github.com/o/r.git\n",
        ] {
            assert_eq!(parse_github_remote(url), expected, "{url}");
        }
        assert_eq!(parse_github_remote("https://gitlab.com/o/r"), None);
        assert_eq!(parse_github_remote("https://github.com/o"), None);
    }

    #[test]
    fn reports_branch_and_remote() {
        let dir = repo();
        run(dir.path(), &["remote", "add", "origin", "git@github.com:octo/repo.git"]).unwrap();
        let status = status(dir.path()).unwrap();
        assert_eq!(status.branch.as_deref(), Some("develop"));
        assert_eq!(status.github.unwrap().full_name(), "octo/repo");
        assert!(!status.shallow);
    }

    #[test]
    fn a_folder_that_is_not_a_repository_says_so() {
        let dir = tempfile::tempdir().unwrap();
        assert_eq!(status(dir.path()).unwrap_err().code, "invalid_repo");
    }
}
