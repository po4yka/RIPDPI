fn main() {
    if let Err(error) = ripdpi_webtunnel::pt::run_managed_client_from_env() {
        println!("{error}");
        std::process::exit(1);
    }
}
