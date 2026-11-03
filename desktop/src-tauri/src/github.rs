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
    fn errors_explain_what_to_do() {
        let e = error(StatusCode::FORBIDDEN, &Method::GET, "u", r#"{"message":"API rate limit exceeded"}"#, Some("0"), false);
        assert_eq!(e.code, "rate_limited");
        assert!(e.message.contains("Add GITHUB_TOKEN"));
        assert_eq!(error(StatusCode::UNAUTHORIZED, &Method::GET, "u", "{}", None, true).code, "unauthorized");
        assert_eq!(error(StatusCode::NOT_FOUND, &Method::GET, "u", "", None, false).details["status"], 404);
    }
}
