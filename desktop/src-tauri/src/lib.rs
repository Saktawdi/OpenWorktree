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

/// 托盘菜单里当前选中项目 id（SPA 回传），与轮询到的项目列表比对画绿点。
struct TraySelectedProject(Mutex<Option<String>>);

/// 上一次托盘菜单快照签名：轮询周期内数据没变就不重建，避免用户正开着菜单被 set_menu 打断。
struct TrayMenuSig(Mutex<String>);

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
 * 右键菜单：Agent 运行数（禁用态标题）+ 接入项目列表（绿点 = SPA 当前选中的工作台）
 * + 打开主窗口 + 退出。数据每 5s 轮询后端 REST（Bearer 鉴权，与浏览器同源不同路：
 * 查询 token 仅 SSE 放行），菜单整体重建——条目数极少，开销可忽略。
 * 点击项目 → emit tray-open-project → 启动页桥接给 iframe SPA → switchProject。
 */

use tauri::menu::{Menu, MenuItem, PredefinedMenuItem};
use tauri::tray::TrayIconBuilder;

/// 项目名截断：托盘菜单宽度有限，超长 workspace 名收尾加省略号。
fn truncate_label(s: &str, max: usize) -> String {
    if s.chars().count() <= max {
        s.to_string()
    } else {
        let cut: String = s.chars().take(max.saturating_sub(1)).collect();
        format!("{}…", cut)
    }
}

/// GET /api/agents/busy → { count, running: [...] }。失败（后端未就绪/重启中）返回 None，
/// 调用方按 0 处理但标题写「离线」。
fn fetch_busy(port: u16, token: &str) -> Option<u32> {
    let text = http_get(
        port,
        token,
        "/api/agents/busy",
    )?;
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
    // 非 2xx（含 401：后端重启后 token 轮换，等壳下轮重新对表——token 其实不变，
    // 这里只是防御）也走 None，调用方按离线降级。
    res.ok()?.into_string().ok()
}

/// 14×14 绿色圆点（#22c55e）：托盘菜单里标记「当前选中的工作台」。
/// 运行期合成 RGBA，无需资源文件；IconMenuItem 在 Windows 上原生渲染位图。
fn green_dot_icon() -> tauri::image::Image<'static> {
    const S: usize = 14;
    let mut rgba = vec![0u8; S * S * 4];
    let c = (S as f32 - 1.0) / 2.0;
    let r = 5.0f32;
    for y in 0..S {
        for x in 0..S {
            let d = ((x as f32 - c).powi(2) + (y as f32 - c).powi(2)).sqrt();
            // 半径内实心，边缘一圈按距离简易抗锯齿
            let alpha = if d <= r - 1.0 {
                255u8
            } else if d <= r {
                ((r - d) * 255.0) as u8
            } else {
                0
            };
            let i = (y * S + x) * 4;
            rgba[i] = 0x22;
            rgba[i + 1] = 0xc5;
            rgba[i + 2] = 0x5e;
            rgba[i + 3] = alpha;
        }
    }
    tauri::image::Image::new_owned(rgba, S as u32, S as u32)
}

