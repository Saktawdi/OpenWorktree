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

/// 托盘轮询所需的运行期参数（port + token 在后端就绪后才能确定）。
/// None = 后端尚未就绪，轮询线程空转等待。
struct TrayBackend(Mutex<Option<(u16, String)>>);

/// 托盘面板里当前选中项目 id（SPA 回传），与轮询到的项目列表比对画绿点。
struct TraySelectedProject(Mutex<Option<String>>);

/// 托盘面板的渲染数据快照（轮询线程每 5s 刷新 + 变化即推事件）。
struct TraySnapshot(Mutex<serde_json::Value>);

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

/* ─── 托盘 ───
 * 图标 + 自绘面板：右键托盘弹出一个无边框小窗（tray-panel.html，WebRender 渲染，
 * 应用自己的设计风格——原生菜单无法配色、画不了真正的绿点）。面板数据由轮询线程
 * 每 5s 从后端 REST 拉取（Bearer 鉴权，查询串 token 仅 SSE 放行）写入 TraySnapshot，
 * 变化时 emit tray-snapshot 推给面板实时刷新；面板显示期间轮询加密到 2s。
 *
 * 交互：左键单击图标回主窗口；主窗口点 ✕ = 隐藏到托盘（进程与后端常驻，托盘面板
 * 「退出 OpenWorktree」才是唯一出口，走 app.exit → ExitRequested kill 后端）。
 * 点击项目 → emit tray-open-project → 启动页桥接给 iframe SPA → switchProject。
 */

use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::Listener;

const PANEL_W: f64 = 300.0;
const PANEL_ROW_H: f64 = 38.0;
const PANEL_MAX_ROWS: usize = 8;

/// GET /api/agents/busy → { count, running: [...] }。失败（后端未就绪/重启中）返回 None。
fn fetch_busy(port: u16, token: &str) -> Option<u32> {
    let text = http_get(port, token, "/api/agents/busy")?;
    serde_json::from_str::<serde_json::Value>(&text)
        .ok()?
        .get("count")?
        .as_u64()
        .map(|c| c as u32)
}

/// GET /api/projects → { projects: [{ id, name, ... }] }。失败返回空。
fn fetch_projects(port: u16, token: &str) -> Vec<(String, String)> {
    let Some(text) = http_get(port, token, "/api/projects") else {
        return Vec::new();
    };
    serde_json::from_str::<serde_json::Value>(&text)
        .ok()
        .and_then(|v| v.get("projects").cloned())
        .and_then(|p| serde_json::from_value::<Vec<serde_json::Value>>(p).ok())
        .map(|rows| {
            rows.iter()
                .filter_map(|p| {
                    let id = p.get("id")?.as_str()?.to_string();
                    let name = p
                        .get("name")
                        .and_then(|n| n.as_str())
                        .unwrap_or(&id)
                        .to_string();
                    Some((id, name))
                })
                .collect()
        })
        .unwrap_or_default()
}

/// 带鉴权地 GET 后端 REST。非 200 或连接失败 → None。
fn http_get(port: u16, token: &str, path: &str) -> Option<String> {
    let url = format!("http://127.0.0.1:{port}{path}");
    let res = ureq::get(&url)
        .set("Authorization", &format!("Bearer {token}"))
        .timeout(std::time::Duration::from_secs(3))
        .call();
    res.ok()?.into_string().ok()
}

/// 依据快照估算面板高度（页头 + 状态行 + 项目行 + 分隔 + 操作行 + 内边距）。
fn panel_height(snap: &serde_json::Value) -> f64 {
    let projects = snap
        .get("projects")
        .and_then(|p| p.as_array())
        .map(|a| a.len())
        .unwrap_or(0);
    let rows = projects.clamp(0, PANEL_MAX_ROWS) as f64;
    56.0 + 34.0 + 10.0 + rows * PANEL_ROW_H + if projects > 0 { 14.0 } else { 0.0 } + 46.0 + 16.0
}

