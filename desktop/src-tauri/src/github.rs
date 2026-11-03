//! The app's own GitHub requests: pull requests, checks, workflow runs and drift issues. The engine talks to
//! GitHub for the resources it manages; this is everything around them. Reads work without a token on public
//! repositories (with GitHub's lower rate limit); writes need GITHUB_TOKEN from the keychain.
//!
//! GET responses are cached by ETag, so refreshing a view costs GitHub nothing when nothing changed.

use crate::error::{AppError, Result};
use crate::git::GitHubRepo;
use reqwest::header::{HeaderMap, HeaderValue, ACCEPT, AUTHORIZATION, ETAG, IF_NONE_MATCH, USER_AGENT};
use reqwest::{Method, StatusCode};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::sync::Mutex;
use std::time::Duration;

pub const API: &str = "https://api.github.com";

pub struct GitHub {
    client: reqwest::Client,
    api: String,
    cache: Mutex<HashMap<String, (String, Value)>>,
}

impl GitHub {
    pub fn new(api: &str) -> Result<GitHub> {
        let mut headers = HeaderMap::new();
        headers.insert(ACCEPT, HeaderValue::from_static("application/vnd.github+json"));
        headers.insert("X-GitHub-Api-Version", HeaderValue::from_static("2022-11-28"));
        headers.insert(USER_AGENT, HeaderValue::from_static(concat!("gitops-desktop/", env!("CARGO_PKG_VERSION"))));
        let client = reqwest::Client::builder()
            .default_headers(headers)
            .timeout(Duration::from_secs(30))
            .build()
            .map_err(|e| AppError::new("github", "client", e.to_string()))?;
        Ok(GitHub { client, api: api.trim_end_matches('/').to_string(), cache: Mutex::new(HashMap::new()) })
    }

    async fn send(&self, method: Method, path: &str, token: Option<&str>, body: Option<Value>) -> Result<Value> {
        let url = format!("{}{}", self.api, path);
        let mut request = self.client.request(method.clone(), &url);
        if let Some(token) = token {
            request = request.header(AUTHORIZATION, format!("Bearer {token}"));
        }
        let cached = (method == Method::GET).then(|| self.cache.lock().unwrap().get(&url).cloned()).flatten();
        if let Some((etag, _)) = &cached {
            request = request.header(IF_NONE_MATCH, etag);
        }
        if let Some(body) = body {
            request = request.json(&body);
        }
        let response = request.send().await.map_err(|e| {
            AppError::new("github", "unreachable", format!("Couldn't reach GitHub: {e}"))
                .with_details(json!({"method": method.as_str(), "url": url}))
        })?;
        let status = response.status();
        if status == StatusCode::NOT_MODIFIED {
            if let Some((_, value)) = cached {
                return Ok(value);
            }
        }
        let etag = response.headers().get(ETAG).and_then(|v| v.to_str().ok()).map(str::to_string);
        let remaining = response.headers().get("x-ratelimit-remaining").and_then(|v| v.to_str().ok()).map(str::to_string);
        let text = response.text().await.unwrap_or_default();
        if !status.is_success() {
            return Err(error(status, &method, &url, &text, remaining.as_deref(), token.is_some()));
        }
        let value: Value = if text.trim().is_empty() { Value::Null } else { serde_json::from_str(&text)? };
        if method == Method::GET {
            if let Some(etag) = etag {
                self.cache.lock().unwrap().insert(url, (etag, value.clone()));
            }
        }
        Ok(value)
    }

    pub async fn get(&self, path: &str, token: Option<&str>) -> Result<Value> {
        self.send(Method::GET, path, token, None).await
    }

    pub async fn post(&self, path: &str, token: &str, body: Value) -> Result<Value> {
        self.send(Method::POST, path, Some(token), Some(body)).await
    }

    pub async fn add_labels(&self, repo: &GitHubRepo, token: &str, number: u64, labels: &[String]) -> Result<()> {
        self.post(&format!("/repos/{}/{}/issues/{number}/labels", repo.owner, repo.name), token, json!({"labels": labels})).await?;
        Ok(())
    }

    /// Opens a pull request from `branch` into `base`.
    pub async fn create_pull(&self, repo: &GitHubRepo, token: &str, branch: &str, base: &str, title: &str, body: &str) -> Result<Value> {
        let pull = self
            .post(
                &format!("/repos/{}/{}/pulls", repo.owner, repo.name),
                token,
                json!({"title": title, "head": branch, "base": base, "body": body, "maintainer_can_modify": true}),
            )
            .await?;
        Ok(json!({"number": pull["number"], "url": pull["html_url"]}))
    }
}