/// 按当前快照整体重建托盘菜单。重建前必须 set_menu 换新再 drop 旧的，避免悬空。
fn rebuild_tray_menu(handle: &tauri::AppHandle) {
    let busy = handle.state::<TrayBackend>();
    let selected = handle.state::<TraySelectedProject>();
    let backend = busy.0.lock().expect("tray backend lock poisoned").clone();
    let sel = selected
        .0
        .lock()
        .expect("tray selected project lock poisoned")
        .clone();

    let status_text = match &backend {
        None => "后端启动中…".to_string(),
        Some((port, token)) => match fetch_busy(*port, token) {
            Some(0) => "智能体空闲".to_string(),
            Some(n) => format!("Agent 运行中 × {n}"),
            None => format!("后端离线（:{port}）"),
        },
    };

    let mut items: Vec<tauri::menu::IconMenuItem<tauri::Wry>> = Vec::new();
    let mut projects: Vec<(String, String)> = Vec::new();

    if let Some((port, token)) = &backend {
        projects = fetch_projects(*port, token);
        for (id, name) in &projects {
            let label = truncate_label(name, 36);
            let selected = sel.as_deref() == Some(id.as_str());
            // 选中项带绿色圆点图标（绿色圆点 = SPA 当前选中的工作台）；未选中不带图标
            let item = tauri::menu::IconMenuItem::with_id(
                handle,
                format!("project:{id}"),
                label,
                true,
                if selected { Some(green_dot_icon()) } else { None },
                None::<String>,
            )
            .expect("icon menu item");
            items.push(item);
        }
    }

    // 快照签名：状态行 + 项目列表 + 选中项。没变化直接跳过（不 set_menu，不打断展开中的菜单）。
    let sig = format!("{status_text}|{projects:?}|{sel:?}");
    {
        let sig_state = handle.state::<TrayMenuSig>();
        let mut last = sig_state.0.lock().expect("tray menu sig lock poisoned");
        if *last == sig {
            return;
        }
        *last = sig;
    }

    let menu = Menu::with_id(handle, "ow-tray-menu").expect("tray menu");
    let status = MenuItem::with_id(handle, "__status", &status_text, false, None::<String>)
        .expect("status item");
    let _ = menu.append(&status);
    let sep = PredefinedMenuItem::separator(handle).expect("separator");
    let _ = menu.append(&sep);

    for it in &items {
        let _ = menu.append(it);
    }
    if !items.is_empty() {
        let sep2 = PredefinedMenuItem::separator(handle).expect("separator");
        let _ = menu.append(&sep2);
    }

    let open = MenuItem::with_id(handle, "open-main", "打开工作台", true, None::<String>)
        .expect("open item");
    let quit = MenuItem::with_id(handle, "__quit", "退出 OpenWorktree", true, None::<String>)
        .expect("quit item");
    let _ = menu.append(&open);
    let _ = menu.append(&quit);

    if let Some(tray) = handle.tray_by_id("ow-tray") {
        let _ = tray.set_menu(Some(menu));
    }
}

/// 后端就绪后启动的轮询线程：每 5s 对表一次（busy 数 + 项目 + SPA 回传的选中项目），
/// 重建托盘菜单。快照由 setup 阶段 manage 的 TrayBackend / TraySelectedProject 提供。
fn spawn_tray_poller(handle: tauri::AppHandle) {
    std::thread::spawn(move || {
        loop {
            std::thread::sleep(std::time::Duration::from_secs(5));
            rebuild_tray_menu(&handle);
        }
    });
}

/// 托盘图标（窗口右下角常驻）。图标复用打包 PNG；点击左键回主窗口。
fn setup_tray(handle: &tauri::AppHandle) {
    let icon = tauri::image::Image::from_bytes(include_bytes!("../icons/128x128.png"))
        .expect("decode tray icon");
    let _ = TrayIconBuilder::with_id("ow-tray")
        .icon(icon)
        .tooltip("OpenWorktree")
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| match event.id().as_ref() {
            "__quit" => {
                // 与窗口关闭同一条收割路径：RunEvent::ExitRequested kill 子进程。
                app.exit(0);
            }
            "open-main" => {
                show_main(app);
            }
            id if id.starts_with("project:") => {
                let project_id = id.trim_start_matches("project:").to_string();
                show_main(app);
                let _ = app.emit("tray-open-project", project_id);
            }
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            // Windows：左键单击图标（无菜单）= 打开主窗口，与常见 IM/下载器一致。
            if let tauri::tray::TrayIconEvent::Click {
                button: tauri::tray::MouseButton::Left,
                button_state: tauri::tray::MouseButtonState::Up,
                ..
            } = event
            {
                show_main(tray.app_handle());
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
            // 托盘：后端未就绪前菜单只有状态行与退出；就绪后轮询线程 5s 刷新。
            // 轮询参数（port/token）由下方 boot 协程抓到后写入 TrayBackend。
            handle.manage(TrayBackend(Mutex::new(None)));
            handle.manage(TraySelectedProject(Mutex::new(None)));
            handle.manage(TrayMenuSig(Mutex::new(String::new())));
            setup_tray(&handle);
            // 先铺一版「后端启动中…」菜单，避免首次轮询（5s 后）前右键空白
            rebuild_tray_menu(&handle);
            spawn_tray_poller(handle.clone());
            // SPA 选中项目变化 → 启动页桥接转发 → 这里记录，托盘菜单画绿点。
            // payload 是 JSON 字符串（去引号后即 project id）；空串表示无选中。
            use tauri::Listener;
            let sel_handle = handle.clone();
            handle.listen("tray-selected-project", move |event| {
                let id = event.payload().trim_matches('"').to_string();
                *sel_handle
                    .state::<TraySelectedProject>()
                    .0
                    .lock()
                    .expect("tray selected project lock poisoned") =
                    if id.is_empty() { None } else { Some(id) };
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