/// 在托盘图标上方弹出/隐藏自绘面板（已可见则收起）。
/// icon_rect 来自托盘事件（物理像素）；面板逻辑尺寸 × scale_factor 对齐到物理坐标。
fn toggle_tray_panel(app: &tauri::AppHandle, icon_rect: tauri::Rect) {
    let Some(panel) = app.get_webview_window("tray-panel") else {
        return;
    };
    if panel.is_visible().unwrap_or(false) {
        let _ = panel.hide();
        return;
    }
    let snap = app
        .state::<TraySnapshot>()
        .0
        .lock()
        .expect("tray snapshot lock poisoned")
        .clone();
    let scale = panel.scale_factor().unwrap_or(1.0);
    let h = panel_height(&snap).min(520.0);
    let _ = panel.set_size(tauri::LogicalSize::new(PANEL_W, h));

    // 水平：托盘图标中心对齐面板中心；垂直：面板底边贴图标上方留 6px 缝。
    // 全程物理像素，再钳回图标所在显示器可见范围（托盘多在屏幕右下）。
    let scale = scale as f64;
    let pw = PANEL_W * scale;
    let ph = h * scale;
    // Position/Size 是枚举（Physical/Logical），只能解构取值；托盘 rect 恒为物理坐标。
    let (rect_x, rect_y, rect_w) = match (icon_rect.position, icon_rect.size) {
        (tauri::Position::Physical(p), tauri::Size::Physical(s)) => {
            (p.x as f64, p.y as f64, s.width as f64)
        }
        // 兜底：个别平台给逻辑坐标，乘 scale 折算物理
        (tauri::Position::Logical(p), tauri::Size::Logical(s)) => {
            (p.x * scale, p.y * scale, s.width * scale)
        }
        _ => return,
    };
    let cx = rect_x + rect_w / 2.0;
    let bottom = rect_y;
    let (mut x, mut y) = (cx - pw / 2.0, bottom - ph - 6.0);
    if let Ok(Some(m)) = app.monitor_from_point(cx, bottom) {
        let (mx, my) = (m.position().x as f64, m.position().y as f64);
        let (mw, mh) = (m.size().width as f64, m.size().height as f64);
        x = x.clamp(mx + 8.0, (mx + mw - pw - 8.0).max(mx + 8.0));
        y = y.clamp(my + 8.0, (my + mh - ph - 8.0).max(my + 8.0));
    } else {
        x = x.max(8.0);
        y = y.max(8.0);
    }
    let _ = panel.set_position(tauri::PhysicalPosition::new(x, y));
    let _ = panel.show();
    let _ = panel.set_focus();
    // 展示即推一帧最新快照，避免面板里留着上一轮的旧数据
    let _ = panel.emit("tray-snapshot", snap);
}

/// 收起托盘面板（失焦时调用；不存在/不可见时静默）。
fn hide_tray_panel(app: &tauri::AppHandle) {
    if let Some(panel) = app.get_webview_window("tray-panel") {
        if panel.is_visible().unwrap_or(false) {
            let _ = panel.hide();
        }
    }
}

/// 轮询一轮后端，更新 TraySnapshot；有变化且面板可见时推事件给面板刷新。
fn poll_backend_snapshot(handle: &tauri::AppHandle) {
    let backend = handle
        .state::<TrayBackend>()
        .0
        .lock()
        .expect("tray backend lock poisoned")
        .clone();
    let (status, busy, projects) = match &backend {
        None => ("starting".to_string(), None, Vec::new()),
        Some((port, token)) => match fetch_busy(*port, token) {
            Some(0) => ("idle".to_string(), Some(0), fetch_projects(*port, token)),
            Some(n) => ("running".to_string(), Some(n), fetch_projects(*port, token)),
            None => ("offline".to_string(), None, Vec::new()),
        },
    };
    let selected = handle
        .state::<TraySelectedProject>()
        .0
        .lock()
        .expect("tray selected project lock poisoned")
        .clone();
    let snap = serde_json::json!({
        "status": status,
        "busy": busy,
        "selected": selected,
        "projects": projects.iter().map(|(id, name)| serde_json::json!({
            "id": id,
            "name": name,
            "selected": selected.as_deref() == Some(id.as_str()),
        })).collect::<Vec<_>>(),
    });
    let changed = {
        let state = handle.state::<TraySnapshot>();
        let mut g = state.0.lock().expect("tray snapshot lock poisoned");
        let changed = *g != snap;
        *g = snap.clone();
        changed
    };
    if changed {
        if let Some(panel) = handle.get_webview_window("tray-panel") {
            let _ = panel.emit("tray-snapshot", snap);
        }
    }
}