/// The marker marocchino/sticky-pull-request-comment puts in the Plan workflow's comment (header gitops-plan).
pub const PLAN_COMMENT_MARKER: &str = "<!-- Sticky Pull Request Commentgitops-plan -->";

impl GitHub {
    fn repo_path(repo: &GitHubRepo, rest: &str) -> String {
        format!("/repos/{}/{}{}", repo.owner, repo.name, rest)
    }

    /// Open pull requests, newest first. Details (files, checks, plan comment) come from `pull_details`.
    pub async fn pull_requests(&self, repo: &GitHubRepo, token: Option<&str>) -> Result<Value> {
        let pulls = self.get(&Self::repo_path(repo, "/pulls?state=open&per_page=50&sort=updated&direction=desc"), token).await?;
        Ok(Value::Array(
            pulls.as_array().cloned().unwrap_or_default().iter().map(|p| json!({
                "number": p["number"],
                "title": p["title"],
                "author": p["user"]["login"],
                "branch": p["head"]["ref"],
                "sha": p["head"]["sha"],
                "base": p["base"]["ref"],
                "draft": p["draft"],
                "url": p["html_url"],
                "updatedAt": p["updated_at"],
            })).collect(),
        ))
    }

    /// The config files a pull request changes, its check runs, and the Plan workflow's comment.
    pub async fn pull_details(&self, repo: &GitHubRepo, token: Option<&str>, number: u64, sha: &str) -> Result<Value> {
        if !sha.chars().all(|c| c.is_ascii_hexdigit()) || sha.len() < 7 {
            return Err(AppError::new("github", "invalid_sha", "Not a commit id"));
        }
        let files_path = Self::repo_path(repo, &format!("/pulls/{number}/files?per_page=100"));
        let checks_path = Self::repo_path(repo, &format!("/commits/{sha}/check-runs?per_page=100"));
        let comments_path = Self::repo_path(repo, &format!("/issues/{number}/comments?per_page=100"));
        let (files, checks, comments) = futures_util::join!(
            self.get(&files_path, token),
            self.get(&checks_path, token),
            self.get(&comments_path, token),
        );
        let files: Vec<Value> = files?.as_array().cloned().unwrap_or_default().iter()
            .map(|f| json!({"path": f["filename"], "status": f["status"], "additions": f["additions"], "deletions": f["deletions"]}))
            .collect();
        let checks: Vec<Value> = checks?["check_runs"].as_array().cloned().unwrap_or_default().iter()
            .map(|c| json!({"name": c["name"], "status": c["status"], "conclusion": c["conclusion"], "url": c["html_url"]}))
            .collect();
        let plan = comments?.as_array().cloned().unwrap_or_default().into_iter().rev()
            .find(|c| c["body"].as_str().is_some_and(|b| b.contains(PLAN_COMMENT_MARKER)))
            .map(|c| json!({
                "body": c["body"].as_str().unwrap_or_default().replace(PLAN_COMMENT_MARKER, "").trim(),
                "updatedAt": c["updated_at"],
                "url": c["html_url"],
                "author": c["user"]["login"],
            }));
        Ok(json!({"files": files, "checks": checks, "planComment": plan}))
    }

    /// Issues labelled drift, open and closed (the Drift workflow opens one per target and closes it when fixed).
    pub async fn drift_issues(&self, repo: &GitHubRepo, token: Option<&str>) -> Result<Value> {
        let issues = self.get(&Self::repo_path(repo, "/issues?labels=drift&state=all&per_page=100&sort=created&direction=desc"), token).await?;
        Ok(Value::Array(
            issues.as_array().cloned().unwrap_or_default().iter().filter(|i| i.get("pull_request").is_none()).map(|i| json!({
                "number": i["number"],
                "title": i["title"],
                "state": i["state"],
                "createdAt": i["created_at"],
                "closedAt": i["closed_at"],
                "comments": i["comments"],
                "url": i["html_url"],
            })).collect(),
        ))
    }

