/*
 * MicYou — Turns your Android device into a high-quality PC microphone.
 * Copyright (C) 2026 LanRhyme <https://github.com/LanRhyme/MicYou>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version, with the MicYou Plugin Exception.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */

use serde::Deserialize;
use tauri::AppHandle;

#[cfg(target_os = "macos")]
use tauri::{
    menu::{CheckMenuItem, IsMenuItem, Menu, MenuItem, PredefinedMenuItem, Submenu},
    Runtime,
};

/// One node of the app-wide menu the frontend pushes down.
///
/// The descriptor is sent whole every time (never as a patch), which keeps the
/// backend stateless: the frontend owns the labels, the checked state and the
/// behaviour, and only the native menu object lives here.
#[derive(Deserialize, Debug, Clone)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum MenuNode {
    Item {
        id: String,
        label: String,
        /// serde defaults a `bool` to `false`, which would grey every item out,
        /// so the default has to be spelled out.
        #[serde(default = "default_true")]
        enabled: bool,
        #[serde(default)]
        accelerator: Option<String>,
    },
    Check {
        id: String,
        label: String,
        checked: bool,
        #[serde(default = "default_true")]
        enabled: bool,
    },
    Separator,
    Submenu {
        label: String,
        #[serde(default = "default_true")]
        enabled: bool,
        items: Vec<MenuNode>,
    },
    /// A native entry (quit, hide, undo...), resolved against a whitelist.
    Predefined { name: String },
}

const fn default_true() -> bool {
    true
}

/// Native entries the frontend may request by name.
#[cfg(target_os = "macos")]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PredefinedKind {
    Services,
    Hide,
    HideOthers,
    ShowAll,
    Quit,
    Undo,
    Redo,
    Cut,
    Copy,
    Paste,
    SelectAll,
    Minimize,
    Maximize,
    Fullscreen,
    CloseWindow,
}

/// Resolves a descriptor name against the whitelist, ignoring case and separators
/// so the frontend can spell them camelCase, kebab-case or lowercase.
#[cfg(target_os = "macos")]
fn predefined_kind(name: &str) -> Option<PredefinedKind> {
    let normalized = name.to_ascii_lowercase().replace(['-', '_'], "");
    Some(match normalized.as_str() {
        "services" => PredefinedKind::Services,
        "hide" => PredefinedKind::Hide,
        "hideothers" => PredefinedKind::HideOthers,
        "showall" => PredefinedKind::ShowAll,
        "quit" => PredefinedKind::Quit,
        "undo" => PredefinedKind::Undo,
        "redo" => PredefinedKind::Redo,
        "cut" => PredefinedKind::Cut,
        "copy" => PredefinedKind::Copy,
        "paste" => PredefinedKind::Paste,
        "selectall" => PredefinedKind::SelectAll,
        "minimize" => PredefinedKind::Minimize,
        "maximize" => PredefinedKind::Maximize,
        "fullscreen" => PredefinedKind::Fullscreen,
        "closewindow" => PredefinedKind::CloseWindow,
        _ => return None,
    })
}

#[cfg(target_os = "macos")]
fn make_predefined<R: Runtime>(
    app: &AppHandle<R>,
    kind: PredefinedKind,
) -> tauri::Result<Box<dyn IsMenuItem<R>>> {
    let item = match kind {
        PredefinedKind::Services => PredefinedMenuItem::services(app, None)?,
        PredefinedKind::Hide => PredefinedMenuItem::hide(app, None)?,
        PredefinedKind::HideOthers => PredefinedMenuItem::hide_others(app, None)?,
        PredefinedKind::ShowAll => PredefinedMenuItem::show_all(app, None)?,
        PredefinedKind::Quit => PredefinedMenuItem::quit(app, None)?,
        PredefinedKind::Undo => PredefinedMenuItem::undo(app, None)?,
        PredefinedKind::Redo => PredefinedMenuItem::redo(app, None)?,
        PredefinedKind::Cut => PredefinedMenuItem::cut(app, None)?,
        PredefinedKind::Copy => PredefinedMenuItem::copy(app, None)?,
        PredefinedKind::Paste => PredefinedMenuItem::paste(app, None)?,
        PredefinedKind::SelectAll => PredefinedMenuItem::select_all(app, None)?,
        PredefinedKind::Minimize => PredefinedMenuItem::minimize(app, None)?,
        PredefinedKind::Maximize => PredefinedMenuItem::maximize(app, None)?,
        PredefinedKind::Fullscreen => PredefinedMenuItem::fullscreen(app, None)?,
        PredefinedKind::CloseWindow => PredefinedMenuItem::close_window(app, None)?,
    };
    Ok(Box::new(item))
}

