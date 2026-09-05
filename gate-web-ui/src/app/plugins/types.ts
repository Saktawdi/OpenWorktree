/**
 * 插件系统（app/plugins）：宿主与插件之间的类型契约入口。
 *
 * 契约本体收敛在仓库内共享包 packages/plugin-sdk（@gate/plugin-sdk），宿主与所有
 * 插件工程 import 同一份——消灭 round 1 的手工镜像（host-types.ts 逐工程复制）。
 * 本文件只保留宿主侧视图类型并 re-export SDK，宿主内部既有 import 路径不变。
 *
 * 改契约请改 packages/plugin-sdk/src/types.ts（冻结语义见其文件头）。
 */
export * from "@gate/plugin-sdk";
import type { PluginManifest } from "@gate/plugin-sdk";

/** 目录同步得到的一条插件（含启停状态与资产指纹）。 */
export interface PluginListItem extends PluginManifest {
  enabled: boolean;
  cacheTag: string;
}
