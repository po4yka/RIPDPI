use std::env;
use std::path::PathBuf;

use ciadpi_config::{parse_cli, ConfigError, ParseResult, StartupEnv, VERSION};
#[cfg(target_os = "windows")]
use ripdpi_runtime::platform;
use ripdpi_runtime::{process, runtime};

fn help_text() -> String {
    let mut text = String::new();
    text.push_str("    -i, --ip, <ip>            Listening IP, default 0.0.0.0\n");
    text.push_str("    -p, --port <num>          Listening port, default 1080\n");
    #[cfg(not(target_os = "windows"))]
    {
        text.push_str("    -D, --daemon              Daemonize\n");
        text.push_str("    -w, --pidfile <filename>  Write PID to file\n");
    }
    #[cfg(target_os = "linux")]
    {
        text.push_str("    -E, --transparent         Transparent proxy mode\n");
    }
    text.push_str("    -c, --max-conn <count>    Connection count limit, default 512\n");
    text.push_str("    -N, --no-domain           Deny domain resolving\n");
    text.push_str("    -U, --no-udp              Deny UDP association\n");
    text.push_str("    -X                        Disable IPv6 listener and target support\n");
    text.push_str("    -G, --http-connect        Accept HTTP CONNECT proxy requests\n");
    text.push_str("    -I  --conn-ip <ip>        Connection binded IP, default ::\n");
    text.push_str("    -b, --buf-size <size>     Buffer size, default 16384\n");
    text.push_str("    -x, --debug <level>       Print logs, 0, 1 or 2\n");
    text.push_str("    -g, --def-ttl <num>       TTL for all outgoing connections\n");
    #[cfg(target_os = "linux")]
    {
        text.push_str("    -F, --tfo                 Enable TCP Fast Open\n");
    }
    text.push_str("    -A, --auto <t,r,s,n,k,c>  Try desync params after this option\n");
    text.push_str(
        "                              Detect: torst,redirect,ssl_err,none,conn,keep,pri=<num>\n",
    );
    text.push_str("    -L, --auto-mode <modes>   Modes: 0,1,2,3,s,r\n");
    text.push_str(
        "    -T, --timeout <s[:p:c:b]> Timeout waiting for response, after which trigger auto\n",
    );
    text.push_str("    -y, --cache-file <path|-> Dump cache to file or stdout\n");
    text.push_str("    -u, --cache-ttl <sec>     Lifetime of cached desync params for IP\n");
    text.push_str("        --cache-merge <bits>  Merge cache entries by IPv4 prefix bits\n");
    text.push_str("        --host-autolearn      Learn preferred TCP routes per HTTP Host / TLS SNI\n");
    text.push_str("        --host-autolearn-penalty-ttl <sec> Penalty window for failed learned routes\n");
    text.push_str("        --host-autolearn-max-hosts <count> Maximum remembered hosts in the learned store\n");
    text.push_str("        --host-autolearn-file <path> Path to the persisted learned-host store\n");
    text.push_str("    -K, --proto <t,h,u,i>     Protocol whitelist: tls,http,udp,ipv4\n");
    text.push_str("    -H, --hosts <file|:str>   Hosts whitelist, filename or :string\n");
    text.push_str("    -j, --ipset <file|:str>   IP whitelist\n");
    text.push_str("    -V, --pf <port[-portr]>   Ports range whitelist\n");
    text.push_str(
        "    -R, --round <num[-numr]>  Number of request to which desync will be applied\n",
    );
    text.push_str("        --payload-size-range <start-end> Activate group only for matching payload sizes\n");
    text.push_str("        --stream-byte-range <start-end> Activate group only for matching outbound stream bytes\n");
    text.push_str(
        "    -s, --split <pos_t>       Position format: marker[:repeats:skip]\n",
    );
    text.push_str(
        "                              Markers: abs,host,endhost,sld,midsld,endsld,method,extlen,sniext\n",
    );
    text.push_str("                              Also accepts legacy offset[:repeats:skip][+flag1[flag2]] syntax\n");
    text.push_str("    -d, --disorder <pos_t>    Split and send reverse order\n");
    text.push_str("    -o, --oob <pos_t>         Split and send as OOB data\n");
    text.push_str("    -q, --disoob <pos_t>      Split and send reverse order as OOB data\n");
    #[cfg(any(target_os = "linux", target_os = "windows"))]
    {
        text.push_str("    -f, --fake <pos_t>        Split and send fake packet\n");
        #[cfg(target_os = "linux")]
        {
            text.push_str(
                "    -S, --md5sig              Add MD5 Signature option for fake packets\n",
            );
        }
        text.push_str("    -n, --fake-sni <str>      Change SNI in fake\n");
        text.push_str("                              Replaced: ? - rand let, # - rand num, * - rand let/num\n");
    }
    text.push_str("    -t, --ttl <num>           TTL of fake packets, default 8\n");
    text.push_str("    -Z, --wait-send           Wait for TCP send queue before next step\n");
    text.push_str("    -W, --await-int <ms>      Poll interval while waiting for send queue\n");
    text.push_str("    -C, --to-socks5 <ip:port> Chain upstream traffic through SOCKS5\n");
    text.push_str("    -P, --protect-path <path> Route sockets through a protect service\n");
    text.push_str(
        "        --comment <text>      Label the current desync group for cache output\n",
    );
    text.push_str("    -O, --fake-offset <pos_t> Fake data start offset\n");
    text.push_str("    -l, --fake-data <f|:str>  Set custom fake packet\n");
    text.push_str("        --fake-http-profile <id> Built-in HTTP fake payload: compat_default,iana_get,cloudflare_get\n");
    text.push_str("        --fake-tls-profile <id> Built-in TLS fake payload: compat_default,iana_firefox,google_chrome,vk_chrome,sberbank_chrome,rutracker_kyber,bigsize_iana\n");
    text.push_str("        --fake-udp-profile <id> Built-in UDP fake payload: compat_default,zero_256,zero_512,dns_query,stun_binding,wireguard_initiation,dht_get_peers\n");
    text.push_str("    -Q, --fake-tls-mod <flag> Modify fake TLS CH: rand,orig,rndsni,dupsid,padencap,msize=<int>\n");
    text.push_str("    -e, --oob-data <char>     Set custom OOB data\n");
    text.push_str("    -M, --mod-http <h,d,r,m,u> Modify HTTP: hcsmix,dcsmix,rmspace,methodeol,unixeol\n");
    text.push_str("    -r, --tlsrec <pos_t>      Make TLS record at position\n");
    text.push_str("    -m, --tlsminor <ver>      Change minor version of TLS\n");
    text.push_str("    -a, --udp-fake <count>    UDP fakes count, default 0\n");
    text.push_str("        --fake-quic-profile <mode> Fake QUIC payload: disabled,compat_default,realistic_initial\n");
    text.push_str("        --fake-quic-host <domain> Override SNI used by realistic QUIC fake Initials\n");
    #[cfg(target_os = "linux")]
    {
        text.push_str("    -Y, --drop-sack           Drop packets with SACK extension\n");
    }
    text
}

