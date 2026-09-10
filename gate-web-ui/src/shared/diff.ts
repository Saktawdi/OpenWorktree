import type { DiffFile, DiffHunk, DiffLine } from "@/shared/types";

export function parseUnifiedDiff(text: string): DiffFile[] {
  const files: DiffFile[] = [];
  const lines = text.split("\n");
  let cur: DiffFile | null = null;
  let hunk: DiffHunk | null = null;
  let oldNo = 0;
  let newNo = 0;

  const flushHunk = () => {
    if (cur && hunk) cur.hunks.push(hunk);
    hunk = null;
  };
  const flushFile = () => {
    flushHunk();
    if (cur) computeCounts(cur);
    cur = null;
  };

  for (const raw of lines) {
    if (raw.startsWith("diff --git ")) {
      flushFile();
      const m = raw.match(/^diff --git a\/(.+?) b\/(.+)$/);
      cur = {
        path: m ? m[2] : raw.slice(11),
        status: "modified",
        additions: 0,
        deletions: 0,
        hunks: [],
      };
      files.push(cur);
      continue;
    }
    if (!cur) continue;
    if (raw.startsWith("new file mode")) {
      cur.status = "added";
      continue;
    }
    if (raw.startsWith("deleted file mode")) {
      cur.status = "deleted";
      continue;
    }
    if (raw.startsWith("--- ") || raw.startsWith("index ") || raw.startsWith("similarity ")) continue;
    if (raw.startsWith("+++ ")) continue;
    if (raw.startsWith("@@")) {
      flushHunk();
      const m = raw.match(/@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/);
      oldNo = m ? parseInt(m[1], 10) : 1;
      newNo = m ? parseInt(m[2], 10) : 1;
      hunk = { header: raw, lines: [] };
      continue;
    }
    if (!hunk) continue;
    if (raw.startsWith("+")) {
      hunk.lines.push({ type: "add", newNo, content: raw.slice(1) });
      newNo++;
    } else if (raw.startsWith("-")) {
      hunk.lines.push({ type: "del", oldNo, content: raw.slice(1) });
      oldNo++;
    } else {
      hunk.lines.push({ type: "ctx", oldNo, newNo, content: raw });
      oldNo++;
      newNo++;
    }
  }
  flushFile();
  return files;
}

function computeCounts(f: DiffFile) {
  let add = 0;
  let del = 0;
  for (const h of f.hunks) {
    for (const l of h.lines) {
      if (l.type === "add") add++;
      else if (l.type === "del") del++;
    }
  }
  f.additions = add;
  f.deletions = del;
}

export function diffTotals(files: DiffFile[]): { additions: number; deletions: number; files: number } {
  let additions = 0;
  let deletions = 0;
  for (const f of files) {
    additions += f.additions;
    deletions += f.deletions;
  }
  return { additions, deletions, files: files.length };
}

/** 列表侧增删行数指纹：单文件内容缓存用它判断「列表刷新后内容是否过期」。 */
export function diffSig(f: { additions: number; deletions: number }): string {
  return `${f.additions}/${f.deletions}`;
}

/** 估算完整 diff 文件集的字节体量（demo 快照等场景用，不要求精确）。 */
export function approxDiffBytes(files: DiffFile[]): number {
  let n = 0;
  for (const f of files) {
    n += f.path.length + 40;
    for (const h of f.hunks) for (const l of h.lines) n += l.content.length + 4;
  }
  return n;
}