    /// Recent runs of one workflow file, each with its jobs (the Drift workflow has one job per target).
    pub async fn workflow_runs_with_jobs(&self, repo: &GitHubRepo, token: Option<&str>, workflow: &str, limit: usize) -> Result<Value> {
        check_workflow_file(workflow)?;
        let runs = self
            .get(&Self::repo_path(repo, &format!("/actions/workflows/{workflow}/runs?per_page={}", limit.clamp(1, 50))), token)
            .await?;
        let runs = runs["workflow_runs"].as_array().cloned().unwrap_or_default();
        let job_paths: Vec<String> =
            runs.iter().map(|r| Self::repo_path(repo, &format!("/actions/runs/{}/jobs?per_page=100", r["id"]))).collect();
        let jobs = futures_util::future::join_all(job_paths.iter().map(|path| self.get(path, token))).await;
        Ok(Value::Array(
            runs.iter().zip(jobs).map(|(r, jobs)| json!({
                "id": r["id"],
                "event": r["event"],
                "status": r["status"],
                "conclusion": r["conclusion"],
                "branch": r["head_branch"],
                "createdAt": r["created_at"],
                "url": r["html_url"],
                "jobs": jobs.ok().and_then(|j| j["jobs"].as_array().cloned()).unwrap_or_default().iter()
                    .map(|j| json!({"name": j["name"], "status": j["status"], "conclusion": j["conclusion"], "url": j["html_url"]}))
                    .collect::<Vec<_>>(),
            })).collect(),
        ))
    }

    /// The latest runs of every workflow.
    pub async fn recent_runs(&self, repo: &GitHubRepo, token: Option<&str>) -> Result<Value> {
        let runs = self.get(&Self::repo_path(repo, "/actions/runs?per_page=30"), token).await?;
        Ok(Value::Array(
            runs["workflow_runs"].as_array().cloned().unwrap_or_default().iter().map(|r| json!({
                "id": r["id"],
                "workflow": r["name"],
                "path": r["path"],
                "event": r["event"],
                "status": r["status"],
                "conclusion": r["conclusion"],
                "branch": r["head_branch"],
                "title": r["display_title"],
                "createdAt": r["created_at"],
                "url": r["html_url"],
            })).collect(),
        ))
    }

    /// Starts a workflow that has a workflow_dispatch trigger, on a branch.
    pub async fn dispatch(&self, repo: &GitHubRepo, token: &str, workflow: &str, git_ref: &str) -> Result<()> {
        check_workflow_file(workflow)?;
        self.post(&Self::repo_path(repo, &format!("/actions/workflows/{workflow}/dispatches")), token, json!({"ref": git_ref})).await?;
        Ok(())
    }
}

/// A workflow is named by its file name in .github/workflows, nothing else.
pub fn check_workflow_file(name: &str) -> Result<()> {
    let ok = (name.ends_with(".yml") || name.ends_with(".yaml"))
        && name.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '-' | '_' | '.'))
        && !name.starts_with('.');
    if ok { Ok(()) } else { Err(AppError::new("github", "invalid_workflow", format!("'{name}' isn't a workflow file name"))) }
}

/// Workflow files in the repository that can be started by hand (they have a workflow_dispatch trigger).
pub fn dispatchable_workflows(repo: &std::path::Path) -> Vec<Value> {
    let Ok(entries) = std::fs::read_dir(repo.join(".github").join("workflows")) else { return Vec::new() };
    let mut found: Vec<Value> = entries
        .filter_map(|e| e.ok())
        .filter_map(|e| {
            let file = e.file_name().to_string_lossy().into_owned();
            check_workflow_file(&file).ok()?;
            let text = std::fs::read_to_string(e.path()).ok()?;
            if !text.contains("workflow_dispatch") {
                return None;
            }
            let name = text.lines().find_map(|l| l.strip_prefix("name:")).map(|n| n.trim().trim_matches(['\'', '"']).to_string());
            Some(json!({"file": file, "name": name.unwrap_or_else(|| file.clone())}))
        })
        .collect();
    found.sort_by(|a, b| a["file"].as_str().cmp(&b["file"].as_str()));
    found
}

/// Where to open a pull request by hand when the app has no token to open it itself.
pub fn compare_url(repo: &GitHubRepo, base: &str, branch: &str) -> String {
    format!("https://github.com/{}/{}/compare/{}...{}?expand=1", repo.owner, repo.name, base, branch)
}

