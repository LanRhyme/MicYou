fn main() -> Result<(), Box<dyn std::error::Error>> {
    // Protobuf code generation
    std::env::set_var("PROTOC", protoc_bin_vendored::protoc_bin_path().unwrap());
    prost_build::compile_protos(&["proto/network.proto"], &["proto/"])?;

    // Tauri build
    tauri_build::build();
    Ok(())
}
