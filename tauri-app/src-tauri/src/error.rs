use serde::Serialize;

#[derive(Debug, Clone, Serialize)]
pub struct AppError {
    pub kind: String,
    pub message: String,
}

impl AppError {
    pub fn network(msg: impl Into<String>) -> Self {
        Self {
            kind: "network".into(),
            message: msg.into(),
        }
    }

    pub fn audio(msg: impl Into<String>) -> Self {
        Self {
            kind: "audio".into(),
            message: msg.into(),
        }
    }

    pub fn platform(msg: impl Into<String>) -> Self {
        Self {
            kind: "platform".into(),
            message: msg.into(),
        }
    }

    pub fn io(msg: impl Into<String>) -> Self {
        Self {
            kind: "io".into(),
            message: msg.into(),
        }
    }

    pub fn server(msg: impl Into<String>) -> Self {
        Self {
            kind: "server".into(),
            message: msg.into(),
        }
    }

    pub fn window(msg: impl Into<String>) -> Self {
        Self {
            kind: "window".into(),
            message: msg.into(),
        }
    }

    pub fn adb(msg: impl Into<String>) -> Self {
        Self {
            kind: "adb".into(),
            message: msg.into(),
        }
    }

    pub fn server_already_running() -> Self {
        Self::server("Server is already running")
    }

    pub fn server_not_running() -> Self {
        Self::server("Server is not running")
    }

    pub fn no_connection() -> Self {
        Self::network("No active connection")
    }

    pub fn other(msg: impl Into<String>) -> Self {
        Self {
            kind: "other".into(),
            message: msg.into(),
        }
    }
}

impl std::fmt::Display for AppError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "[{}] {}", self.kind, self.message)
    }
}

impl std::error::Error for AppError {}

impl From<std::io::Error> for AppError {
    fn from(e: std::io::Error) -> Self {
        Self::io(e.to_string())
    }
}

impl From<String> for AppError {
    fn from(s: String) -> Self {
        Self::other(s)
    }
}

impl From<&str> for AppError {
    fn from(s: &str) -> Self {
        Self::other(s.to_string())
    }
}
