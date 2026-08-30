# OpenWorktree 品牌资产说明

> 品牌临时工作站（原 `brand/` 目录）已清理；页面实际引用的资产全部在
> `gate-web-ui/public/brand/`，本文档是设计说明与出处存档，附带的
> `brand-tools/` 是当时的资产生成脚本存档（仅供追溯，路径需调整后才能重跑）。

## 字标

**OW**：圆即字母 O，W 一笔穿环而出，外侧两笔略微伸出圆缘（worktree 枝条离开
主干的隐喻），W 与圆环交叠处品牌绿点缀（浅色版）。AI 生成原稿后抠底矢量化处理。

## 页面引用的资产（gate-web-ui/public/brand/）

| 文件 | 说明 |
|---|---|
| `ow-{light,dark}-badge-32.png` | favicon（index.html 按 `prefers-color-scheme` 自动切换，未用 .ico） |
| `ow-{light,dark}-badge-64.png` | 顶栏/界面静态徽章 |
| `ow-theme-switch.webp` | 主题切换动画，浅→深，54 帧 × 37ms ≈ 2.0s，0.29MB |
| `ow-theme-switch-reverse.webp` | 同素材倒放，深→浅 |

主题切换动画接入在 `TopBar.tsx` 的 `BrandMark`：切换时按方向播放，播完停在
目标主题静态徽章；`prefers-reduced-motion` 命中时直接换静态图。

## 配色（与 gate-web-ui/src/styles.css 同源）

| 主题 | 底色 | 墨色 | 点缀 |
|---|---|---|---|
| light | `#f5f6f8` | `#1a1d24` | `#0fa968` |
| dark | `#0a0c10` | `#e9ecf2` | `#35d99e` |

动画圆盘占画布 88%，与静态徽章填充比一致，播放结束与静态图标无缝衔接。

## 出处与再生成

- 静态图标与动画的 AI 原图/原视频（playground-1788081962-1.png、
  playground-1788082046-1.png、主题切换 mp4）源自外部生成工具，原始文件在
  本机下载目录；如需重做品牌，重新生成原图后可参考 `brand-tools/` 的处理流程
  （圆形蒙版抠底、亮度键控抽线条、抽帧加速、动画 WebP 编码）。
- 注意：本机 ffmpeg 的 libvpx 链路会丢弃 VP9 alpha 平面（已验证），动画一律
  用动画 WebP 承载，不要走 WebM。
- 已弃用方案记录：WebM(VP9 alpha)不可用；改名调研结论「Forge 在 Git 语境指
  托管平台、两字母缩写不可搜索」见提交前的讨论，勿再沿用 IssueForge/Gate 命名。
