/**
 * 插件系统（app/plugins）：插件 UI 的错误边界。
 * 挂件渲染抛错只塌这一格，主应用与其它插件不受影响。
 */
import { Component, type ReactNode } from "react";

interface Props {
  /** 用于错误卡片的归属标注。 */
  label: string;
  children: ReactNode;
}

interface State {
  error: Error | null;
  nonce: number;
}

export class PluginBoundary extends Component<Props, State> {
  state: State = { error: null, nonce: 0 };

  static getDerivedStateFromError(error: Error): State {
    return { error, nonce: 0 };
  }

  render() {
    if (this.state.error) {
      return (
        <div className="rounded-lg border border-danger/30 bg-danger-dim/30 px-3 py-2.5">
          <div className="text-[12px] text-danger font-medium">「{this.props.label}」渲染崩溃</div>
          <div className="mt-1 text-[11.5px] text-dim break-all">{this.state.error.message}</div>
          <button
            className="btn btn-sm mt-2"
            onClick={() => this.setState((s) => ({ error: null, nonce: s.nonce + 1 }))}
          >
            重试渲染
          </button>
        </div>
      );
    }
    // nonce 变化强制重建子树：给「重试渲染」一个手动重置通道。
    return <div key={this.state.nonce}>{this.props.children}</div>;
  }
}
