// OpenWorktree 桌面壳：托管后端单文件（ow.exe / ow-linux）并注入登录令牌。
//
// 生命周期：setup 里经 shell 插件以 sidecar 方式拉起后端，同时起一个任务持续读它的
// stdout —— 抓到 "GATE_WEB_TOKEN=<token>" 后把令牌连同端口一起通知前端。前端把令牌
// 写进 sessionStorage 并跳到真正的 SPA（http://127.0.0.1:<port>/）。
//
// 退出：Tauri 退出时由 shell 插件统一收割子进程（RunEvent::ExitRequested 里显式 kill
// 兜底），后端自身的 shutdown hook 随 SIGTERM/kill 生效，不留孤儿进程。
//
// 端口：后端默认 18080；被占时侧车以非零码退出，监听进程退出事件并向用户报错——
// 改端口属于 gate.toml 的职责，壳不代改配置。
use tauri::{Emitter, Manager};
use tauri_plugin_shell::process::CommandEvent;
use tauri_plugin_shell::ShellExt;

const TOKEN_PREFIX: &str = "GATE_WEB_TOKEN=";
const LISTEN_PREFIX: &str = "listening on http://";
const DEFAULT_PORT: u16 = 18080;

/// 后端子进程句柄：spawn 后存入，退出时收割。Mutex<Option<_>> 便于 take() 一次性消费。
struct BackendChild(
    std::sync::Mutex<Option<tauri_plugin_shell::process::CommandChild>>,
);

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_shell::init())
        .setup(|app| {
            let handle = app.handle().clone();
            tauri::async_runtime::spawn(async move {
                let sidecar = match handle.shell().sidecar("ow") {
                    Ok(s) => s,
                    Err(e) => {
                        eprintln!("resolve sidecar ow failed: {e}");
                        let _ = handle.emit("backend-error", "无法定位内置后端 ow（sidecar 缺失）");
                        return;
                    }
                };
                let (mut rx, child) = match sidecar.spawn() {
                    Ok(pair) => pair,
                    Err(e) => {
                        eprintln!("spawn sidecar ow failed: {e}");
                        let _ = handle.emit("backend-error", "内置后端启动失败");
                        return;
                    }
                };
                handle.manage(BackendChild(std::sync::Mutex::new(Some(child))));

                let mut port = DEFAULT_PORT;
                let mut token: Option<String> = None;
                while let Some(event) = rx.recv().await {
                    match event {
                        CommandEvent::Stdout(line) => {
                            let line = String::from_utf8_lossy(&line).to_string();
                            for seg in line.split_whitespace() {
                                if let Some(t) = seg.strip_prefix(TOKEN_PREFIX) {
                                    token = Some(t.to_string());
                                }
                            }
                            if let Some(rest) = line.strip_prefix(LISTEN_PREFIX) {
                                // "listening on http://127.0.0.1:18080/"
                                if let Some(hostport) = rest.split('/').next() {
                                    if let Some(idx) = hostport.rfind(':') {
                                        if let Ok(p) = hostport[idx + 1..].parse::<u16>() {
                                            port = p;
                                        }
                                    }
                                }
                            }
                            if let Some(t) = token.clone() {
                                // 直接导航到真 SPA 并以查询参数携带令牌；SPA boot() 识别
                                // ?ow-token= 完成 connectLive 并立即从地址栏抹掉。
                                let url = format!("http://127.0.0.1:{port}/?ow-token={t}");
                                if let Some(w) = handle.get_webview_window("main") {
                                    let _ = w.eval(&format!("location.replace({url:?})"));
                                }
                                break;
                            }
                        }
                        CommandEvent::Terminated(status) => {
                            let _ = handle.emit(
                                "backend-error",
                                format!("后端进程退出（code={:?}）。若端口 18080 被占用请改 local-run/gate.toml", status.code),
                            );
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
