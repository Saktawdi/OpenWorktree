// OpenWorktree 桌面壳：托管后端单文件（ow.exe / ow-linux）并注入登录令牌。
//
// 生命周期：setup 里经 shell 插件以 sidecar 方式拉起后端，工作目录指向每用户数据目录
// （local-run/ 的 gate.toml、SQLite、令牌、工单克隆都落在那里，不混进安装目录）。
// 后端日志经 slf4j-simple 走 STDERR（simpleLogger 未配 logFile），所以 stdout 与
// stderr 都要读：抓到 "GATE_WEB_TOKEN=<token>" 后，WebView 跳转
// http://127.0.0.1:<port>/?ow-token=<token>，SPA 的 boot() 完成自动登录并抹掉参数。
//
// 诊断：后端全部输出镜像到 <app_data>/backend-boot.log——壳本身无控制台（release 是
// windows_subsystem=windows），黑盒排查就靠这份文件。
//
// 退出：RunEvent::ExitRequested 里显式 kill 子进程（Windows 上 shell 插件不保证随窗口
// 退出回收子进程）。端口冲突：后端以非零码退出，启动页显示原因并指向 gate.toml。
use std::path::PathBuf;
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

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_shell::init())
        .setup(|app| {
            let handle = app.handle().clone();
            tauri::async_runtime::spawn(async move {
                // 数据目录：local-run/（gate.toml、db、令牌、克隆）统一落在每用户数据目录。
                let data_dir = handle.path().app_data_dir().ok();
                if let Some(d) = &data_dir {
                    let _ = std::fs::create_dir_all(d);
                }
                let boot_log = data_dir.as_ref().map(|d| d.join("backend-boot.log"));
                append_log(&boot_log, "=== backend boot ===");

                let sidecar = match handle.shell().sidecar("ow") {
                    Ok(s) => s,
                    Err(e) => {
                        append_log(&boot_log, &format!("resolve sidecar failed: {e}"));
                        let _ = handle.emit("backend-error", "无法定位内置后端 ow（sidecar 缺失）");
                        return;
                    }
                };
                let mut cmd = sidecar;
                if let Some(d) = &data_dir {
                    cmd = cmd.current_dir(d);
                }
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
                                        "后端进程退出（code={:?}）。端口 18080 被占用时请改数据目录下 local-run/gate.toml",
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
