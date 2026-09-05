#!/usr/bin/env node
/**
 * 把构建产物安装进 gate-home 的插件目录：manifest.json + dist/ → <gateHome>/plugins/<id>/。
 * 目标目录取参顺序：命令行参数 > GATE_HOME 环境变量 > <仓库根>/local-run/gate-home。
 * 安装后在 设置 → 插件 点「刷新」即可发现；改代码重跑本脚本后点「重载」热更新。
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

const target =
  process.argv[2] ||
  process.env.GATE_HOME ||
  path.join(findRepoRoot(root), "local-run", "gate-home");
const dest = path.join(target, "plugins", manifest.id);

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

console.log(`已安装 → ${dest}`);
console.log("前端 设置 → 插件 → 刷新 即可发现；更新产物后点「重载」。");
