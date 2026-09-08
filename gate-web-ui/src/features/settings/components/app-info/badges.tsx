/** 「关于」区的徽标与外链小工具（app-info）。 */

export function openExternal(url: string) {
  window.open(url, "_blank", "noopener,noreferrer");
}

/** 发行日期展示（published_at 是 ISO 串；解析失败原样回显）。 */
export function releaseDate(iso?: string | null): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleDateString("zh-CN", { year: "numeric", month: "long", day: "numeric" });
}

/**
 * 先行体验版徽标（内联 SVG，两种主题下都清晰）：近黑底 + 三色渐变描边（accent → 蓝 → 紫），
 * 斜向流光循环扫过 + 双色呼吸辉光。仅当远程最新发行 < 当前版本（本地是先行 beta 构建）时展示。
 */
export function BetaAheadBadge({ className = "" }: { className?: string }) {
  return (
    <svg
      className={`beta-ahead-badge ${className}`}
      width="150"
      height="30"
      viewBox="0 0 150 30"
      role="img"
      aria-label="先行体验版：本地版本领先于远程最新发行"
    >
      <defs>
        <linearGradient id="owb-border" x1="0%" y1="0%" x2="100%" y2="100%">
          <stop offset="0%" stopColor="#35d99e" />
          <stop offset="52%" stopColor="#4ea1ff" />
          <stop offset="100%" stopColor="#b16cff" />
        </linearGradient>
        <linearGradient id="owb-shine" x1="0%" y1="0%" x2="100%" y2="0%">
          <stop offset="0%" stopColor="#ffffff" stopOpacity="0" />
          <stop offset="50%" stopColor="#ffffff" stopOpacity="0.3" />
          <stop offset="100%" stopColor="#ffffff" stopOpacity="0" />
        </linearGradient>
        <clipPath id="owb-clip">
          <rect x="1" y="1" width="148" height="28" rx="14" />
        </clipPath>
      </defs>
      <rect x="1" y="1" width="148" height="28" rx="14" fill="#0c1116" stroke="url(#owb-border)" strokeWidth="1.5" />
      <g clipPath="url(#owb-clip)">
        <g transform="rotate(18 0 0)">
          <rect className="beta-badge-shine" x="-26" y="-8" width="18" height="46" fill="url(#owb-shine)" />
        </g>
      </g>
      <path d="M18.1 7.6 12.6 15.4h3.3l-1.3 7.2 5.9-8.9h-3.5z" fill="url(#owb-border)" />
      <text x="25" y="19.5" fill="#e9fbf4" fontSize="12" fontWeight="600" letterSpacing="0.5">先行体验版</text>
      <text x="88" y="19.5" fill="url(#owb-border)" fontSize="11" fontWeight="800" letterSpacing="2">BETA</text>
    </svg>
  );
}

/**
 * 先行者徽标（远程仓库还没有任何已发行版本时的"终极先行"状态）：近黑底 + 流彩描边
 * （四段渐变色随 SMIL 循环流动，绿→蓝→紫→粉）+ 斜向流光 + 闪烁星标 + 双色呼吸辉光，
 * 比先行 beta 徽标更高一档——这个构建本身就是全网唯一版本。
 */
export function PioneerBadge({ className = "" }: { className?: string }) {
  return (
    <svg
      className={`pioneer-badge ${className}`}
      width="170"
      height="30"
      viewBox="0 0 170 30"
      role="img"
      aria-label="先行者：远程仓库还没有任何已发行版本"
    >
      <defs>
        <linearGradient id="owp-border" x1="0%" y1="0%" x2="100%" y2="100%">
          <stop offset="0%" stopColor="#35d99e">
            <animate attributeName="stop-color" values="#35d99e;#4ea1ff;#b16cff;#ff6ec7;#35d99e" dur="7s" repeatCount="indefinite" />
          </stop>
          <stop offset="36%" stopColor="#4ea1ff">
            <animate attributeName="stop-color" values="#4ea1ff;#b16cff;#ff6ec7;#35d99e;#4ea1ff" dur="7s" repeatCount="indefinite" />
          </stop>
          <stop offset="70%" stopColor="#b16cff">
            <animate attributeName="stop-color" values="#b16cff;#ff6ec7;#35d99e;#4ea1ff;#b16cff" dur="7s" repeatCount="indefinite" />
          </stop>
          <stop offset="100%" stopColor="#ff6ec7">
            <animate attributeName="stop-color" values="#ff6ec7;#35d99e;#4ea1ff;#b16cff;#ff6ec7" dur="7s" repeatCount="indefinite" />
          </stop>
        </linearGradient>
        <linearGradient id="owp-shine" x1="0%" y1="0%" x2="100%" y2="0%">
          <stop offset="0%" stopColor="#ffffff" stopOpacity="0" />
          <stop offset="50%" stopColor="#ffffff" stopOpacity="0.35" />
          <stop offset="100%" stopColor="#ffffff" stopOpacity="0" />
        </linearGradient>
        <clipPath id="owp-clip">
          <rect x="1" y="1" width="168" height="28" rx="14" />
        </clipPath>
      </defs>
      <rect x="1" y="1" width="168" height="28" rx="14" fill="#0c1116" stroke="url(#owp-border)" strokeWidth="1.5" />
      <g clipPath="url(#owp-clip)">
        <g transform="rotate(18 0 0)">
          <rect className="pioneer-badge-shine" x="-26" y="-8" width="20" height="46" fill="url(#owp-shine)" />
        </g>
      </g>
      <path
        className="pioneer-star"
        transform="translate(21 15)"
        d="M0 -5 C0.8 -1.5 1.5 -0.8 5 0 C1.5 0.8 0.8 1.5 0 5 C-0.8 1.5 -1.5 0.8 -5 0 C-1.5 -0.8 -0.8 -1.5 0 -5 Z"
        fill="url(#owp-border)"
      />
      <text x="32" y="19.5" fill="#eef0f6" fontSize="12" fontWeight="600" letterSpacing="0.5">先行者</text>
      <text x="86" y="19.5" fill="url(#owp-border)" fontSize="10" fontWeight="800" letterSpacing="1.2">UNRELEASED</text>
    </svg>
  );
}
