// OpenWorktree 桌面壳：托管后端单文件（ow.exe / ow-linux）并注入登录令牌。
//
// 生命周期：setup 里经 shell 插件以 sidecar 方式拉起后端，工作目录指向数据目录
// （resolve_data_dir：默认安装目录 data\，工单克隆等大体量数据随安装盘走；
//  NSIS 卸载器 PREUNINSTALL 钩子会询问保留 → %APPDATA%\OpenWorktree\data，
//  重装时 POSTINSTALL 钩子自动搬回）。
// 后端日志经 slf4j-simple 走 STDERR（simpleLogger 未配 logFile），所以 stdout 与
// stderr 都要读：抓到 "GATE_WEB_TOKEN=<token>" 后，WebView 跳转
// http://127.0.0.1:<port>/?ow-token=<token>，SPA 的 boot() 完成自动登录并抹掉参数。
//
// 诊断：后端全部输出镜像到 <数据目录>\backend-boot.log——壳本身无控制台（release 是
// windows_subsystem=windows），黑盒排查就靠这份文件。
//
// 退出：RunEvent::ExitRequested 里显式 kill 子进程（Windows 上 shell 插件不保证随窗口
// 退出回收子进程）。端口冲突：后端以非零码退出，启动页显示原因并指向 gate.toml。
use std::path::{Path, PathBuf};
use std::sync::Mutex;

use tauri::{Emitter, Manager};
use tauri_plugin_shell::process::CommandEvent;
use tauri_plugin_shell::ShellExt;

const TOKEN_PREFIX: &str = "GATE_WEB_TOKEN=";
const LISTEN_PREFIX: &str = "listening on http://";
const DEFAULT_PORT: u16 = 18080;

/// 后端子进程句柄：spawn 后存入，退出时收割。Mutex<Option<_>> 便于 take() 一次性消费。
struct BackendChild(Mutex<Option<tauri_plugin_shell::process::CommandChild>>);

fn append_log(path: &Option<PathBuf>, line: &str) {
    use std::io::Write;
    if let Some(p) = path {
        if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(p) {
            let _ = f.write_all(line.as_bytes());
            let _ = f.write_all(b"\n");
        }
    }
}

/// 目录可写性探针（创建即删）。装进 Program Files 的 perMachine 安装不可写。
fn dir_writable(dir: &Path) -> bool {
    let probe = dir.join(".ow-write-probe");
    match std::fs::write(&probe, b"ok") {
        Ok(()) => {
            let _ = std::fs::remove_file(&probe);
            true
        }
        Err(_) => false,
    }
}

/// 递归复制目录（跨卷搬迁兜底）。符号链接/连接点跳过。
fn copy_dir_recursive(src: &Path, dst: &Path) -> bool {
    if !src.is_dir() || std::fs::create_dir_all(dst).is_err() {
        return false;
    }
    let entries = match std::fs::read_dir(src) {
        Ok(e) => e,
        Err(_) => return false,
    };
    for entry in entries.flatten() {
        let ty = match entry.file_type() {
            Ok(t) => t,
            Err(_) => return false,
        };
        let to = dst.join(entry.file_name());
        let ok = if ty.is_dir() {
            copy_dir_recursive(&entry.path(), &to)
        } else if ty.is_file() {
            std::fs::copy(entry.path(), &to).is_ok()
        } else {
            true
        };
        if !ok {
            return false;
        }
    }
    true
}

/// 把 src 整体搬为 dst：dst 不存在时一步 rename（同卷瞬时）；已存在（空目录）或
/// 跨卷 rename 失败时退化为逐项复制后删除源。任一步失败返回 false 且不删源（不丢数据）。
fn move_dir(src: &Path, dst: &Path) -> bool {
    if !src.is_dir() {
        return false;
    }
    if !dst.exists() && std::fs::rename(src, dst).is_ok() {
        return true;
    }
    let entries = match std::fs::read_dir(src) {
        Ok(e) => e,
        Err(_) => return false,
    };
    for entry in entries.flatten() {
        let to = dst.join(entry.file_name());
        let p = entry.path();
        let moved = if p.is_dir() {
            std::fs::rename(&p, &to).is_ok()
                || (copy_dir_recursive(&p, &to) && std::fs::remove_dir_all(&p).is_ok())
        } else if p.is_file() {
            std::fs::copy(&p, &to).is_ok() && std::fs::remove_file(&p).is_ok()
        } else {
            true
        };
        if !moved {
            return false;
        }
    }
    let _ = std::fs::remove_dir(src);
    true
}

