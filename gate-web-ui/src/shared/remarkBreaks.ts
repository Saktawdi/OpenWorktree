/**
 * remark 插件（本地实现，语义等价 remark-breaks）：把段落内的软换行（单个 \n）
 * 转为硬换行（mdast break 节点 → <br>），让用户输入里的换行在渲染层原样保留，
 * 与对话框（Composer）所见一致。项目刻意不引入新 npm 依赖：插件只是一次纯树变换。
 *
 * 处理范围刻意收窄：只动「段落」内的 text 节点（列表项/引用块里的段落随块级下钻
 * 一并生效）；标题天然单行，行内代码（inlineCode）/代码块（code）与表格单元不参与
 * 换行语义，一律不碰——用户粘贴的代码缩进与表格结构不会被误改写。
 */
import type { Root } from "mdast";

/** 最小结构类型：mdast 节点只会用到 type / value / children 三个字段。 */
interface AnyNode {
  type: string;
  value?: unknown;
  children?: AnyNode[];
}

/** phrasing 子树变换：text 按行尾归一（\r\n/\r → \n，否则 CRLF 会与 <br> 叠成双换行）
 *  后切分，换行处插入 break 节点；其余节点原样透传或递归。 */
function breakify(nodes: AnyNode[]): AnyNode[] {
  const out: AnyNode[] = [];
  for (const node of nodes) {
    if (node.type === "text" && typeof node.value === "string" && node.value.includes("\n")) {
      const parts = node.value.replace(/\r\n?/g, "\n").split("\n");
      parts.forEach((part, i) => {
        if (i > 0) out.push({ type: "break" });
        if (part) out.push({ type: "text", value: part });
      });
    } else if (Array.isArray(node.children)) {
      out.push({ ...node, children: breakify(node.children) });
    } else {
      out.push(node);
    }
  }
  return out;
}

/** 自顶向下找段落：命中即处理其 phrasing 子树并停止下钻；其余块级节点继续递归。 */
function walk(node: AnyNode): void {
  if (node.type === "paragraph" && Array.isArray(node.children)) {
    node.children = breakify(node.children);
    return;
  }
  if (Array.isArray(node.children)) {
    for (const child of node.children) walk(child);
  }
}

/** remark 插件形态：返回一个 mdast 树变换器（react-markdown 的 remarkPlugins 直接收）。 */
export function remarkBreaks() {
  return (tree: Root): void => {
    walk(tree as unknown as AnyNode);
  };
}