/// 后端就绪后启动的轮询线程：每 5s 对表一次；面板展开期间加密到 2s（轮询本身轻量）。
fn spawn_tray_poller(handle: tauri::AppHandle) {
    std::thread::spawn(move || loop {
        std::thread::sleep(std::time::Duration::from_secs(
            if handle
                .get_webview_window("tray-panel")
                .and_then(|p| p.is_visible().ok())
                .unwrap_or(false)
            {
                2
            } else {
                5
            },
        ));
        poll_backend_snapshot(&handle);
    });
}

/// 托盘图标（窗口右下角常驻）。左键单击回主窗口；右键弹出自绘面板。
fn setup_tray(handle: &tauri::AppHandle) {
    let icon = tauri::image::Image::from_bytes(include_bytes!("../icons/128x128.png"))
        .expect("decode tray icon");
    let _ = TrayIconBuilder::with_id("ow-tray")
        .icon(icon)
        .tooltip("OpenWorktree")
        .show_menu_on_left_click(false)
        .on_tray_icon_event(|tray, event| {
            // Windows：左键单击图标 = 打开主窗口；右键抬起 = toggle 自绘面板。
            if let TrayIconEvent::Click {
                button,
                button_state: MouseButtonState::Up,
                rect,
                ..
            } = event
            {
                match button {
                    MouseButton::Left => show_main(tray.app_handle()),
                    MouseButton::Right => toggle_tray_panel(tray.app_handle(), rect),
                    _ => {}
                }
            }
        })
        .build(handle)
        .expect("build tray icon");
}

/// 主窗口带回前台（单实例回调与托盘共用）。
fn show_main(app: &tauri::AppHandle) {
    if let Some(win) = app.get_webview_window("main") {
        let _ = win.unminimize();
        let _ = win.show();
        let _ = win.set_focus();
    }
}

/* ─── 托盘面板命令（tray-panel.html 经 __TAURI__.core.invoke 调用）── */

/// 面板首帧数据（后续增量靠 tray-snapshot 事件推）。
#[tauri::command]
fn get_tray_state(app: tauri::AppHandle) -> serde_json::Value {
    app.state::<TraySnapshot>()
        .0
        .lock()
        .expect("tray snapshot lock poisoned")
        .clone()
}

#[tauri::command]
fn tray_close_panel(app: tauri::AppHandle) {
    hide_tray_panel(&app);
}

/// 点项目：收面板 → 主窗口带回前台 → 通知启动页桥接给 SPA 切工作台。
#[tauri::command]
fn tray_open_project(app: tauri::AppHandle, project_id: String) {
    hide_tray_panel(&app);
    show_main(&app);
    let _ = app.emit("tray-open-project", project_id);
}

#[tauri::command]
fn tray_open_main(app: tauri::AppHandle) {
    hide_tray_panel(&app);
    show_main(&app);
}