/// 数据目录决策（后端的工作目录，local-run/ 落在其中）：
/// 1. 安装目录 data\ —— 默认。克隆体积持续增长，装在哪个盘由安装时决定，卸载时随
///    安装目录清理（卸载器钩子先询问是否保留）；
/// 2. 上次卸载选「保留」的数据 %APPDATA%\OpenWorktree\data —— POSTINSTALL 钩子会搬回
///    安装目录，rename 失败（跨卷）时由这里接管；
/// 3. 每用户数据目录 %APPDATA%\com.openworktree.desktop —— 安装目录不可写时的兜底
///    （如 perMachine 装进 Program Files），也是旧版数据的位置：release 首次运行且
///    安装目录还没有 local-run 时整体搬入。
/// tauri dev（debug 构建）不做迁移、仍用数据目录兜底路径，避免把真实安装的数据
/// 搬进 target\debug（cargo clean 会清掉）。dev-shim 侧车自带仓库内 local-run 配置，
/// 不受此目录影响。
fn resolve_data_dir(handle: &tauri::AppHandle) -> PathBuf {
    let app_data = handle.path().app_data_dir().ok();
    let kept = std::env::var_os("APPDATA")
        .map(PathBuf::from)
        .map(|d| d.join("OpenWorktree").join("data"));

    if let Ok(exe) = std::env::current_exe() {
        if let Some(install) = exe.parent() {
            let data = install.join("data");
            if data.join("local-run").is_dir() {
                return data;
            }
            if !cfg!(debug_assertions) {
                if let Some(k) = &kept {
                    if k.join("local-run").is_dir() && move_dir(k, &data) {
                        return data;
                    }
                }
                if let Some(l) = &app_data {
                    let legacy = l.join("local-run");
                    if legacy.is_dir() && move_dir(&legacy, &data.join("local-run")) {
                        return data;
                    }
                }
            }
            if std::fs::create_dir_all(&data).is_ok() && dir_writable(&data) {
                return data;
            }
        }
    }
    // 兜底链：保留的数据目录 > 旧版每用户数据目录
    if let Some(k) = &kept {
        if k.join("local-run").is_dir() {
            return k.clone();
        }
    }
    let fallback = app_data.unwrap_or_else(std::env::temp_dir);
    let _ = std::fs::create_dir_all(&fallback);
    fallback
}

/// 300ms 快速探测：端口有监听者即 true（无监听时本机 RST 立即返回，超时仅防火墙丢包场景）。
fn port_in_use(port: u16) -> bool {
    std::net::TcpStream::connect_timeout(
        &std::net::SocketAddr::from(([127, 0, 0, 1], port)),
        std::time::Duration::from_millis(300),
    )
    .is_ok()
}

