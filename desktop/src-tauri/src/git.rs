//! Git, through the `git` command the person already uses, so their credential helper, SSH keys, hooks and
//! commit signing all apply. Arguments are always passed as a list (never through a shell), and paths are
//! passed after `--`, so nothing in a file or branch name can turn into an option.

use crate::error::{AppError, Result};
use serde::Serialize;
use std::io::Read;
use std::path::Path;
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

pub fn run(repo: &Path, args: &[&str]) -> Result<String> {
    run_with_timeout(repo, args, Duration::from_secs(60))
}

/// Runs git, killing it after `limit` (a push waiting on a credential prompt would otherwise hang forever).
pub fn run_with_timeout(repo: &Path, args: &[&str], limit: Duration) -> Result<String> {
    let mut command = Command::new("git");
    command
        .arg("-C")
        .arg(repo)
        .args(args)
        .env("GIT_TERMINAL_PROMPT", "0")
        .env("LC_ALL", "C")
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x0800_0000);
    }
    let mut child = command.spawn().map_err(|e| {
        AppError::new("git", "git_missing", format!("Couldn't run git ({e}). Install Git and make sure it's on the PATH."))
    })?;
    let mut stdout = child.stdout.take().expect("piped");
    let mut stderr = child.stderr.take().expect("piped");
    let out = std::thread::spawn(move || {
        let mut buf = Vec::new();
        let _ = stdout.read_to_end(&mut buf);
        buf
    });
    let err = std::thread::spawn(move || {
        let mut buf = Vec::new();
        let _ = stderr.read_to_end(&mut buf);
        buf
    });
    let deadline = Instant::now() + limit;
    let status = loop {
        if let Some(status) = child.try_wait()? {
            break status;
        }
        if Instant::now() >= deadline {
            let _ = child.kill();
            let _ = child.wait();
            return Err(AppError::new("git", "timeout", format!("git {} didn't finish within {}s", args.join(" "), limit.as_secs())));
        }
        std::thread::sleep(Duration::from_millis(20));
    };
    let stdout = out.join().unwrap_or_default();
    let stderr = err.join().unwrap_or_default();
    if !status.success() {
        let stderr = String::from_utf8_lossy(&stderr).trim().to_string();
        return Err(AppError::new("git", "git_failed", format!("git {} failed: {stderr}", args.join(" "))));
    }
    Ok(String::from_utf8_lossy(&stdout).into_owned())
}

/// The GitHub repository a remote points at.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct GitHubRepo {
    pub owner: String,
    pub name: String,
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

/// A file under config/ that differs from HEAD.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ConfigChange {
    pub path: String,
    /// modified, added, deleted, renamed or untracked
    pub status: String,
    /// The target the file belongs to (config/<target>/...), if any.
    pub target: Option<String>,
}

pub fn target_of(path: &str) -> Option<String> {
    let rest = path.strip_prefix("config/")?;
    let (target, tail) = rest.split_once('/')?;
    (!target.is_empty() && !tail.is_empty()).then(|| target.to_string())
}

/// Uncommitted changes under config/, staged or not, including new files.
pub fn config_changes(repo: &Path) -> Result<Vec<ConfigChange>> {
    let out = run(repo, &["status", "--porcelain=v1", "-z", "--untracked-files=all", "--", "config"])?;
    Ok(parse_status(&out))
}

fn parse_status(out: &str) -> Vec<ConfigChange> {
    let mut changes = Vec::new();
    let mut entries = out.split('\0').filter(|e| !e.is_empty());
    while let Some(entry) = entries.next() {
        if entry.len() < 4 {
            continue;
        }
        let (code, path) = entry.split_at(3);
        let code = code.trim_end();
        let status = match code {
            "??" => "untracked",
            c if c.contains('R') => {
                entries.next(); // the old name
                "renamed"
            }
            c if c.contains('D') => "deleted",
            c if c.contains('A') => "added",
            _ => "modified",
        };
        changes.push(ConfigChange { path: path.to_string(), status: status.to_string(), target: target_of(path) });
    }
    changes
}