fn canonical_option(option: &str) -> &str {
    match option {
        "--ip" | "-i" => "-i",
        "--port" | "-p" => "-p",
        "--max-conn" | "-c" => "-c",
        "--conn-ip" | "-I" => "-I",
        "--buf-size" | "-b" => "-b",
        "--debug" | "-x" => "-x",
        "--cache-file" | "-y" => "-y",
        "--cache-ttl" | "-u" => "-u",
        "--host-autolearn" => "--host-autolearn",
        "--host-autolearn-penalty-ttl" => "--host-autolearn-penalty-ttl",
        "--host-autolearn-max-hosts" => "--host-autolearn-max-hosts",
        "--host-autolearn-file" => "--host-autolearn-file",
        "--timeout" | "-T" => "-T",
        "--proto" | "-K" => "-K",
        "--hosts" | "-H" => "-H",
        "--ipset" | "-j" => "-j",
        "--split" | "-s" => "-s",
        "--disorder" | "-d" => "-d",
        "--oob" | "-o" => "-o",
        "--disoob" | "-q" => "-q",
        "--fake" | "-f" => "-f",
        "--ttl" | "-t" => "-t",
        "--fake-offset" | "-O" => "-O",
        "--fake-data" | "-l" => "-l",
        "--fake-http-profile" => "--fake-http-profile",
        "--fake-tls-profile" => "--fake-tls-profile",
        "--fake-udp-profile" => "--fake-udp-profile",
        "--fake-tls-mod" | "-Q" => "-Q",
        "--fake-sni" | "-n" => "-n",
        "--oob-data" | "-e" => "-e",
        "--mod-http" | "-M" => "-M",
        "--tlsrec" | "-r" => "-r",
        "--tlsminor" | "-m" => "-m",
        "--udp-fake" | "-a" => "-a",
        "--fake-quic-profile" => "--fake-quic-profile",
        "--fake-quic-host" => "--fake-quic-host",
        "--def-ttl" | "-g" => "-g",
        "--pf" | "-V" => "-V",
        "--round" | "-R" => "-R",
        "--payload-size-range" => "--payload-size-range",
        "--stream-byte-range" => "--stream-byte-range",
        "--auto" | "-A" => "-A",
        "--auto-mode" | "-L" => "-L",
        "--to-socks5" | "-C" => "-C",
        "--cache-merge" => "--cache-merge",
        _ => option,
    }
}

