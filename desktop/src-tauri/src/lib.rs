// OpenWorktree 桌面壳：托管后端单文件（ow.exe / ow-linux）并注入登录令牌。
//
// 生命周期：setup 里经 shell 插件以 sidecar 方式拉起后端，工作目录指向数据目录
// （resolve_data_dir：分根布局 —— 轻根 %APPDATA%\OpenWorktree\local-run 存小数据/配置，
//  常驻系统盘每用户目录；工单克隆与项目镜像等大体积数据在安装目录同级 OpenWorktree-data，
//  更新/热更新/卸载均不触碰；首次启动自动收敛旧布局，见下方「数据布局」注释）。
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

/// 托盘面板主题（"dark" / "light"）：SPA 顶栏日夜切换经启动页桥接回传，面板跟随。
struct TrayTheme(Mutex<String>);

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

// ─── 数据布局（分根，v1）──────────────────────────────────────────────
// 轻根：小数据/配置，常驻系统盘每用户目录，重装/更新/热更新永不移动：
//   %APPDATA%\OpenWorktree\local-run\{gate.toml, gate-home\{gate.db, blobs, locks,
//   approvals, audit.jsonl, web-token, …}}
// 重根：大体积数据（工单克隆工作区、项目镜像 auth-*.git），随安装所在盘放置，
//   位于安装目录【同级】的独立目录——安装包/在线热更新的载荷只认安装目录本身：
//   <安装目录同级>\OpenWorktree-data\{clones\, auth\auth-*.git}
// 快速模式超级工单的工作区是用户项目路径（不在上述任何数据根内），天然不受更新影响。
// 后端工作目录 = 轻根（其下 local-run/gate.toml 由本文件首次启动时生成，显式给出重根
// 绝对路径；后端默认配置解析即 ./local-run/gate.toml）。
//
// 旧布局（%APPDATA%\com.openworktree.desktop 整树 / 卸载「保留」备份 / 早期「安装目录
// data\」快照）在此做一次性收敛：按 gate.db 活度挑本体树，把 gate-home 搬入轻根、
// clones 与 auth-*.git 搬入重根，其余化石树改名归档（保留不删）；随后写
// layout-migrate.json 标记，后端启动时把 DB 行内旧绝对路径重基为相对新克隆根
// （LegacyLayoutMigration），克隆 .git/config 里的 auth origin 路径由本侧同步改写。

use std::time::SystemTime;

const HEAVY_DIR_NAME: &str = "OpenWorktree-data";
const LEGACY_APP_DATA_DIR: &str = "com.openworktree.desktop";

/// 轻根（%APPDATA%\OpenWorktree）；APPDATA 缺失时回退 app_data。
fn light_root(app_data: &Option<PathBuf>) -> Option<PathBuf> {
    std::env::var_os("APPDATA")
        .map(PathBuf::from)
        .map(|d| d.join("OpenWorktree"))
        .or_else(|| app_data.clone())
}

/// 重根：默认取安装目录同级 OpenWorktree-data（与安装同盘、载荷外）；不可写（如
/// perMachine 装进受保护目录）时退回 %LOCALAPPDATA%\OpenWorktree-data 并在日志说明。
fn heavy_root(install: &Path, log: &Option<PathBuf>) -> PathBuf {
    let sibling = install
        .parent()
        .map(|p| p.join(HEAVY_DIR_NAME))
        .unwrap_or_else(|| install.join(HEAVY_DIR_NAME));
    for cand in [sibling.clone()] {
        if std::fs::create_dir_all(&cand).is_ok() && dir_writable(&cand) {
            return cand;
        }
    }
    let fallback = std::env::var_os("LOCALAPPDATA")
        .map(PathBuf::from)
        .map(|d| d.join(HEAVY_DIR_NAME))
        .unwrap_or_else(|| sibling.clone());
    append_log(
        log,
        &format!(
            "layout: heavy root {} not writable, fallback to {}",
            sibling.display(),
            fallback.display()
        ),
    );
    let _ = std::fs::create_dir_all(&fallback);
    fallback
}

/// 数据活度：local-run/gate-home/gate.db 的修改时间（无库 → None）。
fn data_freshness(local_run: &Path) -> Option<SystemTime> {
    std::fs::metadata(local_run.join("gate-home").join("gate.db"))
        .ok()
        .and_then(|m| m.modified().ok())
}

/// 把旧树改名归档（local-run.stale-<毫秒>，保留不删）；失败仅记录。
fn archive_tree(local_run: &Path, log: &Option<PathBuf>, what: &str) {
    let ts = SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis())
        .unwrap_or(0);
    let stale = local_run.with_file_name(format!("local-run.stale-{ts}"));
    match std::fs::rename(local_run, &stale) {
        Ok(()) => append_log(
            log,
            &format!("layout: stale {} archived to {}", what, stale.display()),
        ),
        Err(e) => append_log(
            log,
            &format!("layout: cannot archive {} ({}): {}", what, local_run.display(), e),
        ),
    }
}

