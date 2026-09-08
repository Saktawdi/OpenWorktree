import { Database } from "@phosphor-icons/react";

/**
 * 存储设置（预留页）：数据与缓存的管理入口规划中。
 * 预期后续接入：工作区数据目录查看、本地缓存清理、队列/草稿等本地数据管理。
 */
export function StorageBlock() {
  return (
    <div className="space-y-4">
      <div className="card p-10 text-center">
        <div className="w-12 h-12 rounded-xl bg-raised border border-edge grid place-items-center mx-auto">
          <Database size={22} className="text-faint" />
        </div>
        <div className="mt-4 text-[15px] font-semibold">存储设置（规划中）</div>
        <div className="mt-1.5 text-[12.5px] text-faint leading-relaxed max-w-[420px] mx-auto">
          用于管理工作区数据与本地缓存：数据目录查看、缓存清理、排队消息与草稿等本地数据管理。该页面正在设计中，敬请期待。
        </div>
      </div>
    </div>
  );
}