fn print_error(err: &ConfigError) {
    match &err.value {
        Some(value) => eprintln!("invalid value: {} {}", canonical_option(&err.option), value),
        None if err.option.starts_with('-') => {
            eprintln!("ciadpi: unrecognized option `{}`", err.option)
        }
        None => eprintln!("invalid option: {}", err.option),
    }
}

fn startup_env() -> StartupEnv {
    let cwd = env::current_dir().unwrap_or_else(|_| PathBuf::from("."));
    StartupEnv::from_env_and_cwd(&cwd)
}

fn run_args(args: Vec<String>) -> i32 {
    let startup = startup_env();
    let parsed = match parse_cli(&args, &startup) {
        Ok(parsed) => parsed,
        Err(err) => {
            print_error(&err);
            return 1;
        }
    };

    match parsed {
        ParseResult::Help => {
            print!("{}", help_text());
            0
        }
        ParseResult::Version => {
            println!("{VERSION}");
            0
        }
        ParseResult::Run(config) => {
            if env::var_os("CIADPI_RS_DRY_RUN").is_some() {
                return 0;
            }
            let _process = match process::ProcessGuard::prepare(&config) {
                Ok(guard) => guard,
                Err(err) => {
                    eprintln!("ciadpi: {err}");
                    return 1;
                }
            };
            match runtime::run_proxy(config) {
                Ok(()) => 0,
                Err(err) => {
                    eprintln!("ciadpi: {err}");
                    1
                }
            }
        }
    }
}

fn run() -> i32 {
    let args: Vec<String> = env::args().skip(1).collect();

    #[cfg(target_os = "windows")]
    match platform::windows::maybe_run_as_service(&args, run_args) {
        Ok(Some(code)) => return code,
        Ok(None) => {}
        Err(err) => {
            eprintln!("ciadpi: {err}");
            return 1;
        }
    }

    run_args(args)
}

fn main() {
    std::process::exit(run());
}

#[cfg(test)]
mod tests {
    use super::{canonical_option, help_text};

    #[test]
    fn canonical_option_normalizes_known_aliases() {
        assert_eq!(canonical_option("--ip"), "-i");
        assert_eq!(canonical_option("--cache-file"), "-y");
        assert_eq!(canonical_option("--cache-merge"), "--cache-merge");
        assert_eq!(canonical_option("--unknown"), "--unknown");
    }

    #[test]
    fn help_text_mentions_extended_parser_options() {
        let help = help_text();
        for option in [
            "-X",
            "--http-connect",
            "--cache-merge",
            "--wait-send",
            "--await-int",
            "--to-socks5",
            "--protect-path",
            "--comment",
        ] {
            assert!(
                help.contains(option),
                "help text should document parser option {option}"
            );
        }
    }

    #[test]
    fn help_text_describes_full_auto_mode_surface() {
        let help = help_text();
        assert!(help.contains("Modes: 0,1,2,3,s,r"));
    }
}
