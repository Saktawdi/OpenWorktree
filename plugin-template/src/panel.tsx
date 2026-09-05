import { useState } from "react";

/** 模板示例面板：证明插件 React 组件（含 hooks）在宿主树上正常工作。 */
export function HelloPanel() {
  const [count, setCount] = useState(0);
  return (
    <div className="tpl-panel">
      <div className="text-[13px] font-semibold text-ink">你好，插件世界</div>
      <div className="mt-1 text-[12px] text-dim leading-relaxed">
        宿主的全局样式类（card / btn / text-dim / field-label …）可直接使用；
        插件私有样式写在 src/styles.css，类名请加自己的前缀。
      </div>
      <div className="mt-1 text-[12px] text-dim leading-relaxed">
        可用能力见 README：ctx.kv（声明 kv 权限）、ctx.hostFetch（声明 net 权限）、
        registerChatInputAction、registerPanelWidget。
      </div>
      <button className="btn btn-sm mt-3" onClick={() => setCount((c) => c + 1)}>
        点击计数：{count}
      </button>
    </div>
  );
}

/** 模板示例整页：演示 ctx.registerPage（顶栏导航 + 全页渲染，hooks 可用）。 */
export function HelloPage() {
  const [clicks, setClicks] = useState(0);
  return (
    <div className="max-w-[880px] mx-auto px-6 py-10">
      <div className="card p-8">
        <div className="text-[15px] font-semibold text-ink">模板示例页</div>
        <div className="mt-1.5 text-[12.5px] text-dim leading-relaxed">
          这是插件通过 ctx.registerPage 贡献的整页视图：顶栏导航出现「示例页」入口，
          禁用/重载插件时宿主会自动关闭本页并回到工作台。
        </div>
        <button className="btn btn-sm mt-4" onClick={() => setClicks((c) => c + 1)}>
          页面计数：{clicks}
        </button>
      </div>
    </div>
  );
}