/// The working-tree change of one config file as a unified diff (a new file shows as all added lines).
pub fn working_diff(repo: &Path, path: &str) -> Result<String> {
    check_config_path(path)?;
    let tracked = run(repo, &["ls-files", "--error-unmatch", "--", path]).is_ok();
    if !tracked {
        let text = std::fs::read_to_string(repo.join(path)).unwrap_or_default();
        let mut diff = format!("--- /dev/null\n+++ b/{path}\n");
        for line in text.lines() {
            diff.push('+');
            diff.push_str(line);
            diff.push('\n');
        }
        return Ok(diff);
    }
    run(repo, &["diff", "--no-color", "--no-ext-diff", "HEAD", "--", path])
}

/// Puts one config file back to HEAD (or removes it if it's new). The UI asks first.
pub fn discard(repo: &Path, path: &str) -> Result<()> {
    check_config_path(path)?;
    if run(repo, &["ls-files", "--error-unmatch", "--", path]).is_ok() {
        run(repo, &["restore", "--source=HEAD", "--staged", "--worktree", "--", path])?;
    } else {
        std::fs::remove_file(repo.join(path))?;
    }
    Ok(())
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Commit {
    pub sha: String,
    pub short: String,
    pub author: String,
    pub date: String,
    pub subject: String,
    /// The pull request a merge or squash commit came from ("Merge pull request #14 ..." or "... (#14)").
    pub pull_request: Option<u64>,
}

/// Commits that touched a path (a file follows renames; a folder covers everything in it), newest first.
pub fn history(repo: &Path, path: &str, limit: usize) -> Result<Vec<Commit>> {
    check_config_path(path)?;
    let limit = limit.clamp(1, 500).to_string();
    let mut args = vec!["log", "-n", &limit, "--format=%H%x1f%h%x1f%an%x1f%aI%x1f%s%x1e"];
    if repo.join(path).is_file() {
        args.push("--follow");
    }
    args.extend(["--", path]);
    let out = run(repo, &args)?;
    Ok(out
        .split('\u{1e}')
        .filter_map(|record| {
            let fields: Vec<&str> = record.trim_matches(['\n', '\r']).split('\u{1f}').collect();
            let [sha, short, author, date, subject] = fields[..] else { return None };
            Some(Commit {
                sha: sha.to_string(),
                short: short.to_string(),
                author: author.to_string(),
                date: date.to_string(),
                subject: subject.to_string(),
                pull_request: pull_request_number(subject),
            })
        })
        .collect())
}

pub fn pull_request_number(subject: &str) -> Option<u64> {
    let digits = |s: &str| s.chars().take_while(char::is_ascii_digit).collect::<String>().parse().ok();
    if let Some(rest) = subject.strip_prefix("Merge pull request #") {
        return digits(rest);
    }
    let start = subject.rfind("(#")?;
    let number: Option<u64> = digits(&subject[start + 2..]);
    subject[start..].ends_with(')').then_some(number).flatten()
}

/// What one commit changed in a path.
pub fn commit_diff(repo: &Path, sha: &str, path: &str) -> Result<String> {
    check_config_path(path)?;
    if sha.len() < 7 || !sha.chars().all(|c| c.is_ascii_hexdigit()) {
        return Err(AppError::new("git", "invalid_sha", format!("'{sha}' isn't a commit id")));
    }
    run(repo, &["show", "--no-color", "--no-ext-diff", "--format=", sha, "--", path])
}

/// Only paths inside config/, relative, without "..": the UI can't name anything else.
fn check_config_path(path: &str) -> Result<()> {
    let ok = (path == "config" || path.starts_with("config/"))
        && !path.split('/').any(|part| part == ".." || part.is_empty() && path != "config")
        && !path.contains('\\')
        && !path.starts_with('-');
    if ok {
        Ok(())
    } else {
        Err(AppError::new("git", "invalid_path", format!("'{path}' isn't a path under config/")))
    }
}

#[derive(Debug, Clone, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Proposal {
    pub branch: String,
    pub title: String,
    #[serde(default)]
    pub body: String,
    pub paths: Vec<String>,
    /// Labels for the pull request, such as "promotion".
    #[serde(default)]
    pub labels: Vec<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Proposed {
    pub branch: String,
    pub commit: String,
    pub from_branch: String,
    pub pushed: bool,
    pub push_error: Option<String>,
}

/// Commits the chosen config files to a new branch, pushes it, and goes back to the branch you were on.
///
/// Only the chosen paths are committed (`git commit -- <paths>`), so other edits, staged or not, stay as they
/// were. The files leave your working branch: they now live on the new branch and, once merged, come back
/// through the default branch. If the push fails, the commit stays on the new branch for you to push later.
pub fn propose(repo: &Path, proposal: &Proposal) -> Result<Proposed> {
    let branch = proposal.branch.trim();
    let title = proposal.title.trim();
    if title.is_empty() {
        return Err(AppError::new("git", "invalid_proposal", "A title is required"));
    }
    if proposal.paths.is_empty() {
        return Err(AppError::new("git", "invalid_proposal", "Choose at least one changed file"));
    }
    if branch.starts_with('-') || run(repo, &["check-ref-format", "--branch", branch]).is_err() {
        return Err(AppError::new("git", "invalid_branch", format!("'{branch}' isn't a valid branch name")));
    }
    if run(repo, &["rev-parse", "--verify", "--quiet", &format!("refs/heads/{branch}")]).is_ok() {
        return Err(AppError::new("git", "branch_exists", format!("A branch named '{branch}' already exists")));
    }
    let changed: Vec<String> = config_changes(repo)?.into_iter().map(|c| c.path).collect();
    for path in &proposal.paths {
        check_config_path(path)?;
        if !changed.contains(path) {
            return Err(AppError::new("git", "invalid_proposal", format!("{path} has no uncommitted change")));
        }
    }
    let from_branch = run(repo, &["symbolic-ref", "--quiet", "--short", "HEAD"])
        .map(|b| b.trim().to_string())
        .map_err(|_| AppError::new("git", "detached_head", "Check out a branch first: proposing from a detached HEAD isn't supported"))?;

    run(repo, &["switch", "-c", branch])?;
    let committed = (|| {
        let mut add = vec!["add", "-A", "--"];
        add.extend(proposal.paths.iter().map(String::as_str));
        run(repo, &add)?;
        let message = if proposal.body.trim().is_empty() { title.to_string() } else { format!("{title}\n\n{}", proposal.body.trim()) };
        let mut commit = vec!["commit", "--quiet", "-m", message.as_str(), "--"];
        commit.extend(proposal.paths.iter().map(String::as_str));
        run(repo, &commit)?;
        run(repo, &["rev-parse", "HEAD"]).map(|s| s.trim().to_string())
    })();
    let commit = match committed {
        Ok(commit) => commit,
        Err(e) => {
            // Nothing was committed: go back and drop the empty branch, leaving the files as they were.
            let _ = run(repo, &["switch", &from_branch]);
            let _ = run(repo, &["branch", "-D", branch]);
            return Err(e);
        }
    };
    let push = run_with_timeout(repo, &["push", "--set-upstream", "origin", branch], Duration::from_secs(120));
    run(repo, &["switch", &from_branch])?;
    Ok(Proposed {
        branch: branch.to_string(),
        commit,
        from_branch,
        pushed: push.is_ok(),
        push_error: push.err().map(|e| e.message),
    })
}

fn check_sha(sha: &str) -> Result<()> {
    if sha.len() < 7 || sha.len() > 64 || !sha.chars().all(|c| c.is_ascii_hexdigit()) {
        return Err(AppError::new("git", "invalid_sha", format!("'{sha}' isn't a commit id")));
    }
    Ok(())
}

/// Opens a branch that undoes one past change to config/ (a merge of a pull request included), pushes it and goes
/// back to the branch you were on. Refuses commits that touch anything outside config/, and stops cleanly if
/// later changes conflict. Your uncommitted config edits must be committed or discarded first.
pub fn revert(repo: &Path, sha: &str, branch: &str) -> Result<Proposed> {
    check_sha(sha)?;
    let branch = branch.trim();
    if branch.starts_with('-') || run(repo, &["check-ref-format", "--branch", branch]).is_err() {
        return Err(AppError::new("git", "invalid_branch", format!("'{branch}' isn't a valid branch name")));
    }
    if run(repo, &["rev-parse", "--verify", "--quiet", &format!("refs/heads/{branch}")]).is_ok() {
        return Err(AppError::new("git", "branch_exists", format!("A branch named '{branch}' already exists")));
    }
    if !config_changes(repo)?.is_empty() {
        return Err(AppError::new("git", "dirty", "Commit or discard your changes under config/ before reverting one"));
    }
    if run(repo, &["merge-base", "--is-ancestor", sha, "HEAD"]).is_err() {
        return Err(AppError::new("git", "not_ancestor", "That commit isn't part of the branch you're on"));
    }
    let parents = run(repo, &["rev-list", "--parents", "-n", "1", sha])?.split_whitespace().count() - 1;
    let files = if parents > 1 {
        run(repo, &["diff", "--name-only", &format!("{sha}^1"), sha])?
    } else {
        run(repo, &["show", "--name-only", "--format=", sha])?
    };
    let outside: Vec<&str> = files.lines().filter(|f| !f.is_empty() && !f.starts_with("config/")).collect();
    if !outside.is_empty() {
        return Err(AppError::new(
            "git",
            "outside_config",
            format!("That commit also changes {} outside config/, so revert it by hand", outside.join(", ")),
        ));
    }
    let from_branch = run(repo, &["symbolic-ref", "--quiet", "--short", "HEAD"])
        .map(|b| b.trim().to_string())
        .map_err(|_| AppError::new("git", "detached_head", "Check out a branch first"))?;
    run(repo, &["switch", "-c", branch])?;
    let mut args = vec!["revert", "--no-edit"];
    if parents > 1 {
        args.extend(["-m", "1"]);
    }
    args.push(sha);
    if let Err(e) = run(repo, &args) {
        let _ = run(repo, &["revert", "--abort"]);
        let _ = run(repo, &["switch", &from_branch]);
        let _ = run(repo, &["branch", "-D", branch]);
        return Err(AppError::new(
            "git",
            "conflict",
            format!("Later changes touched the same lines, so it can't be undone automatically: {}", e.message),
        ));
    }
    let commit = run(repo, &["rev-parse", "HEAD"])?.trim().to_string();
    let push = run_with_timeout(repo, &["push", "--set-upstream", "origin", branch], Duration::from_secs(120));
    run(repo, &["switch", &from_branch])?;
    Ok(Proposed { branch: branch.to_string(), commit, from_branch, pushed: push.is_ok(), push_error: push.err().map(|e| e.message) })
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Blame {
    pub field: String,
    pub line: String,
    /// Null when the line is an uncommitted edit.
    pub commit: Option<Commit>,
}

/// Which commit last set a field of a config file (the line `  "field": ...`, as the engine writes them).
pub fn blame_field(repo: &Path, path: &str, field: &str) -> Result<Blame> {
    check_config_path(path)?;
    let text = std::fs::read_to_string(repo.join(path))?;
    let needle = format!("  {}:", serde_json::to_string(field)?);
    let (number, line) = text
        .lines()
        .enumerate()
        .find(|(_, l)| l.starts_with(&needle))
        .ok_or_else(|| AppError::new("git", "no_field", format!("{path} has no top-level \"{field}\"")))?;
    let range = format!("{},{}", number + 1, number + 1);
    let out = run(repo, &["blame", "--porcelain", "-L", &range, "--", path])?;
    let mut lines = out.lines();
    let sha = lines.next().unwrap_or_default().split(' ').next().unwrap_or_default().to_string();
    if sha.chars().all(|c| c == '0') {
        return Ok(Blame { field: field.to_string(), line: line.trim().to_string(), commit: None });
    }
    let mut author = String::new();
    let mut time = 0i64;
    let mut subject = String::new();
    for l in lines {
        if let Some(v) = l.strip_prefix("author ") {
            author = v.to_string();
        } else if let Some(v) = l.strip_prefix("author-time ") {
            time = v.parse().unwrap_or(0);
        } else if let Some(v) = l.strip_prefix("summary ") {
            subject = v.to_string();
        }
    }
    let date = run(repo, &["show", "-s", "--format=%aI", &sha]).map(|d| d.trim().to_string()).unwrap_or_else(|_| time.to_string());
    Ok(Blame {
        field: field.to_string(),
        line: line.trim().to_string(),
        commit: Some(Commit { short: sha[..7.min(sha.len())].to_string(), sha, author, date, pull_request: pull_request_number(&subject), subject }),
    })
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AuditEntry {
    #[serde(flatten)]
    pub commit: Commit,
    pub targets: Vec<String>,
    pub files: Vec<String>,
}

/// Config changes on the current branch's mainline, newest first: each merge (a pull request) or direct commit
/// once, with the targets and files it changed.
pub fn audit(repo: &Path, limit: usize) -> Result<Vec<AuditEntry>> {
    let limit = limit.clamp(1, 500).to_string();
    let out = run(repo, &["log", "--first-parent", "-m", "-n", &limit, "--name-only", "--format=%x1e%H%x1f%h%x1f%an%x1f%aI%x1f%s", "--", "config"])?;
    let mut entries = Vec::new();
    for record in out.split('\u{1e}').filter(|r| !r.trim().is_empty()) {
        let mut lines = record.lines();
        let header = lines.next().unwrap_or_default();
        let fields: Vec<&str> = header.split('\u{1f}').collect();
        let [sha, short, author, date, subject] = fields[..] else { continue };
        let files: Vec<String> = lines.map(str::trim).filter(|l| l.starts_with("config/")).map(String::from).collect();
        let mut targets: Vec<String> = files.iter().filter_map(|f| target_of(f)).collect();
        targets.sort();
        targets.dedup();
        if entries.iter().any(|e: &AuditEntry| e.commit.sha == sha) {
            continue;
        }
        entries.push(AuditEntry {
            commit: Commit {
                sha: sha.to_string(),
                short: short.to_string(),
                author: author.to_string(),
                date: date.to_string(),
                subject: subject.to_string(),
                pull_request: pull_request_number(subject),
            },
            targets,
            files,
        });
    }
    Ok(entries)
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
        assert_eq!(status.github, Some(GitHubRepo { owner: "octo".into(), name: "repo".into() }));
        assert!(!status.shallow);
    }

    #[test]
    fn lists_config_changes_including_new_files() {
        let dir = repo();
        let p = dir.path();
        std::fs::write(p.join("config/dev/users/bret.json"), "{\"username\": \"Bert\"}\n").unwrap();
        std::fs::write(p.join("config/dev/users/ann.json"), "{}\n").unwrap();
        std::fs::write(p.join("README.md"), "outside config\n").unwrap();

        let changes = config_changes(p).unwrap();
        assert_eq!(
            changes,
            vec![
                ConfigChange { path: "config/dev/users/bret.json".into(), status: "modified".into(), target: Some("dev".into()) },
                ConfigChange { path: "config/dev/users/ann.json".into(), status: "untracked".into(), target: Some("dev".into()) },
            ]
        );
        assert!(working_diff(p, "config/dev/users/bret.json").unwrap().contains("+{\"username\": \"Bert\"}"));
        assert!(working_diff(p, "config/dev/users/ann.json").unwrap().starts_with("--- /dev/null"));
    }

    #[test]
    fn refuses_paths_outside_config() {
        for path in ["README.md", "config/../secrets", "/etc/passwd", "-p", "config//x", "configs/x"] {
            assert_eq!(working_diff(Path::new("."), path).unwrap_err().code, "invalid_path", "{path}");
        }
    }

    #[test]
    fn history_follows_a_file_and_finds_pull_requests() {
        let dir = repo();
        let p = dir.path();
        std::fs::write(p.join("config/dev/users/bret.json"), "{\"username\": \"Bret\", \"id\": 1}\n").unwrap();
        run(p, &["commit", "-q", "-am", "Give Bret an id (#12)"]).unwrap();

        let commits = history(p, "config/dev/users/bret.json", 10).unwrap();
        assert_eq!(commits.len(), 2);
        assert_eq!(commits[0].subject, "Give Bret an id (#12)");
        assert_eq!(commits[0].pull_request, Some(12));
        assert!(commit_diff(p, &commits[0].sha, "config/dev/users/bret.json").unwrap().contains("+{\"username\": \"Bret\", \"id\": 1}"));
        assert_eq!(history(p, "config/dev", 10).unwrap().len(), 2);
    }

    #[test]
    fn reads_pull_request_numbers() {
        assert_eq!(pull_request_number("Merge pull request #14 from o/fix"), Some(14));
        assert_eq!(pull_request_number("Fix labels (#7)"), Some(7));
        assert_eq!(pull_request_number("Use (#7) in the middle"), None);
        assert_eq!(pull_request_number("Plain commit"), None);
    }

    #[test]
    fn discard_restores_or_removes() {
        let dir = repo();
        let p = dir.path();
        std::fs::write(p.join("config/dev/users/bret.json"), "changed\n").unwrap();
        std::fs::write(p.join("config/dev/users/new.json"), "{}\n").unwrap();
        discard(p, "config/dev/users/bret.json").unwrap();
        discard(p, "config/dev/users/new.json").unwrap();
        assert!(config_changes(p).unwrap().is_empty());
    }

    #[test]
    fn propose_commits_only_the_chosen_files_to_a_new_branch_and_comes_back() {
        let dir = repo();
        let p = dir.path();
        // A bare remote to push to.
        let remote = tempfile::tempdir().unwrap();
        run(remote.path(), &["init", "-q", "--bare"]).unwrap();
        run(p, &["remote", "add", "origin", remote.path().to_str().unwrap()]).unwrap();
        std::fs::write(p.join("config/dev/users/bret.json"), "{\"username\": \"Bert\"}\n").unwrap();
        std::fs::write(p.join("config/dev/users/ann.json"), "{\"username\": \"Ann\"}\n").unwrap();

        let proposed = propose(p, &Proposal {
            branch: "gitops/add-ann".into(),
            title: "Add Ann".into(),
            body: "Requested in the app".into(),
            labels: vec![],
            paths: vec!["config/dev/users/ann.json".into()],
        })
        .unwrap();

        assert!(proposed.pushed, "{:?}", proposed.push_error);
        assert_eq!(proposed.from_branch, "develop");
        assert_eq!(run(p, &["symbolic-ref", "--short", "HEAD"]).unwrap().trim(), "develop");
        // The proposed file moved to the branch; the other edit is still here, uncommitted.
        let left: Vec<String> = config_changes(p).unwrap().into_iter().map(|c| c.path).collect();
        assert_eq!(left, vec!["config/dev/users/bret.json".to_string()]);
        let files = run(p, &["show", "--name-only", "--format=%s", "gitops/add-ann"]).unwrap();
        assert!(files.contains("Add Ann") && files.contains("config/dev/users/ann.json") && !files.contains("bret.json"), "{files}");
        assert!(run(remote.path(), &["rev-parse", "--verify", "gitops/add-ann"]).is_ok());
    }

    #[test]
    fn propose_refuses_bad_requests_without_touching_anything() {
        let dir = repo();
        let p = dir.path();
        std::fs::write(p.join("config/dev/users/bret.json"), "x\n").unwrap();
        let bad = |branch: &str, paths: Vec<&str>| {
            propose(p, &Proposal { branch: branch.into(), title: "t".into(), body: String::new(), paths: paths.into_iter().map(String::from).collect(), labels: vec![] })
                .unwrap_err()
                .code
        };
        assert_eq!(bad("has space", vec!["config/dev/users/bret.json"]), "invalid_branch");
        assert_eq!(bad("-x", vec!["config/dev/users/bret.json"]), "invalid_branch");
        assert_eq!(bad("develop", vec!["config/dev/users/bret.json"]), "branch_exists");
        assert_eq!(bad("ok", vec!["config/dev/target.json"]), "invalid_proposal");
        assert_eq!(bad("ok", vec!["README.md"]), "invalid_path");
        assert_eq!(run(p, &["symbolic-ref", "--short", "HEAD"]).unwrap().trim(), "develop");
        assert_eq!(config_changes(p).unwrap().len(), 1);
    }

    #[test]
    fn revert_undoes_a_config_change_on_a_new_branch() {
        let dir = repo();
        let p = dir.path();
        let remote = tempfile::tempdir().unwrap();
        run(remote.path(), &["init", "-q", "--bare"]).unwrap();
        run(p, &["remote", "add", "origin", remote.path().to_str().unwrap()]).unwrap();
        std::fs::write(p.join("config/dev/users/bret.json"), "{\"username\": \"Bert\"}\n").unwrap();
        run(p, &["commit", "-q", "-am", "Rename Bret (#3)"]).unwrap();
        let sha = run(p, &["rev-parse", "HEAD"]).unwrap().trim().to_string();

        let reverted = revert(p, &sha, "gitops/revert-rename").unwrap();

        assert!(reverted.pushed, "{:?}", reverted.push_error);
        assert_eq!(run(p, &["symbolic-ref", "--short", "HEAD"]).unwrap().trim(), "develop");
        let content = run(p, &["show", "gitops/revert-rename:config/dev/users/bret.json"]).unwrap();
        assert_eq!(content, "{\"username\": \"Bret\"}\n");
        assert!(run(p, &["log", "-1", "--format=%s", "gitops/revert-rename"]).unwrap().starts_with("Revert \"Rename Bret"));
    }

    #[test]
    fn revert_refuses_what_it_cant_do_safely() {
        let dir = repo();
        let p = dir.path();
        std::fs::write(p.join("README.md"), "x\n").unwrap();
        std::fs::write(p.join("config/dev/users/bret.json"), "{}\n").unwrap();
        run(p, &["add", "-A"]).unwrap();
        run(p, &["commit", "-q", "-m", "Mixed change"]).unwrap();
        let sha = run(p, &["rev-parse", "HEAD"]).unwrap().trim().to_string();
        assert_eq!(revert(p, &sha, "r1").unwrap_err().code, "outside_config");
        assert_eq!(revert(p, "nothex!", "r2").unwrap_err().code, "invalid_sha");
        std::fs::write(p.join("config/dev/users/bret.json"), "dirty\n").unwrap();
        assert_eq!(revert(p, &sha, "r3").unwrap_err().code, "dirty");
        assert_eq!(run(p, &["symbolic-ref", "--short", "HEAD"]).unwrap().trim(), "develop");
    }

    #[test]
    fn blame_names_the_commit_that_set_a_field() {
        let dir = repo();
        let p = dir.path();
        std::fs::write(p.join("config/dev/users/bret.json"), "{\n  \"username\": \"Bret\",\n  \"email\": \"a@b.io\"\n}\n").unwrap();
        run(p, &["commit", "-q", "-am", "Set Bret's email (#9)"]).unwrap();
        std::fs::write(p.join("config/dev/users/bret.json"), "{\n  \"username\": \"Bret\",\n  \"email\": \"c@d.io\"\n}\n").unwrap();

        let committed = blame_field(p, "config/dev/users/bret.json", "username").unwrap();
        assert_eq!(committed.commit.unwrap().pull_request, Some(9));
        let edited = blame_field(p, "config/dev/users/bret.json", "email").unwrap();
        assert!(edited.commit.is_none(), "an uncommitted edit has no commit yet");
        assert_eq!(blame_field(p, "config/dev/users/bret.json", "phone").unwrap_err().code, "no_field");
    }

    #[test]
    fn audit_lists_mainline_changes_with_their_targets() {
        let dir = repo();
        let p = dir.path();
        run(p, &["switch", "-q", "-c", "feature"]).unwrap();
        std::fs::create_dir_all(p.join("config/prod/users")).unwrap();
        std::fs::write(p.join("config/prod/users/a.json"), "{}\n").unwrap();
        std::fs::write(p.join("config/dev/users/bret.json"), "{}\n").unwrap();
        run(p, &["add", "-A"]).unwrap();
        run(p, &["commit", "-q", "-m", "Feature work"]).unwrap();
        run(p, &["switch", "-q", "develop"]).unwrap();
        run(p, &["merge", "-q", "--no-ff", "feature", "-m", "Merge pull request #21 from me/feature"]).unwrap();

        let entries = audit(p, 10).unwrap();
        assert_eq!(entries.len(), 2, "the merge and the first commit, not the feature commit itself");
        assert_eq!(entries[0].commit.pull_request, Some(21));
        assert_eq!(entries[0].targets, vec!["dev".to_string(), "prod".to_string()]);
    }

    #[test]
    fn a_folder_that_is_not_a_repository_says_so() {
        let dir = tempfile::tempdir().unwrap();
        assert_eq!(status(dir.path()).unwrap_err().code, "invalid_repo");
    }
}
