#!/usr/bin/env node
/**
 * 把构建产物安装进 gate-home 的插件目录：manifest.json + dist/ → <gateHome>/plugins/<id>/。
 * 目标目录取参顺序：命令行参数 > GATE_HOME 环境变量 > 探测运行中的后端实例 > <仓库根>/local-run/gate-home。
 *
 * 「探测运行中的后端」解决安装版桌面壳与本地开发的目录分歧：网页版实际读的是后端进程
 * 所用 gate.toml 的 gate_home（安装版在 %APPDATA%\OpenWorktree\…，本地开发在仓库 local-run），
 * 此前脚本固定按仓库 local-run 猜——桌面壳用户每次 deploy 都装错地方、网页里永远看不到新产物。
 * 现逐个读取常见数据目录的 gate.toml，对其中 [web] 端点做 /api/health 探活，取第一个在线的
 * gate_home；全部离线才回退仓库 local-run。装完在 设置 → 插件 点「刷新」即可发现。
 */
import { cp, mkdir, rm, readFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const manifestPath = path.join(root, "manifest.json");

if (!existsSync(manifestPath)) {
  console.error("manifest.json 不存在：请先在项目根创建。");
  process.exit(1);
}
const manifest = JSON.parse(await readFile(manifestPath, "utf-8"));
if (!manifest.id || !/^[a-z][a-z0-9-]*$/.test(manifest.id)) {
  console.error(`manifest.id 非法（需匹配 [a-z][a-z0-9-]*）：${manifest.id}`);
  process.exit(1);
}

/** 向上找仓库根（含 .git 或 local-run 的最近祖先）；模板与 plugins/ 下嵌套工程都适用。 */
function findRepoRoot(start) {
  let dir = start;
  for (let i = 0; i < 5; i++) {
    if (existsSync(path.join(dir, ".git")) || existsSync(path.join(dir, "local-run"))) return dir;
    const parent = path.dirname(dir);
    if (parent === dir) break;
    dir = parent;
  }
  return start;
}

/** 解析 gate.toml：gate_home + [web] 段的 bind/port；读不到/无 web 端口返回 null。 */
function parseGateToml(text) {
  const gateHome = text.match(/^\s*gate_home\s*=\s*"([^"]+)"/m)?.[1];
  if (!gateHome) return null;
  const web = text.match(/\[web\]([\s\S]*?)(?=\n\s*\[[a-z_]+\]|\s*$)/i)?.[1] ?? "";
  const port = Number(web.match(/^\s*port\s*=\s*(\d+)/m)?.[1]);
  if (!Number.isInteger(port) || port <= 0) return null;
  const bindRaw = web.match(/^\s*bind\s*=\s*"([^"]+)"/m)?.[1];
  const host = bindRaw && !bindRaw.includes("0.0.0.0") && !bindRaw.includes("::") ? bindRaw : "127.0.0.1";
  return { gateHome, host, port };
}

/** 对单个候选 gate.toml 探活：/api/health 免鉴权（见 gate-web WebServer），在线即返回 gate_home。 */
async function probeConfig(cfgPath) {
  try {
    const parsed = parseGateToml(await readFile(cfgPath, "utf8"));
    if (!parsed) return null;
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), 1500);
    try {
      const res = await fetch(`http://${parsed.host}:${parsed.port}/api/health`, {
        signal: ctl.signal,
      });
      if (res.ok) return parsed.gateHome;
    } catch {
      /* 端口未监听/拒绝/超时 = 该实例不在运行 */
    } finally {
      clearTimeout(timer);
    }
  } catch {
    /* 读不到/解析失败视为离线候选 */
  }
  return null;
}

/**
 * 候选 gate.toml（安装版桌面壳数据目录优先，仓库 local-run 殿后——开发时本机跑的就是它；
 * 只探「进程在跑 + 健康检查在线」的实例，防止把插件装进一个不在运行的旧数据根）。
 */
function candidateConfigs() {
  const list = [];
  const appData = process.env.APPDATA;
  if (appData) {
    list.push(path.join(appData, "OpenWorktree", "local-run", "gate.toml"));
    list.push(path.join(appData, "OpenWorktree", "data", "local-run", "gate.toml"));
    list.push(path.join(appData, "OpenWorktree", "data", "gate.toml"));
    list.push(path.join(appData, "com.openworktree.desktop", "local-run", "gate.toml"));
  }
  list.push(path.join(findRepoRoot(root), "local-run", "gate.toml"));
  return [...new Set(list.map((p) => path.resolve(p)))];
}

/** 探测第一个在线的实例；找不到返回 null。附带回退原因供日志。 */
async function findLiveGateHome() {
  const reports = [];
  for (const cfg of candidateConfigs()) {
    const gateHome = await probeConfig(cfg);
    reports.push({ cfg, gateHome });
    if (gateHome) {
      console.log(`[deploy] 探测到运行中的后端：${cfg}\n[deploy]    → gate_home = ${gateHome}`);
      return gateHome;
    }
  }
  for (const r of reports) {
    console.log(`[deploy]   跳过（不在运行）${r.cfg}`);
  }
  return null;
}

let target;
let targetSource;
const explicitArg = process.argv[2];
if (explicitArg) {
  target = explicitArg;
  targetSource = "命令行参数";
} else if (process.env.GATE_HOME) {
  target = process.env.GATE_HOME;
  targetSource = "GATE_HOME 环境变量";
} else {
  const live = await findLiveGateHome();
  if (live) {
    target = live;
    targetSource = "探测到的运行中实例";
  } else {
    target = path.join(findRepoRoot(root), "local-run", "gate-home");
    targetSource = "仓库回退（未探测到运行中的后端）";
    console.warn(
      "[deploy] 未探测到运行中的后端，回退安装到仓库 local-run/gate-home。\n" +
        "[deploy] 若目标是安装版桌面壳/其它数据目录，请用 GATE_HOME=<gate-home绝对路径> npm run deploy 指定。",
    );
  }
}
const dest = path.join(target, "plugins", manifest.id);

await mkdir(path.dirname(dest), { recursive: true });
await rm(dest, { recursive: true, force: true });
await cp(root, dest, {
  recursive: true,
  filter: (src) => {
    const rel = path.relative(root, src);
    if (!rel) return true;
    // 只带 manifest + dist + README；源码/依赖/脚手架留在工程里
    return rel === "manifest.json" || rel.startsWith("dist") || rel === "README.md";
  },
});

console.log(`已安装（来源：${targetSource}）→ ${dest}`);
console.log("前端 设置 → 插件 → 刷新 即可发现；更新产物后点「重载」。");