/// 克隆 .git/config 的 origin 改写：旧 auth 父目录前缀 → 新重根 auth 目录。
/// Windows git 会把反斜杠写为转义形式（`C:\\Users\\…`），因此需要同时匹配
/// 原生反斜杠、正斜杠、转义反斜杠三种拼写；任一种命中都改写，避免静默漏改。
fn rewrite_clone_origins(clones_root: &Path, old_auth_parent: &Path, new_auth_dir: &Path, log: &Option<PathBuf>) {
    if !clones_root.is_dir() {
        return;
    }
    let old_fs = old_auth_parent.to_string_lossy();
    let old_fwd = old_fs.replace('\\', "/");
    let old_esc = old_fs.replace('\\', "\\\\");
    let new_fwd = new_auth_dir.to_string_lossy().replace('\\', "/");
    let mut touched = 0usize;
    let mut scanned = 0usize;
    fn walk(
        dir: &Path,
        old_fs: &str,
        old_fwd: &str,
        old_esc: &str,
        new_fwd: &str,
        touched: &mut usize,
        scanned: &mut usize,
    ) {
        let Ok(entries) = std::fs::read_dir(dir) else { return };
        for entry in entries.flatten() {
            let p = entry.path();
            if !entry.file_type().map(|t| t.is_dir()).unwrap_or(false) {
                continue;
            }
            if entry.file_name() == ".git" {
                let cfg = p.join("config");
                let Ok(text) = std::fs::read_to_string(&cfg) else { continue };
                *scanned += 1;
                let rewritten = text
                    .replace(&*old_fs, &*new_fwd)
                    .replace(&*old_fwd, &*new_fwd)
                    .replace(&*old_esc, &*new_fwd);
                if rewritten != text {
                    if std::fs::write(&cfg, rewritten).is_ok() {
                        *touched += 1;
                    }
                }
                continue;
            }
            walk(&p, old_fs, old_fwd, old_esc, new_fwd, touched, scanned);
        }
    }
    walk(clones_root, &old_fs, &old_fwd, &old_esc, &new_fwd, &mut touched, &mut scanned);
    if touched > 0 {
        append_log(log, &format!("layout: rewrote auth origin in {touched} clone .git/config"));
    } else if scanned > 0 {
        append_log(
            log,
            &format!(
                "layout: WARN scanned {scanned} clone .git/config but none referenced old auth parent {}",
                old_auth_parent.display()
            ),
        );
    }
}

/// 生成布局配置（轻根 local-run/gate.toml）：显式给出重根绝对路径与自指 gate_home。
fn write_layout_config(local_run: &Path, heavy: &Path, log: &Option<PathBuf>) {
    let abs = |p: &Path| p.to_string_lossy().replace('\\', "/");
    let cfg = local_run.join("gate.toml");
    let content = format!(
        "# OpenWorktree 布局配置（壳首次启动生成）。\n\
         # 小数据/配置常驻系统盘：{light}\\local-run；\n\
         # 工单克隆与项目镜像等大体积数据在安装目录同级：{heavy}（更新/卸载不触碰）。\n\
         schema_version = 2\n\
         project = \"openworktree\"\n\
         auth_repo = \"{auth}\"\n\
         clones_root = \"{clones}\"\n\
         gate_home = \"{home}\"\n\
         \n\
         [web]\n\
         bind = \"127.0.0.1\"\n\
         port = 18080\n",
        light = abs(local_run),
        heavy = abs(heavy),
        auth = abs(&heavy.join("auth").join("auth.git")),
        clones = abs(&heavy.join("clones")),
        home = abs(&local_run.join("gate-home"))
    );
    let tmp = cfg.with_extension("toml.tmp");
    if std::fs::write(&tmp, content).is_ok() && std::fs::rename(&tmp, &cfg).is_ok() {
        append_log(log, &format!("layout: wrote config {}", cfg.display()));
    } else {
        let _ = std::fs::remove_file(&tmp);
        append_log(log, "layout: FAILED to write gate.toml (backend will fall back to minimal defaults)");
    }
}

