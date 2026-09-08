/**
 * 部署脚本：把 build 产物与 manifest 复制到 <gateHome>/plugins/llm-assistant/。
 * 对应 gate.web.plugin.PluginCatalog 的扫描目录。
 */
import { cpSync, existsSync, mkdirSync, readFileSync, rmSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const pkgRoot = resolve(__dirname, "..");
const manifest = JSON.parse(readFileSync(resolve(pkgRoot, "manifest.json"), "utf8"));
const pluginId = manifest.id;

const gateHome = process.env.GATE_HOME || resolve(homedir(), ".gate");
const targetDir = resolve(gateHome, "plugins", pluginId);

console.log(`[install] 部署插件 ${pluginId} -> ${targetDir}`);
if (existsSync(targetDir)) {
  rmSync(targetDir, { recursive: true, force: true });
}
mkdirSync(targetDir, { recursive: true });

cpSync(resolve(pkgRoot, "manifest.json"), resolve(targetDir, "manifest.json"));
cpSync(resolve(pkgRoot, "dist"), targetDir, { recursive: true });
console.log(`[install] 部署完成。在 OpenWorktree 设置中心「插件」分区刷新即可看到。`);
