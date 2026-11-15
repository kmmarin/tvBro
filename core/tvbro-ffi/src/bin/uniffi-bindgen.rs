// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
//! `cargo run -p tvbro-ffi --features bindgen --bin uniffi-bindgen -- generate --library <lib> --language kotlin --out-dir <dir>`

fn main() {
    uniffi::uniffi_bindgen_main()
}
