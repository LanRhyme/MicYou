//! TUI theme: reads the GUI-exported theme.json (written by the frontend via
//! save_theme_colors) and falls back to a built-in Morandi-ish palette.

use ratatui::style::Color;

/// RGBA color parsed from a `#rrggbb` hex string.
#[derive(Debug, Clone, Copy)]
pub struct Rgba {
    pub r: u8,
    pub g: u8,
    pub b: u8,
}

impl Rgba {
    pub fn parse(hex: &str) -> Option<Self> {
        let hex = hex.trim().trim_start_matches('#');
        if hex.len() != 6 {
            return None;
        }
        let r = u8::from_str_radix(&hex[0..2], 16).ok()?;
        let g = u8::from_str_radix(&hex[2..4], 16).ok()?;
        let b = u8::from_str_radix(&hex[4..6], 16).ok()?;
        Some(Self { r, g, b })
    }

    pub fn to_color(self) -> Color {
        Color::Rgb(self.r, self.g, self.b)
    }

    /// Blend toward a target color by `t` in [0, 1].
    fn blend(self, other: Self, t: f32) -> Self {
        let f = |a: u8, b: u8| (a as f32 * (1.0 - t) + b as f32 * t).round() as u8;
        Self {
            r: f(self.r, other.r),
            g: f(self.g, other.g),
            b: f(self.b, other.b),
        }
    }
}

/// Theme colors used across the TUI.
#[derive(Debug, Clone, Copy)]
pub struct Theme {
    pub primary: Rgba,
    pub secondary: Rgba,
    pub tertiary: Rgba,
    pub surface: Rgba,
    pub surface_variant: Rgba,
    pub on_surface: Rgba,
    pub error: Rgba,
    /// Spectrum gradient from low to high bins (8 stops).
    pub gradient: [Rgba; 8],
}

/// Built-in fallback palette (warm Morandi tones) used when theme.json is absent.
fn fallback() -> Theme {
    let p = |hex: &str| Rgba::parse(hex).expect("valid fallback hex");
    Theme {
        primary: p("#8d8768"),
        secondary: p("#a09b8f"),
        tertiary: p("#9b8e7c"),
        surface: p("#1e1d1a"),
        surface_variant: p("#2a2824"),
        on_surface: p("#e7e6e4"),
        error: p("#d17a7a"),
        gradient: [
            p("#5f7f76"),
            p("#6f8f7f"),
            p("#7f9e88"),
            p("#8d8768"),
            p("#a0915f"),
            p("#b39b56"),
            p("#c4a556"),
            p("#d4af56"),
        ],
    }
}

/// Load the theme from ~/.config/micyou/theme.json (GUI export) with fallback.
pub fn load() -> Theme {
    let colors = tauri_app_lib::app_config::load_theme_colors();
    let mut theme = fallback();
    let mut any = false;

    if let Some(c) = Rgba::parse(&colors.primary) {
        theme.primary = c;
        any = true;
    }
    if let Some(c) = Rgba::parse(&colors.secondary) {
        theme.secondary = c;
    }
    if let Some(c) = Rgba::parse(&colors.tertiary) {
        theme.tertiary = c;
    }
    if let Some(c) = Rgba::parse(&colors.surface) {
        theme.surface = c;
    }
    if let Some(c) = Rgba::parse(&colors.surface_variant) {
        theme.surface_variant = c;
    }
    if let Some(c) = Rgba::parse(&colors.on_surface) {
        theme.on_surface = c;
    }
    if let Some(c) = Rgba::parse(&colors.error) {
        theme.error = c;
    }

    if any {
        // Derive a cava-style gradient from the primary color: shift hue across
        // blue-ish lows to warm highs, darkening the bottom and brightening the top.
        theme.gradient = derive_gradient(theme.primary, theme.secondary, theme.tertiary);
    }

    theme
}

/// Build an 8-stop spectrum gradient from the theme's accent colors.
fn derive_gradient(primary: Rgba, secondary: Rgba, tertiary: Rgba) -> [Rgba; 8] {
    // Low bins: cooler/darker (secondary shifted toward surface), high bins:
    // warmer/brighter (tertiary/primary). Simple two-stage blend chain.
    let mut out = [Rgba { r: 0, g: 0, b: 0 }; 8];
    for (i, color) in out.iter_mut().enumerate() {
        let t = i as f32 / 7.0;
        let base = if t < 0.5 {
            primary.blend(secondary, t * 2.0)
        } else {
            secondary.blend(tertiary, (t - 0.5) * 2.0)
        };
        // Brighten toward the top
        let bright = base.blend(
            Rgba {
                r: 255,
                g: 255,
                b: 255,
            },
            t * 0.25,
        );
        *color = bright;
    }
    out
}
