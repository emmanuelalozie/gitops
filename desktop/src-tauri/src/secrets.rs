//! API tokens in the OS keychain. The webview can set, clear and ask whether a token is present, but never read
//! one back: tokens only leave the keychain as environment variables of the engine process and as the
//! Authorization header of this app's own GitHub requests.

use crate::error::{AppError, Result};

const SERVICE: &str = "gitops-desktop";

/// Token names are environment variable names, as `tokenEnv` in target.json uses them.
pub fn valid_name(name: &str) -> bool {
    let mut chars = name.chars();
    matches!(chars.next(), Some(c) if c.is_ascii_uppercase())
        && name.len() <= 64
        && chars.all(|c| c.is_ascii_uppercase() || c.is_ascii_digit() || c == '_')
}

fn entry(name: &str) -> Result<keyring::Entry> {
    if !valid_name(name) {
        return Err(AppError::new("keychain", "invalid_name", format!("'{name}' isn't a valid token name (A-Z, 0-9, _)")));
    }
    keyring::Entry::new(SERVICE, name).map_err(keychain)
}

fn keychain(e: keyring::Error) -> AppError {
    AppError::new("keychain", "keychain", format!("The system keychain refused: {e}"))
}

pub fn get(name: &str) -> Result<Option<String>> {
    match entry(name)?.get_password() {
        Ok(value) => Ok(Some(value)),
        Err(keyring::Error::NoEntry) => Ok(None),
        Err(e) => Err(keychain(e)),
    }
}

pub fn set(name: &str, value: &str) -> Result<()> {
    let value = value.trim();
    if value.is_empty() {
        return Err(AppError::new("keychain", "empty", "The token is empty"));
    }
    entry(name)?.set_password(value).map_err(keychain)
}

pub fn delete(name: &str) -> Result<()> {
    match entry(name)?.delete_credential() {
        Ok(()) | Err(keyring::Error::NoEntry) => Ok(()),
        Err(e) => Err(keychain(e)),
    }
}

#[cfg(test)]
mod tests {
    use super::valid_name;

    #[test]
    fn names_are_environment_variable_names() {
        assert!(valid_name("GITHUB_TOKEN"));
        assert!(valid_name("GITOPS_ADMIN_TOKEN"));
        assert!(!valid_name("github_token"));
        assert!(!valid_name("1TOKEN"));
        assert!(!valid_name("A-B"));
        assert!(!valid_name(""));
    }
}
