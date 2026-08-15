<script setup lang="ts">
import { GButton, GCard, GBadge, GInput, GSelect } from '@/components/ui';
</script>

<template>
  <div class="sg">
    <div class="sg__head">
      <h2>组件 / 样式速览</h2>
      <p>自建 UI 库 · Multica-like 视觉</p>
    </div>

    <GCard title="按钮">
      <div class="row">
        <GButton variant="primary">主要</GButton>
        <GButton variant="secondary">次要</GButton>
        <GButton variant="ghost">幽灵</GButton>
        <GButton variant="danger">危险</GButton>
        <GButton variant="success">通过</GButton>
        <GButton variant="primary" disabled>禁用</GButton>
      </div>
    </GCard>

    <GCard title="徽章">
      <div class="row">
        <GBadge tone="neutral">neutral</GBadge>
        <GBadge tone="accent">accent</GBadge>
        <GBadge tone="success">success</GBadge>
        <GBadge tone="warning">warning</GBadge>
        <GBadge tone="danger">danger</GBadge>
        <GBadge tone="success" dot>healthy</GBadge>
      </div>
    </GCard>

    <GCard title="表单">
      <div class="form">
        <GInput placeholder="输入框" />
        <GSelect placeholder="请选择" :options="[{ label: 'A', value: 'a' }, { label: 'B', value: 'b' }]" />
      </div>
    </GCard>

    <GCard title="状态">
      <div class="row">
        <div class="skeleton" style="width: 180px" />
        <div class="empty">暂无数据</div>
        <div class="error">令牌无效或已撤销</div>
      </div>
    </GCard>
  </div>
</template>

<style scoped>
.sg {
  flex: 1;
  min-height: 0;
  width: min(100%, 1180px);
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 24px clamp(18px, 3vw, 38px) 32px;
  overflow: auto;
  color: var(--text, #172536);
}
.sg__head {
  display: grid;
  gap: 4px;
  padding: 2px 2px 8px;
}
.sg__head::before {
  content: 'FOUNDATION  /  LOCAL UI';
  color: var(--accent-hover, #c9571f);
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.12em;
}
.sg__head h2 {
  margin: 0;
  color: var(--ink, #10263d);
  font-size: 22px;
  font-weight: 800;
  letter-spacing: -0.03em;
}
.sg__head p {
  margin: 2px 0 0;
  color: var(--text-muted, #718092);
  font-size: 13px;
}
.sg > :deep(.g-card) {
  border-radius: 14px;
  border-color: var(--border, #e4ddd3);
  background: var(--panel, #fffdfa);
  box-shadow: 0 9px 24px rgba(36, 52, 70, 0.055);
}
.sg > :deep(.g-card) :deep(.g-card__head) {
  min-height: 50px;
}
.row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.row :deep(.g-btn) {
  border-radius: 9px;
}
.row :deep(.g-badge) {
  padding: 4px 9px;
  font-size: 11px;
}
.form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
.form :deep(.g-input),
.form :deep(.g-select) {
  min-height: 40px;
  border-radius: 9px;
}
.skeleton {
  height: 14px;
  border-radius: 999px;
  background: linear-gradient(100deg, var(--panel-2, #f7f2ea) 20%, rgba(176, 75, 28, 0.25) 42%, var(--panel-2, #f7f2ea) 64%);
  background-size: 360% 100%;
  animation: shimmer 1.4s ease infinite;
}
@keyframes shimmer {
  0% { background-position: 100% 0; }
  100% { background-position: 0 0; }
}
.empty {
  padding: 11px 14px;
  border: 1px dashed var(--border-strong, #cbbfaf);
  border-radius: 9px;
  color: var(--text-muted, #718092);
  background: var(--panel-2, #f7f2ea);
  font-size: 13px;
}
.error {
  padding: 10px 12px;
  color: var(--danger, #c2534a);
  background: var(--danger-soft, rgba(194, 83, 74, 0.1));
  border-radius: 9px;
  font-size: 13px;
  font-weight: 650;
}
@media (max-width: 680px) {
  .sg {
    padding: 18px 14px 24px;
  }
  .form {
    grid-template-columns: 1fr;
  }
}
</style>
