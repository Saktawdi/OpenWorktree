// OpenWorktree 桌面壳 dev 垫片（仅本地开发；binaries/*.exe 已 gitignore）。
//
// 本机无 GraalVM，无法产出 native 单文件侧车。垫片顶替 sidecar：spawn
// `java -cp ... gate.web.GateWebApp --config local-run/gate.toml`，把子进程
// stdout/stderr 以字节流泵到自身 stdout/stderr（壳 lib.rs 按行解析
// GATE_WEB_TOKEN / listening port 的逻辑不变）。
//
// 为什么显式泵而不是句柄继承：tauri 以管道作垫片的 stdio 时，.NET/继承路径下
// JVM 的输出到不了壳（实测 java 在写、壳收不到）；而垫片自己写 stdout 壳能收到，
// 所以 java→管道→垫片→壳的显式转发是已被验证可达的路径。
//
// 仓库根定位：tauri dev 会把 externalBin 复制到 src-tauri/target/debug/ 旁拉起，
// release 在 src-tauri/binaries/ —— 向上找带 gate-web/target/dependency 的祖先，
// 或用 OW_DEV_REPO 显式指定。

use std::env;
use std::fs;
use std::io;
use std::io::{BufReader, Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::thread;

/// Windows：把垫片自身进程（连同随后 spawn 的 java 子进程）挂进一个
/// JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE 的 Job——壳退出收割垫片（哪怕是
/// TerminateProcess）时，句柄关闭即触发 Job 清理，java 不会成为孤儿。
/// 纯 kernel32 FFI，避免仅为此引入 windows-sys 依赖。
#[cfg(windows)]
fn assign_self_to_kill_on_close_job() -> bool {
    const JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE: u32 = 0x0000_2000;

    #[link(name = "kernel32")]
    extern "system" {
        fn CreateJobObjectW(lpJobAttributes: *mut core::ffi::c_void, lpName: *const u16)
            -> isize;
        fn SetInformationJobObject(
            hJob: isize,
            info_class: u32, // JOBOBJECTINFOCLASS = JobObjectExtendedLimitInformation(9)
            info: *mut core::ffi::c_void,
            length: u32,
        ) -> i32;
        fn AssignProcessToJobObject(hJob: isize, hProcess: isize) -> i32;
        fn GetCurrentProcess() -> isize;
    }

    unsafe {
        let job = CreateJobObjectW(std::ptr::null_mut(), std::ptr::null());
        if job == 0 {
            return false;
        }
        // JOBOBJECT_EXTENDED_LIMIT_INFORMATION：清零后只写 LimitFlags
        // （偏移 16 = 两个 LARGE_INTEGER 之后）。
        let mut info = [0u8; 144];
        let flags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE.to_ne_bytes();
        info[16..20].copy_from_slice(&flags);
        const JOBOBJECTINFOCLASS_EXTENDED_LIMIT: u32 = 9;
        if SetInformationJobObject(
            job,
            JOBOBJECTINFOCLASS_EXTENDED_LIMIT,
            info.as_mut_ptr() as *mut core::ffi::c_void,
            info.len() as u32,
        ) == 0
        {
            return false;
        }
        if AssignProcessToJobObject(job, GetCurrentProcess()) == 0 {
            return false;
        }
        true
    }
}

#[cfg(not(windows))]
fn assign_self_to_kill_on_close_job() -> bool {
    false
}

fn find_repo(exe: &Path) -> Option<PathBuf> {
    if let Some(r) = env::var_os("OW_DEV_REPO") {
        let p = PathBuf::from(r);
        if p.is_dir() {
            return Some(p);
        }
    }
    let mut dir = exe.parent().map(Path::to_path_buf);
    while let Some(d) = dir {
        if d.join("gate-web/target/dependency").is_dir() {
            return Some(d);
        }
        dir = d.parent().map(Path::to_path_buf);
    }
    None
}

fn pump<R: Read + Send + 'static>(mut src: R, mut dst: Box<dyn Write + Send>) {
    let mut buf = [0u8; 8192];
    loop {
        match src.read(&mut buf) {
            Ok(0) => break,
            Ok(n) => {
                let _ = dst.write_all(&buf[..n]);
                let _ = dst.flush();
            }
            Err(_) => break,
        }
    }
}

fn main() {
    let exe = match env::current_exe() {
        Ok(p) => p,
        Err(e) => {
            eprintln!("ow-shim: cannot resolve exe: {e}");
            std::process::exit(96);
        }
    };
    let repo = match find_repo(&exe) {
        Some(r) => r,
        None => {
            eprintln!("ow-shim: cannot locate repo root (set OW_DEV_REPO)");
            std::process::exit(96);
        }
    };
    let _ = fs::create_dir_all(repo.join("local-run/dev-shim"));
    let trace = repo.join("local-run/dev-shim/ow-shim-trace.log");
    let log = |msg: &str| {
        let _ = fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(&trace)
            .and_then(|mut f| {
                use std::io::Write as _;
                writeln!(f, "{}", msg)
            });
    };
    log("=== rust shim start ===");
    if assign_self_to_kill_on_close_job() {
        log("job object: kill-on-close armed");
    } else {
        log("job object: unavailable (non-windows or call failed)");
    }

    let mut cp = String::new();
    cp.push_str(&repo.join("local-run/ui-dev-root").to_string_lossy());
    cp.push(';');
    for m in [
        "gate-domain",
        "gate-ports",
        "gate-application",
        "gate-adapters",
        "gate-bootstrap",
        "gate-web",
        "gate-cli",
    ] {
        cp.push_str(&repo.join(m).join("target").join("classes").to_string_lossy());
        cp.push(';');
    }
    let dep = repo.join("gate-web/target/dependency");
    if let Ok(entries) = fs::read_dir(&dep) {
        for e in entries.flatten() {
            let name = e.file_name().to_string_lossy().to_string();
            if name.ends_with(".jar") && !name.starts_with("gate-") {
                cp.push_str(&e.path().to_string_lossy());
                cp.push(';');
            }
        }
    }

    let java = env::var("JAVA_HOME")
        .map(|h| PathBuf::from(h).join("bin").join("java.exe"))
        .unwrap_or_else(|_| PathBuf::from("java.exe"));
    let java = if java.exists() { java } else { PathBuf::from("java.exe") };
    log(&format!("java={}", java.display()));

    let cfg = repo.join("local-run/gate.toml");
    let cwd = repo.join("local-run");
    log("spawning jvm...");
    let child = Command::new(&java)
        .arg("-cp")
        .arg(&cp)
        .arg("gate.web.GateWebApp")
        .arg("--config")
        .arg(&cfg)
        .current_dir(&cwd)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn();
    let mut child = match child {
        Ok(c) => c,
        Err(e) => {
            log(&format!("spawn failed: {e}"));
            eprintln!("ow-shim: spawn java failed: {e}");
            std::process::exit(97);
        }
    };
    log(&format!("jvm pid={:?}", child.id()));

    let out = child.stdout.take().unwrap();
    let err = child.stderr.take().unwrap();
    let out_dst: Box<dyn Write + Send> = Box::new(io::stdout());
    let err_dst: Box<dyn Write + Send> = Box::new(io::stderr());
    thread::spawn(move || pump(BufReader::with_capacity(8192, out), out_dst));
    thread::spawn(move || pump(BufReader::with_capacity(8192, err), err_dst));

    match child.wait() {
        Ok(st) => {
            log(&format!("jvm exit={:?}", st.code()));
            std::process::exit(st.code().unwrap_or(1));
        }
        Err(e) => {
            log(&format!("wait failed: {e}"));
            std::process::exit(98);
        }
    }
}
