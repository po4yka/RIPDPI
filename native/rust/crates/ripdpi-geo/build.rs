fn main() -> Result<(), Box<dyn std::error::Error>> {
    let schema_dir = "../../../../core/data/model/src/main/proto";
    let schema = format!("{schema_dir}/geosite.proto");
    println!("cargo:rerun-if-changed={schema}");
    prost_build::Config::new()
        .protoc_executable(protoc_bin_vendored::protoc_bin_path()?)
        .default_package_filename("geosite")
        .compile_protos(&[schema], &[schema_dir])?;
    Ok(())
}
