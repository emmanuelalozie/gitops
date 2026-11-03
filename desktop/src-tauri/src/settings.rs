//! The app's own small settings file (recent repositories, which token names are stored). No secrets.

use crate::error::Result;
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

const RECENT: usize = 8;

#[derive(Debug, Default, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct Settings {
    pub recent_repos: Vec<PathBuf>,
    /// The keychain can't list its entries, so the names of stored tokens are kept here.
    pub token_names: Vec<String>,
}

impl Settings {
    pub fn load(file: &Path) -> Settings {
        std::fs::read_to_string(file).ok().and_then(|text| serde_json::from_str(&text).ok()).unwrap_or_default()
    }

    pub fn save(&self, file: &Path) -> Result<()> {
        if let Some(dir) = file.parent() {
            std::fs::create_dir_all(dir)?;
        }
        let temp = file.with_extension("json.tmp");
        std::fs::write(&temp, serde_json::to_string_pretty(self)?)?;
        std::fs::rename(temp, file)?;
        Ok(())
    }

    pub fn remember_repo(&mut self, repo: &Path) {
        self.recent_repos.retain(|r| r != repo);
        self.recent_repos.insert(0, repo.to_path_buf());
        self.recent_repos.truncate(RECENT);
    }

    pub fn remember_token(&mut self, name: &str, stored: bool) {
        self.token_names.retain(|n| n != name);
        if stored {
            self.token_names.push(name.to_string());
            self.token_names.sort();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn recent_repos_are_most_recent_first_without_duplicates() {
        let mut settings = Settings::default();
        settings.remember_repo(Path::new("/a"));
        settings.remember_repo(Path::new("/b"));
        settings.remember_repo(Path::new("/a"));
        assert_eq!(settings.recent_repos, vec![PathBuf::from("/a"), PathBuf::from("/b")]);
    }

    #[test]
    fn saves_and_loads() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("nested/settings.json");
        let mut settings = Settings::default();
        settings.remember_token("GITHUB_TOKEN", true);
        settings.save(&file).unwrap();
        assert_eq!(Settings::load(&file), settings);
        assert_eq!(Settings::load(&dir.path().join("missing.json")), Settings::default());
    }
}
