import React from "react";
import ReactMarkdown, { type Components } from "react-markdown";
import remarkGfm from "remark-gfm";
import { openExternal } from "@/shared/externalLinks";
import { remarkBreaks } from "@/shared/remarkBreaks";

// T-119：这两样必须模块级常量，不能写在 Markdown 里。react-markdown 把
// components[tag] 直接当作元素的 type（hast-util-to-jsx-runtime 的 findComponentFromName
// → state.create），每次渲染新建一个内联箭头函数就是换了一个组件类型——React 会按
// "类型不同" 卸载整棵子树再重建，流式期间每个增量都把整段正文的 DOM 拆了重建。
// 常量身份不变，同样内容只做常规 diff 更新。插件表同理：换新数组 = 换 parse 管道。
const REMARK_PLUGINS = [remarkGfm];
// 用户消息渲染用的插件表：在 GFM 之上追加「软换行 → 硬换行」（用户输入原样换行，
// 与 Composer 所见一致）。仅用户气泡启用（breaks prop），助手正文保持标准 Markdown 语义。
const REMARK_PLUGINS_BREAKS = [...REMARK_PLUGINS, remarkBreaks];

const COMPONENTS: Components = {
  a: ({ href, children }) => (
    <a
      href={href}
      target="_blank"
      rel="noopener noreferrer"
      className="text-accent underline underline-offset-2 hover:brightness-110"
      onClick={(e) => {
        // http/https 外链统一走 openExternal（桌面壳里 target=_blank 被 WebView 拦截）；
        // 其余 href（相对路径等）保持默认导航。左键才接管，中键/右键菜单不受影响。
        if (href && /^https?:\/\//i.test(href)) {
          e.preventDefault();
          openExternal(href);
        }
      }}
    >
      {children}
    </a>
  ),
  code: ({ className, children, ...props }) => {
    const match = /language-(\w+)/.exec(className ?? "");
    const isInline = !match;
    if (isInline) {
      return (
        <code className="bg-sunken px-1 py-0.5 rounded text-[90%] font-mono" {...props}>
          {children}
        </code>
      );
    }
    return (
      <pre className="bg-sunken border border-edge rounded-lg p-3 overflow-x-auto my-2">
        <code className={`text-[12.5px] font-mono leading-relaxed ${className ?? ""}`} {...props}>
          {children}
        </code>
      </pre>
    );
  },
  pre: ({ children }) => <>{children}</>,
  table: ({ children }) => (
    <div className="overflow-x-auto my-2">
      <table className="w-full border-collapse text-[12.5px]">{children}</table>
    </div>
  ),
  th: ({ children }) => (
    <th className="border border-edge bg-sunken px-2.5 py-1.5 text-left font-semibold">{children}</th>
  ),
  td: ({ children }) => (
    <td className="border border-edge px-2.5 py-1.5">{children}</td>
  ),
  ul: ({ children }) => <ul className="list-disc pl-5 my-1 space-y-0.5">{children}</ul>,
  ol: ({ children }) => <ol className="list-decimal pl-5 my-1 space-y-0.5">{children}</ol>,
  li: ({ children }) => <li className="leading-relaxed">{children}</li>,
  blockquote: ({ children }) => (
    <blockquote className="border-l-3 border-accent/30 pl-3 my-2 text-faint italic">{children}</blockquote>
  ),
  hr: () => <hr className="my-3 border-t border-edge" />,
  h1: ({ children }) => <h1 className="text-base font-bold mt-4 mb-1.5">{children}</h1>,
  h2: ({ children }) => <h2 className="text-[15px] font-bold mt-3.5 mb-1">{children}</h2>,
  h3: ({ children }) => <h3 className="text-[14px] font-bold mt-3 mb-1">{children}</h3>,
  p: ({ children }) => <p className="my-1.5 last:mb-0">{children}</p>,
};

// memo：流式期间聊天列表高频重渲染，历史消息的 Markdown 字符串不变，
// 跳过重复的 markdown→React 解析（长会话下该解析是主要主线程开销之一）。
// 注意它救不了正在流式的那一条：那条的 children 每个增量都变，必然重解析——
// T-119 因此在 SSE 侧把增量合帧，降低"每条增量都重解析一遍全文"的次数。
export const Markdown = React.memo(function Markdown({
  children,
  className,
  breaks,
}: {
  children: string;
  className?: string;
  /** true 时把正文里的单个换行渲染为 <br>（用户消息按输入原样换行）。 */
  breaks?: boolean;
}) {
  return (
    <div className={className}>
      <ReactMarkdown remarkPlugins={breaks ? REMARK_PLUGINS_BREAKS : REMARK_PLUGINS} components={COMPONENTS}>
        {children}
      </ReactMarkdown>
    </div>
  );
});