fn error(status: StatusCode, method: &Method, url: &str, body: &str, remaining: Option<&str>, had_token: bool) -> AppError {
    let message = serde_json::from_str::<Value>(body)
        .ok()
        .and_then(|v| v.get("message").and_then(Value::as_str).map(str::to_string))
        .unwrap_or_else(|| body.chars().take(300).collect());
    let (code, hint) = match status.as_u16() {
        401 => ("unauthorized", "The GitHub token was rejected. Replace it in Settings."),
        403 | 429 if remaining == Some("0") => (
            "rate_limited",
            if had_token { "GitHub's rate limit is used up; try again later." } else { "GitHub's rate limit for requests without a token is used up. Add GITHUB_TOKEN in Settings." },
        ),
        403 => ("forbidden", "The token doesn't allow this. It needs access to this repository."),
        404 => ("not_found", if had_token { "Not found, or the token can't see it." } else { "Not found. Private repositories need GITHUB_TOKEN in Settings." }),
        422 => ("unprocessable", "GitHub refused the request."),
        _ => ("api", "GitHub returned an error."),
    };
    AppError::new("github", code, format!("{hint} ({} {}: {message})", status.as_u16(), method.as_str()))
        .with_details(json!({"status": status.as_u16(), "url": url, "body": message}))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn compare_urls_open_a_pull_request_form() {
        let repo = GitHubRepo { owner: "o".into(), name: "r".into() };
        assert_eq!(compare_url(&repo, "develop", "gitops/x"), "https://github.com/o/r/compare/develop...gitops/x?expand=1");
    }

    #[test]
    fn workflows_are_named_by_file_only() {
        assert!(check_workflow_file("drift.yml").is_ok());
        assert!(check_workflow_file("ci-build.yaml").is_ok());
        for bad in ["../x.yml", "x.yml?ref=1", "drift", ".hidden.yml", "a/b.yml"] {
            assert!(check_workflow_file(bad).is_err(), "{bad}");
        }
    }

    #[test]
    fn finds_workflows_that_can_be_started_by_hand() {
        let dir = tempfile::tempdir().unwrap();
        let workflows = dir.path().join(".github/workflows");
        std::fs::create_dir_all(&workflows).unwrap();
        std::fs::write(workflows.join("drift.yml"), "name: Drift detection\non:\n  workflow_dispatch:\n").unwrap();
        std::fs::write(workflows.join("ci.yml"), "name: CI\non: [pull_request]\n").unwrap();
        let found = dispatchable_workflows(dir.path());
        assert_eq!(found, vec![json!({"file": "drift.yml", "name": "Drift detection"})]);
    }

    #[test]
    fn errors_explain_what_to_do() {
        let e = error(StatusCode::FORBIDDEN, &Method::GET, "u", r#"{"message":"API rate limit exceeded"}"#, Some("0"), false);
        assert_eq!(e.code, "rate_limited");
        assert!(e.message.contains("Add GITHUB_TOKEN"));
        assert_eq!(error(StatusCode::UNAUTHORIZED, &Method::GET, "u", "{}", None, true).code, "unauthorized");
        assert_eq!(error(StatusCode::NOT_FOUND, &Method::GET, "u", "", None, false).details["status"], 404);
    }

    /// Reads this project's own public repository. Needs the network, so it only runs when asked:
    /// cargo test -- --ignored live_github
    #[tokio::test]
    #[ignore]
    async fn live_github_reads_map_to_the_fields_the_ui_uses() {
        let gh = GitHub::new(API).unwrap();
        let repo = GitHubRepo { owner: "emmanuelalozie".into(), name: "gitops".into() };
        let token = std::env::var("GITHUB_TOKEN").ok();
        let runs = gh.recent_runs(&repo, token.as_deref()).await.unwrap();
        let first = &runs.as_array().unwrap()[0];
        assert!(first["workflow"].is_string() && first["url"].as_str().unwrap().starts_with("https://github.com/"), "{first}");
        let drift = gh.workflow_runs_with_jobs(&repo, token.as_deref(), "drift.yml", 2).await.unwrap();
        let jobs = drift[0]["jobs"].as_array().unwrap();
        assert!(jobs.iter().any(|j| j["name"].as_str().unwrap().starts_with("Drift in")), "{jobs:?}");
        assert!(gh.drift_issues(&repo, token.as_deref()).await.unwrap().is_array());
        // A second read is answered from the ETag cache (GitHub replies 304).
        assert_eq!(gh.recent_runs(&repo, token.as_deref()).await.unwrap(), runs);
    }
}
