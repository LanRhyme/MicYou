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

#[cfg(target_os = "linux")]
pub mod linux_layer_shell {
    use std::ffi::{c_char, c_int, c_void, CString};
    use gtk::prelude::*;
    use glib::translate::ToGlibPtr;

    pub const GTK_LAYER_SHELL_LAYER_OVERLAY: c_int = 3;
    pub const GTK_LAYER_SHELL_EDGE_LEFT: c_int = 0;
    pub const GTK_LAYER_SHELL_EDGE_RIGHT: c_int = 1;
    pub const GTK_LAYER_SHELL_EDGE_TOP: c_int = 2;
    pub const GTK_LAYER_SHELL_EDGE_BOTTOM: c_int = 3;
    pub const GTK_LAYER_SHELL_KEYBOARD_MODE_NONE: c_int = 0;

    type IsSupportedFn = unsafe extern "C" fn() -> c_int;
    type InitForWindowFn = unsafe extern "C" fn(*mut c_void);
    type SetLayerFn = unsafe extern "C" fn(*mut c_void, c_int);
    type SetKeyboardModeFn = unsafe extern "C" fn(*mut c_void, c_int);
    type SetNamespaceFn = unsafe extern "C" fn(*mut c_void, *const c_char);
    type SetAnchorFn = unsafe extern "C" fn(*mut c_void, c_int, c_int);
    type SetMarginFn = unsafe extern "C" fn(*mut c_void, c_int, c_int);

    pub fn setup_layer_shell(
        gtk_win: &gtk::ApplicationWindow,
        margin_top: i32,
        margin_left: i32,
    ) -> bool {
        let lib_name = CString::new("libgtk-layer-shell.so.0").unwrap();
        let handle = unsafe { libc::dlopen(lib_name.as_ptr(), libc::RTLD_LAZY | libc::RTLD_LOCAL) };
        if handle.is_null() {
            log::info!(target: "layer_shell", "libgtk-layer-shell.so.0 not found; falling back to standard window");
            return false;
        }

        let is_supported_sym = CString::new("gtk_layer_is_supported").unwrap();
        let is_supported: Option<IsSupportedFn> = unsafe { std::mem::transmute(libc::dlsym(handle, is_supported_sym.as_ptr())) };

        if let Some(is_supported_fn) = is_supported {
            if unsafe { is_supported_fn() } == 0 {
                log::info!(target: "layer_shell", "gtk-layer-shell is not supported by current compositor");
                return false;
            }
        } else {
            return false;
        }

        let init_sym = CString::new("gtk_layer_init_for_window").unwrap();
        let set_layer_sym = CString::new("gtk_layer_set_layer").unwrap();
        let set_kb_sym = CString::new("gtk_layer_set_keyboard_mode").unwrap();
        let set_ns_sym = CString::new("gtk_layer_set_namespace").unwrap();
        let set_anchor_sym = CString::new("gtk_layer_set_anchor").unwrap();
        let set_margin_sym = CString::new("gtk_layer_set_margin").unwrap();

        let init_fn: Option<InitForWindowFn> = unsafe { std::mem::transmute(libc::dlsym(handle, init_sym.as_ptr())) };
        let set_layer_fn: Option<SetLayerFn> = unsafe { std::mem::transmute(libc::dlsym(handle, set_layer_sym.as_ptr())) };
        let set_kb_fn: Option<SetKeyboardModeFn> = unsafe { std::mem::transmute(libc::dlsym(handle, set_kb_sym.as_ptr())) };
        let set_ns_fn: Option<SetNamespaceFn> = unsafe { std::mem::transmute(libc::dlsym(handle, set_ns_sym.as_ptr())) };
        let set_anchor_fn: Option<SetAnchorFn> = unsafe { std::mem::transmute(libc::dlsym(handle, set_anchor_sym.as_ptr())) };
        let set_margin_fn: Option<SetMarginFn> = unsafe { std::mem::transmute(libc::dlsym(handle, set_margin_sym.as_ptr())) };

        if let (
            Some(init),
            Some(set_layer),
            Some(set_kb),
            Some(set_ns),
            Some(set_anchor),
            Some(set_margin),
        ) = (
            init_fn,
            set_layer_fn,
            set_kb_fn,
            set_ns_fn,
            set_anchor_fn,
            set_margin_fn,
        ) {
            gtk_win.set_size_request(36, 36);
            gtk_win.set_app_paintable(true);
            if let Some(screen) = gtk::prelude::WidgetExt::screen(gtk_win) {
                if let Some(visual) = screen.rgba_visual() {
                    gtk_win.set_visual(Some(&visual));
                }
            }

            let raw_ptr: *mut gtk::ffi::GtkApplicationWindow = gtk_win.to_glib_none().0;
            let ptr = raw_ptr as *mut c_void;

            unsafe {
                init(ptr);
                set_layer(ptr, GTK_LAYER_SHELL_LAYER_OVERLAY);
                set_kb(ptr, GTK_LAYER_SHELL_KEYBOARD_MODE_NONE);
                let ns = CString::new("micyou-overlay").unwrap();
                set_ns(ptr, ns.as_ptr());
                set_anchor(ptr, GTK_LAYER_SHELL_EDGE_TOP, 1);
                set_anchor(ptr, GTK_LAYER_SHELL_EDGE_LEFT, 1);
                set_margin(ptr, GTK_LAYER_SHELL_EDGE_TOP, margin_top as c_int);
                set_margin(ptr, GTK_LAYER_SHELL_EDGE_LEFT, margin_left as c_int);
            }

            gtk_win.queue_resize();
            gtk_win.show_all();
            log::info!(target: "layer_shell", "Successfully initialized gtk-layer-shell on overlay window");
            return true;
        }

        false
    }

    pub fn update_layer_shell_margin(
        gtk_win: &gtk::ApplicationWindow,
        margin_top: i32,
        margin_left: i32,
    ) -> bool {
        let lib_name = CString::new("libgtk-layer-shell.so.0").unwrap();
        let handle = unsafe { libc::dlopen(lib_name.as_ptr(), libc::RTLD_LAZY | libc::RTLD_LOCAL) };
        if handle.is_null() {
            return false;
        }

        let set_margin_sym = CString::new("gtk_layer_set_margin").unwrap();
        let set_margin_fn: Option<SetMarginFn> =
            unsafe { std::mem::transmute(libc::dlsym(handle, set_margin_sym.as_ptr())) };

        if let Some(set_margin) = set_margin_fn {
            let raw_ptr: *mut gtk::ffi::GtkApplicationWindow = gtk_win.to_glib_none().0;
            let ptr = raw_ptr as *mut c_void;
            unsafe {
                set_margin(ptr, GTK_LAYER_SHELL_EDGE_TOP, margin_top as c_int);
                set_margin(ptr, GTK_LAYER_SHELL_EDGE_LEFT, margin_left as c_int);
            }
            gtk_win.queue_resize();
            return true;
        }

        false
    }
}