/// 旧布局 → 分根布局的一次性收敛（幂等：轻根已有 gate.toml 即跳过）。任何失败都不删源，
/// 只记录；后端兜底生成最小配置也能跑（克隆落到轻根是降级情形，日志会提示）。
fn bootstrap_layout(light: &Path, heavy: &Path, install: &Path, log: &Option<PathBuf>) {
    let local_run = light.join("local-run");
    let _ = std::fs::create_dir_all(local_run.join("gate-home"));
    let cfg = local_run.join("gate.toml");
    if cfg.is_file() {
        return; // 布局已建立
    }
    let ap = std::env::var_os("APPDATA").map(PathBuf::from);
    let legacy_local = ap.as_ref().map(|d| d.join(LEGACY_APP_DATA_DIR).join("local-run"));
    let kept_local = ap.as_ref().map(|d| d.join("OpenWorktree").join("data").join("local-run"));
    let install_local = install.join("data").join("local-run");
    let mut candidates: Vec<PathBuf> = Vec::new();
    for (t, what) in [
        (legacy_local, "legacy per-user"),
        (kept_local, "keep-backup"),
        (Some(install_local.clone()), "install-dir data"),
    ] {
        if let Some(p) = t {
            if p.is_dir() {
                append_log(log, &format!("layout: candidate {what}: {}", p.display()));
                candidates.push(p);
            }
        }
    }
    // 本体树 = gate.db 最新者；无任何旧树 → 全新布局，只写配置。
    let mut best: Option<PathBuf> = None;
    let mut best_fresh: Option<SystemTime> = None;
    for c in &candidates {
        if let Some(t) = data_freshness(c) {
            if best_fresh.is_none() || best_fresh.map_or(true, |b| t > b) {
                best_fresh = Some(t);
                best = Some(c.clone());
            }
        }
    }
    let Some(src) = best else {
        write_layout_config(&local_run, heavy, log);
        return;
    };
    append_log(log, &format!("layout: adopting {} as data source", src.display()));

    // 归档其余候选树（保留不删，杜绝误恢复/误导）。
    for c in &candidates {
        if c != &src {
            archive_tree(c, log, "stale data tree");
        }
    }

    // auth 镜像（auth-*.git 项目镜像，含门禁级 auth.git）→ 重根。必须先于 gate-home/clones
    // 搬迁并采用「失败即中止」：任一镜像搬不过去就整体中止——不写迁移标记与 gate.toml，
    // 旧树保持原状、下次启动重试。否则后端会把 DB 的 project.auth_repo 重基到空目录
    // （LegacyLayoutMigration），项目建单全线 "auth repo does not exist"（c6d4469 缺陷）。
    let _ = std::fs::create_dir_all(heavy.join("auth"));
    let _ = std::fs::create_dir_all(heavy.join("clones"));
    let mut auth_total = 0usize;
    let mut auth_ok = 0usize;
    match std::fs::read_dir(&src) {
        Ok(entries) => {
            for entry in entries.flatten() {
                let name = entry.file_name();
                let name = name.to_string_lossy();
                let is_auth = name == "auth.git"
                        || (name.starts_with("auth-") && name.ends_with(".git"));
                if !is_auth || !entry.path().is_dir() {
                    continue;
                }
                auth_total += 1;
                let dst = heavy.join("auth").join(&*name);
                if move_dir(&entry.path(), &dst) {
                    auth_ok += 1;
                    append_log(
                        log,
                        &format!("layout: auth mirror moved {} -> {}", name, dst.display()),
                    );
                } else {
                    append_log(
                        log,
                        &format!(
                            "layout: WARN cannot move auth mirror {} -> {}",
                            name,
                            dst.display()
                        ),
                    );
                }
            }
        }
        Err(e) => {
            append_log(
                log,
                &format!("layout: WARN cannot list legacy tree {}: {}", src.display(), e),
            );
            append_log(
                log,
                "layout: ABORT legacy layout migration: cannot enumerate legacy tree — no marker/config written, retry on next boot",
            );
            return;
        }
    }
    if auth_total > 0 && auth_ok < auth_total {
        append_log(
            log,
            &format!(
                "layout: ABORT legacy layout migration: moved {auth_ok}/{auth_total} auth mirrors — no marker/config written, retry on next boot"
            ),
        );
        return;
    }
    if auth_ok > 0 {
        append_log(log, &format!("layout: auth mirrors moved into heavy root ({auth_ok})"));
    }

    // gate-home（小数据）→ 轻根 local-run 下。
    let src_gate_home = src.join("gate-home");
    let dst_gate_home = local_run.join("gate-home");
    if src_gate_home.is_dir() {
        let mut merged = false;
        if !dst_gate_home.exists() && std::fs::rename(&src_gate_home, &dst_gate_home).is_ok() {
            merged = true;
        } else if move_dir(&src_gate_home, &dst_gate_home) {
            merged = true;
        }
        if merged {
            append_log(log, "layout: gate-home moved into light root");
        } else {
            append_log(log, "layout: WARN cannot move gate-home into light root");
        }
    }

    // clones（大体积）→ 重根；随后克隆 origin 前缀改写。
    let src_clones = src.join("clones");
    if src_clones.is_dir() {
        if move_dir(&src_clones, &heavy.join("clones")) {
            append_log(log, "layout: clones moved into heavy root");
        } else {
            append_log(log, "layout: WARN cannot move clones into heavy root");
        }
    }
    rewrite_clone_origins(&heavy.join("clones"), &src, &heavy.join("auth"), log);

    // 迁移标记：后端启动时（LegacyLayoutMigration）把 DB 行内旧绝对路径重基。
    let old_clones = src_clones.to_string_lossy().replace('\\', "/");
    let old_auth = src.to_string_lossy().replace('\\', "/");
    let marker = local_run.join("layout-migrate.json");
    let _ = std::fs::write(
        &marker,
        format!(
            "{{\"old_clones_root\":\"{clones}\",\"old_auth_parent\":\"{auth}\"}}",
            clones = old_clones,
            auth = old_auth
        ),
    );
    append_log(log, &format!("layout: migration marker written at {}", marker.display()));

    write_layout_config(&local_run, heavy, log);
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

/// 数据目录决策（后端工作目录，local-run/ 落在其中）。
/// release：分根布局 —— 轻根 %APPDATA%\OpenWorktree（小数据常驻系统盘）＋重根
/// <安装同级>\OpenWorktree-data（克隆/镜像随安装盘）；首次启动完成旧布局一次性收敛。
/// debug（tauri dev / 测试）：不做迁移，用每用户 app_data 兜底，避免把真实数据搬进
/// target\debug（cargo clean 会清掉）。dev-shim 侧车自带仓库内 local-run 配置，不经此。
fn resolve_data_dir(handle: &tauri::AppHandle) -> PathBuf {
    let app_data = handle.path().app_data_dir().ok();
    if cfg!(debug_assertions) {
        let fallback = app_data.unwrap_or_else(std::env::temp_dir);
        let _ = std::fs::create_dir_all(&fallback);
        return fallback;
    }
    let Some(exe) = std::env::current_exe().ok() else {
        return app_data.unwrap_or_else(std::env::temp_dir);
    };
    let Some(install) = exe.parent() else {
        return app_data.unwrap_or_else(std::env::temp_dir);
    };
    let Some(light) = light_root(&app_data) else {
        return app_data.unwrap_or_else(std::env::temp_dir);
    };
    let log = Some(light.join("backend-boot.log"));
    let _ = std::fs::create_dir_all(light.join("local-run"));
    append_log(&log, &format!("light data root: {}", light.display()));
    if !light.join("local-run").join("gate.toml").is_file() {
        let heavy = heavy_root(install, &log);
        append_log(&log, &format!("heavy data root: {}", heavy.display()));
        bootstrap_layout(&light, &heavy, install, &log);
    }
    light
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

const PANEL_W: f64 = 216.0;
const PANEL_ROW_H: f64 = 32.0;
const PANEL_MAX_ROWS: usize = 10;

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

/// 依据快照估算面板高度（细长：紧凑页头 + 项目行 + 操作行）。
fn panel_height(snap: &serde_json::Value) -> f64 {
    let projects = snap
        .get("projects")
        .and_then(|p| p.as_array())
        .map(|a| a.len())
        .unwrap_or(0);
    let rows = projects.clamp(0, PANEL_MAX_ROWS) as f64;
    42.0 + 8.0 + rows * PANEL_ROW_H + if projects > 0 { 10.0 } else { 0.0 } + 38.0 + 10.0
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
    let theme = handle
        .state::<TrayTheme>()
        .0
        .lock()
        .expect("tray theme lock poisoned")
        .clone();
    let snap = serde_json::json!({
        "status": status,
        "busy": busy,
        "selected": selected,
        "theme": theme,
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
            handle.manage(TrayTheme(Mutex::new("dark".to_string())));
            handle.manage(TraySnapshot(Mutex::new(serde_json::json!({
                "status": "starting", "busy": null, "selected": null, "theme": "dark", "projects": [],
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

            // SPA 选中项目 / 主题变化 → 启动页桥接转发 → 这里记录，托盘面板画绿点/换肤。
            // 选中 payload 是 JSON 字符串（去引号后即 project id）；空串表示无选中。
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
            // 主题 payload 同为 JSON 字符串："dark" / "light"。
            let theme_handle = handle.clone();
            handle.listen("tray-theme", move |event| {
                let theme = event.payload().trim_matches('"').to_string();
                if theme != "light" && theme != "dark" {
                    return;
                }
                *theme_handle
                    .state::<TrayTheme>()
                    .0
                    .lock()
                    .expect("tray theme lock poisoned") = theme;
                poll_backend_snapshot(&theme_handle);
            });
            tauri::async_runtime::spawn(async move {
                // 数据目录：轻根（系统盘每用户小数据/配置）＋重根（安装同级大体积克隆），
                // 旧布局由 resolve_data_dir/bootstrap_layout 一次性收敛后返回轻根。data_dir 即轻根。
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