/// 唯一退出出口：ExitRequested 兜底 kill 后端子进程。
#[tauri::command]
fn tray_quit(app: tauri::AppHandle) {
    app.exit(0);
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
        // 托盘面板的操作命令（tauri:// 域的 tray-panel.html 经 invoke 调用）
        .invoke_handler(tauri::generate_handler![
            get_tray_state,
            tray_close_panel,
            tray_open_project,
            tray_open_main,
            tray_quit
        ])
        .setup(|app| {
            let handle = app.handle().clone();
            // 托盘：后端未就绪前面板显示「启动中」；就绪后轮询线程刷新快照。
            // 轮询参数（port/token）由下方 boot 协程抓到后写入 TrayBackend。
            handle.manage(TrayBackend(Mutex::new(None)));
            handle.manage(TraySelectedProject(Mutex::new(None)));
            handle.manage(TraySnapshot(Mutex::new(serde_json::json!({
                "status": "starting", "busy": null, "selected": null, "projects": [],
            }))));
            setup_tray(&handle);
            spawn_tray_poller(handle.clone());

            // 自绘托盘面板：无边框、无任务栏项、点击外部不自动关（由失焦监听收起）。
            // 预建隐藏窗口，右键托盘即弹出——不用临时建窗（首帧白屏）。
            let _ = tauri::WebviewWindowBuilder::new(
                app,
                "tray-panel",
                tauri::WebviewUrl::App("tray-panel.html".into()),
            )
            .title("OpenWorktree Tray")
            .inner_size(PANEL_W, 220.0)
            .visible(false)
            .decorations(false)
            .resizable(false)
            .skip_taskbar(true)
            .always_on_top(true)
            .focused(false)
            .shadow(true)
            .build()?;

            // 主窗口点 ✕ → 隐藏到托盘（进程与后端常驻）；退出只走托盘面板「退出」。
            let main = app.get_webview_window("main").expect("main window");
            let close_handle = handle.clone();
            main.on_window_event(move |event| {
                if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                    api.prevent_close();
                    if let Some(win) = close_handle.get_webview_window("main") {
                        let _ = win.hide();
                    }
                }
            });
            // 面板失焦即收起（含点击托盘图标以外任意处；托盘右键 toggle 会再弹出）。
            let panel = app.get_webview_window("tray-panel").expect("tray panel");
            let blur_handle = handle.clone();
            panel.on_window_event(move |event| {
                if let tauri::WindowEvent::Focused(false) = event {
                    hide_tray_panel(&blur_handle);
                }
            });

            // SPA 选中项目变化 → 启动页桥接转发 → 这里记录，托盘面板画绿点。
            // payload 是 JSON 字符串（去引号后即 project id）；空串表示无选中。
            let sel_handle = handle.clone();
            handle.listen("tray-selected-project", move |event| {
                let id = event.payload().trim_matches('"').to_string();
                *sel_handle
                    .state::<TraySelectedProject>()
                    .0
                    .lock()
                    .expect("tray selected project lock poisoned") =
                    if id.is_empty() { None } else { Some(id) };
                poll_backend_snapshot(&sel_handle);
            });
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
                                // 托盘轮询数据源就绪：REST 鉴权用 HUMAN token（查询串 token 仅 SSE 放行）。
                                *handle
                                    .state::<TrayBackend>()
                                    .0
                                    .lock()
                                    .expect("tray backend lock poisoned") = Some((port, t.clone()));
                                if let Some(tray) = handle.tray_by_id("ow-tray") {
                                    let _ = tray.set_title(Some("OpenWorktree · 运行中"));
                                }
                                // 启动页（tauri:// 域）监听此事件后以 iframe 承载 SPA 并桥接窗口操作
                                let _ = handle.emit(
                                    "backend-ready",
                                    serde_json::json!({ "token": t, "port": port }),
                                );
                            }
                        }
                        CommandEvent::Terminated(status) => {
                            append_log(&boot_log, &format!("backend terminated: {status:?}"));
                            // 托盘菜单随之下线：轮询线程下次醒来会把标题写成「后端离线」。
                            *handle
                                .state::<TrayBackend>()
                                .0
                                .lock()
                                .expect("tray backend lock poisoned") = None;
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
                // try_state：后端从未拉起（端口冲突/秒退）时该状态未 manage，直接
                // state() 会在退出路上 panic。
                if let Some(state) = app.try_state::<BackendChild>() {
                    if let Ok(mut g) = state.0.lock() {
                        if let Some(child) = g.take() {
                            let _ = child.kill();
                        }
                    }
                }
            }
        });
}
