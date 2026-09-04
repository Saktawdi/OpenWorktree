//! 开发用 sidecar 垫片：以 JVM 方式拉起 gate-web 后端，替代需要 GraalVM 流水线产出的
//! native 单文件（binaries/ow-{triple}.exe 的本地占位）。桌面壳只关心三件事——子进程的
//! stdout/stderr、进程存活、退出码——垫片原样透传，壳的自动登录流程照常工作。
//!
//! 仓库根按 exe 相对位置推断（binaries → src-tauri → desktop → 根），也可用
//! OW_SIDECAR_REPO_ROOT 覆盖。java 取 JAVA_HOME\bin\java.exe，否则 PATH 上的 java。
//!
//! 孤儿保护：java 挂进 KILL_ON_JOB_CLOSE 的作业对象——壳退出收割垫片（TerminateProcess）
//! 时内核连带结束 java，不会留下占着端口的孤儿后端。
//!
//! 仅 dev 可用：垫片离开仓库根（如被打进安装包）就定位不到 classpath，此时显式报错退出，
//! 提示换回 native 单文件。

use std::os::windows::io::AsRawHandle;
use std::path::PathBuf;
use std::process::{Command, ExitCode};

fn main() -> ExitCode {
    // 类路径顺序即优先级：静态目录垫最前（/static/index.html 服务桌面壳的 SPA）；
    // 模块 classes；最后运行期依赖 jar（通配符，剔除测试家族已由 copy-dependencies 保证）。
    // 仓库根：OW_SIDECAR_REPO_ROOT 优先；否则从 exe 所在目录逐级向上找含
    // gate-web/target/classes 的目录。不固定层数——tauri dev 直跑 binaries/ 时是
    // 仓库根下 3 层，sidecar 被复制到 target/{debug,release}/ 时是 5 层，逐级探测通吃。
    let repo = match std::env::var("OW_SIDECAR_REPO_ROOT") {
        Ok(v) if !v.trim().is_empty() => Some(PathBuf::from(v.trim())),
        _ => std::env::current_exe()
            .ok()
            .as_ref()
            .and_then(|exe| exe.parent())
            .and_then(|dir| {
                let mut probe = dir.to_path_buf();
                for _ in 0..6 {
                    if probe.join("gate-web/target/classes").is_dir() {
                        return Some(probe);
                    }
                    probe = probe.parent()?.to_path_buf();
                }
                None
            }),
    };
    let Some(repo) = repo else {
        return fail("无法定位仓库根（设 OW_SIDECAR_REPO_ROOT=<仓库根> 后重试）；打包安装包必须使用 native 单文件，不能用本垫片");
    };

    let mut cp: Vec<String> = Vec::new();
    let static_overlay = repo.join("gate-web-ui/.sidecar-classpath");
    if static_overlay.join("static/index.html").is_file() {
        cp.push(static_overlay.to_string_lossy().into_owned());
    } else {
        // 缺它后端照常起，但桌面壳里是 404 白屏——宁可在启动日志里喊出来
        println!(
            "[sidecar-jvm-shim] 警告：缺少静态目录 {}（cd gate-web-ui && cmd //c \"mklink //J .sidecar-classpath\\static dist\"），桌面壳将拿不到 SPA",
            static_overlay.display()
        );
    }
    for m in [
        "gate-web",
        "gate-adapters",
        "gate-application",
        "gate-ports",
        "gate-domain",
        "gate-bootstrap",
    ] {
        cp.push(repo.join(format!("{m}/target/classes")).to_string_lossy().into_owned());
    }
    cp.push(format!("{}\\*", repo.join("gate-web/target/dependency").to_string_lossy()));

    let java = match std::env::var("JAVA_HOME") {
        Ok(h) if !h.trim().is_empty() => format!("{}\\bin\\java.exe", h.trim()),
        _ => "java".to_string(),
    };

    println!("[sidecar-jvm-shim] repo = {}", repo.display());
    println!("[sidecar-jvm-shim] java = {java}");
    println!("[sidecar-jvm-shim] 启动 JVM 后端 gate.web.GateWebApp（参数原样透传）");

    let mut cmd = Command::new(&java);
    cmd.arg("-Dfile.encoding=UTF-8")
        .arg("-cp")
        .arg(cp.join(";"))
        .arg("gate.web.GateWebApp")
        // 透传（如 --config <path>）跟在主类之后，落到 GateWebApp 的参数解析里
        .args(std::env::args().skip(1));

    let mut child = match cmd.spawn() {
        Ok(c) => c,
        Err(e) => return fail(&format!("java 启动失败（{java}）：{e}")),
    };

    // KILL_ON_JOB_CLOSE：壳退出收割垫片（TerminateProcess）时，作业对象句柄随进程销毁
    // 关闭，内核连带结束 java——不留占端口的孤儿后端。失败仅退化为可能留孤儿，不阻塞启动。
    let mut info = win32job::ExtendedLimitInfo::new();
    info.limit_kill_on_job_close();
    if let Ok(job) = win32job::Job::create_with_limit_info(&info) {
        let _ = job.assign_process(child.as_raw_handle() as isize);
        std::mem::forget(job); // 句柄要活到垫片进程死亡才触发 kill-on-close，不能被 Drop 提前关掉
    }

    match child.wait() {
        Ok(status) => ExitCode::from(status.code().unwrap_or(1) as u8),
        Err(e) => fail(&format!("等待后端退出失败：{e}")),
    }
}

/** stdout + stderr 都打：桌面壳两侧都读（后端日志走 stderr），诊断不能只落一边。 */
fn fail(msg: &str) -> ExitCode {
    println!("[sidecar-jvm-shim] {msg}");
    eprintln!("[sidecar-jvm-shim] {msg}");
    ExitCode::from(70)
}