#[cfg(target_os = "macos")]
fn build_items<R: Runtime>(
    app: &AppHandle<R>,
    nodes: &[MenuNode],
) -> tauri::Result<Vec<Box<dyn IsMenuItem<R>>>> {
    let mut items: Vec<Box<dyn IsMenuItem<R>>> = Vec::with_capacity(nodes.len());
    for node in nodes {
        match node {
            MenuNode::Item {
                id,
                label,
                enabled,
                accelerator,
            } => {
                let item =
                    MenuItem::with_id(app, id.as_str(), label, *enabled, accelerator.as_deref())?;
                items.push(Box::new(item));
            }
            MenuNode::Check {
                id,
                label,
                checked,
                enabled,
            } => {
                // `enabled` comes before `checked` in this constructor.
                let item =
                    CheckMenuItem::with_id(app, id.as_str(), label, *enabled, *checked, None::<&str>)?;
                items.push(Box::new(item));
            }
            MenuNode::Separator => {
                items.push(Box::new(PredefinedMenuItem::separator(app)?));
            }
            MenuNode::Submenu {
                label,
                enabled,
                items: children,
            } => {
                let built = build_items(app, children)?;
                let refs: Vec<&dyn IsMenuItem<R>> =
                    built.iter().map(|item| item.as_ref()).collect();
                items.push(Box::new(Submenu::with_items(
                    app, label, *enabled, &refs,
                )?));
            }
            // An unknown name is skipped instead of failing the whole menu.
            MenuNode::Predefined { name } => match predefined_kind(name) {
                Some(kind) => items.push(make_predefined(app, kind)?),
                None => log::warn!(target: "menu", "unknown predefined menu entry: {name}"),
            },
        }
    }
    Ok(items)
}

#[cfg(target_os = "macos")]
fn build_menu<R: Runtime>(app: &AppHandle<R>, nodes: &[MenuNode]) -> tauri::Result<Menu<R>> {
    let built = build_items(app, nodes)?;
    let refs: Vec<&dyn IsMenuItem<R>> = built.iter().map(|item| item.as_ref()).collect();
    Menu::with_items(app, &refs)
}

/// Replaces the app-wide menu with the descriptor the frontend sent.
#[cfg(target_os = "macos")]
pub fn apply(app: &AppHandle, nodes: &[MenuNode]) -> Result<(), String> {
    let menu = build_menu(app, nodes).map_err(|e| e.to_string())?;
    app.set_menu(menu).map_err(|e| e.to_string())?;
    Ok(())
}

/// Other platforms keep their own menu handling.
#[cfg(not(target_os = "macos"))]
pub fn apply(_app: &AppHandle, _nodes: &[MenuNode]) -> Result<(), String> {
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn items_parse_and_default_to_enabled() {
        let node: MenuNode =
            serde_json::from_str(r#"{"kind":"item","id":"menu:start","label":"Start"}"#).unwrap();
        match node {
            MenuNode::Item {
                id,
                label,
                enabled,
                accelerator,
            } => {
                assert_eq!(id, "menu:start");
                assert_eq!(label, "Start");
                assert!(enabled, "enabled must default to true");
                assert_eq!(accelerator, None);
            }
            other => panic!("unexpected node: {other:?}"),
        }
    }

    #[test]
    fn explicit_enabled_false_is_kept() {
        let node: MenuNode = serde_json::from_str(
            r#"{"kind":"item","id":"menu:fullscreen","label":"Fullscreen","enabled":false}"#,
        )
        .unwrap();
        match node {
            MenuNode::Item { enabled, .. } => assert!(!enabled),
            other => panic!("unexpected node: {other:?}"),
        }
    }

    #[test]
    fn accelerators_are_read() {
        let node: MenuNode = serde_json::from_str(
            r#"{"kind":"item","id":"menu:settings","label":"Settings","accelerator":"CmdOrCtrl+,"}"#,
        )
        .unwrap();
        match node {
            MenuNode::Item { accelerator, .. } => {
                assert_eq!(accelerator.as_deref(), Some("CmdOrCtrl+,"));
            }
            other => panic!("unexpected node: {other:?}"),
        }
    }

    #[test]
    fn separators_use_the_unit_variant() {
        let node: MenuNode = serde_json::from_str(r#"{"kind":"separator"}"#).unwrap();
        assert!(matches!(node, MenuNode::Separator));
    }

    #[test]
    fn submenus_nest_with_checks_and_separators() {
        let json = r#"{
            "kind": "submenu",
            "label": "View",
            "items": [
                {"kind": "check", "id": "menu:pocket", "label": "Pocket", "checked": true},
                {"kind": "separator"},
                {"kind": "predefined", "name": "fullscreen"}
            ]
        }"#;
        let node: MenuNode = serde_json::from_str(json).unwrap();
        match node {
            MenuNode::Submenu {
                label,
                enabled,
                items,
            } => {
                assert_eq!(label, "View");
                assert!(enabled, "enabled must default to true");
                assert_eq!(items.len(), 3);
                match &items[0] {
                    MenuNode::Check {
                        id,
                        checked,
                        enabled,
                        ..
                    } => {
                        assert_eq!(id, "menu:pocket");
                        assert!(*checked);
                        assert!(*enabled, "enabled must default to true");
                    }
                    other => panic!("unexpected check node: {other:?}"),
                }
                assert!(matches!(items[1], MenuNode::Separator));
                match &items[2] {
                    MenuNode::Predefined { name } => assert_eq!(name, "fullscreen"),
                    other => panic!("unexpected predefined node: {other:?}"),
                }
            }
            other => panic!("unexpected node: {other:?}"),
        }
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn predefined_names_resolve_through_the_whitelist() {
        for name in [
            "services",
            "hide",
            "hideOthers",
            "hide-others",
            "hide_others",
            "showAll",
            "quit",
            "undo",
            "redo",
            "cut",
            "copy",
            "paste",
            "selectAll",
            "minimize",
            "maximize",
            "fullscreen",
            "closeWindow",
        ] {
            assert!(predefined_kind(name).is_some(), "{name} should resolve");
        }
        assert_eq!(predefined_kind("hide_others"), Some(PredefinedKind::HideOthers));
        assert_eq!(predefined_kind("nope"), None);
        assert_eq!(predefined_kind(""), None);
    }
}