/// 从数据目录的 local-run/gate.toml 读取 web.port（`port = N` 行；无文件/解析失败回退 None，
/// 调用方兜底 DEFAULT_PORT）。port_range_* 等键不会误匹配：strip_prefix 后必须紧跟「=」。
fn read_configured_port(data_dir: &Path) -> Option<u16> {
    let text = std::fs::read_to_string(data_dir.join("local-run").join("gate.toml")).ok()?;
    for raw in text.lines() {
        let line = raw.split('#').next().unwrap_or("").trim();
        let rest = line.strip_prefix("port")?.trim_start();
        let value = rest.strip_prefix('=')?.trim();
        if let Ok(p) = value.parse::<u16>() {
            return Some(p);
        }
    }
    None
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        // 单实例（必须最先注册）：二次启动时把已有实例的主窗口带回前台，
        // 第二个进程随即退出——不再拉起第二个竞争 18080 的后端。
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            use tauri::Manager;
            if let Some(win) = app.get_webview_window("main") {
                let _ = win.unminimize();
                let _ = win.show();
                let _ = win.set_focus();
            }
        }))
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_shell::init())
        .setup(|app| {
            let handle = app.handle().clone();
            tauri::async_runtime::spawn(async move {
                // 数据目录：安装目录 data\（默认）/ 保留数据 / 每用户兜底，见 resolve_data_dir。
                let data_dir = resolve_data_dir(&handle);
                let boot_log = Some(data_dir.join("backend-boot.log"));
                append_log(&boot_log, "=== backend boot ===");
                append_log(&boot_log, &format!("data dir: {}", data_dir.display()));

                // 端口预检：18080（或 gate.toml 配置的 web.port）已有监听者时直接给出可读提示，
                // 不再拉起必然失败的后端（此前表现为 INTERNAL/code=70 的笼统报错）。
                // 能到这一步说明不是本应用已运行的实例（单实例插件已拦截），多半是残存的
                // 后端孤儿或别的程序占了端口。
                let port = read_configured_port(&data_dir).unwrap_or(DEFAULT_PORT);
                if port_in_use(port) {
                    append_log(&boot_log, &format!("port {port} already in use before spawn"));
                    let _ = handle.emit(
                        "backend-error",
                        format!(
                            "端口 {port} 已被占用（可能是未完全退出的 OpenWorktree 后端）。请关闭它或稍后重试；也可改 {dir}\\local-run\\gate.toml 的 web.port 换端口。",
                            dir = data_dir.display()
                        ),
                    );
                    return;
                }

                let sidecar = match handle.shell().sidecar("ow") {
                    Ok(s) => s,
                    Err(e) => {
                        append_log(&boot_log, &format!("resolve sidecar failed: {e}"));
                        let _ = handle.emit("backend-error", "无法定位内置后端 ow（sidecar 缺失）");
                        return;
                    }
                };
                let cmd = sidecar.current_dir(&data_dir);
                let (mut rx, child) = match cmd.spawn() {
                    Ok(pair) => pair,
                    Err(e) => {
                        append_log(&boot_log, &format!("spawn sidecar failed: {e}"));
                        let _ = handle.emit("backend-error", "内置后端启动失败");
                        return;
                    }
                };
                handle.manage(BackendChild(Mutex::new(Some(child))));

                let mut port = DEFAULT_PORT;
                let mut token: Option<String> = None;
                let mut navigated = false;
                while let Some(event) = rx.recv().await {
                    match event {
                        // 后端日志走 stderr（slf4j-simple 默认），stdout 也要读：兜配置变化。
                        CommandEvent::Stdout(line) | CommandEvent::Stderr(line) => {
                            let text = String::from_utf8_lossy(&line).to_string();
                            append_log(&boot_log, &text);
                            for seg in text.split_whitespace() {
                                if let Some(t) = seg.strip_prefix(TOKEN_PREFIX) {
                                    token = Some(t.to_string());
                                }
                            }
                            // 行带 "[main] INFO GateWebApp - " 前缀，必须子串查找。
                            if let Some(idx) = text.find(LISTEN_PREFIX) {
                                let rest = &text[idx + LISTEN_PREFIX.len()..];
                                if let Some(hostport) = rest.split('/').next() {
                                    if let Some(i) = hostport.rfind(':') {
                                        if let Ok(p) = hostport[i + 1..].parse::<u16>() {
                                            port = p;
                                        }
                                    }
                                }
                            }
                            if let (Some(t), false) = (token.clone(), navigated) {
                                navigated = true;
                                append_log(&boot_log, &format!("backend ready on port {port}"));
                                // 启动页（tauri:// 域）监听此事件后以 iframe 承载 SPA 并桥接窗口操作
                                let _ = handle.emit(
                                    "backend-ready",
                                    serde_json::json!({ "token": t, "port": port }),
                                );
                            }
                        }
                        CommandEvent::Terminated(status) => {
                            append_log(&boot_log, &format!("backend terminated: {status:?}"));
                            if !navigated {
                                let _ = handle.emit(
                                    "backend-error",
                                    format!(
                                        "后端进程退出（code={:?}）。端口 18080 被占用时请改数据目录下 local-run/gate.toml（见 backend-boot.log 的 data dir 行）",
                                        status.code
                                    ),
                                );
                            }
                            return;
                        }
                        _ => {}
                    }
                }
            });
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("error while building tauri application")
        .run(|app, event| {
            if let tauri::RunEvent::ExitRequested { .. } = event {
                // 兜底收割：Windows 上 shell 插件不保证随窗口退出回收子进程，显式 kill。
                if let Some(child) = app
                    .state::<BackendChild>()
                    .0
                    .lock()
                    .expect("backend child lock poisoned")
                    .take()
                {
                    let _ = child.kill();
                }
            }
        });
}
