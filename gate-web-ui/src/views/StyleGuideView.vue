<script setup lang="ts">
import { GAvatar, GBadge, GButton, GCard, GCheckbox, GEmpty, GField, GIcon, GInput, GSelect, GSkeleton, GTooltip } from '@/components/ui';
import { ref } from 'vue';

const demoCheck = ref(true);
const demoSwitch = ref(false);
</script>

<template>
  <div class="sg">
    <GCard title="按钮" subtitle="五种语义变体，两档尺寸，加载与禁用状态">
      <div class="row">
        <GButton variant="primary">主要</GButton>
        <GButton variant="secondary">次要</GButton>
        <GButton variant="ghost">幽灵</GButton>
        <GButton variant="danger">危险</GButton>
        <GButton variant="success">通过</GButton>
        <GButton variant="primary" disabled>禁用</GButton>
        <GButton variant="primary" loading>提交中</GButton>
        <GButton variant="secondary" size="sm">小尺寸</GButton>
      </div>
    </GCard>

    <GCard title="徽章与头像" subtitle="状态标签与操作者标记">
      <div class="row">
        <GBadge tone="neutral">中性</GBadge>
        <GBadge tone="accent">强调</GBadge>
        <GBadge tone="success">成功</GBadge>
        <GBadge tone="warning">警告</GBadge>
        <GBadge tone="danger">危险</GBadge>
        <GBadge tone="success" dot>健康</GBadge>
        <span class="row__sep" aria-hidden="true" />
        <GAvatar name="Gate" tone="accent" />
        <GAvatar name="Reviewer" tone="success" />
        <GAvatar name="Ops" tone="warning" />
        <GAvatar name="local" tone="neutral" />
        <GIcon name="git-branch" :size="16" />
        <GTooltip content="悬停或聚焦可见的提示" side="top">
          <GButton variant="ghost" size="sm">悬停提示</GButton>
        </GTooltip>
      </div>
    </GCard>

    <GCard title="表单" subtitle="统一的标签、提示、校验与聚焦状态">
      <div class="form">
        <GField label="输入框" for-id="styleguide-input" hint="统一的标签、提示和聚焦状态。">
          <GInput id="styleguide-input" placeholder="输入框" />
        </GField>
        <GField label="选择器" for-id="styleguide-select" hint="用分段或原生选择器承载短选项列表。">
          <GSelect id="styleguide-select" placeholder="请选择" :options="[{ label: 'A', value: 'a' }, { label: 'B', value: 'b' }]" />
        </GField>
        <GField label="校验错误" for-id="styleguide-invalid" error="该字段不能为空。" required>
          <GInput id="styleguide-invalid" aria-invalid placeholder="必填字段" />
        </GField>
        <div class="form__checks">
          <GCheckbox v-model="demoCheck">复选框</GCheckbox>
          <GCheckbox v-model="demoSwitch" variant="switch">开关</GCheckbox>
        </div>
      </div>
    </GCard>

    <GCard title="加载与空态" subtitle="骨架屏匹配最终布局；空态给出下一步动作">
      <div class="states">
        <div class="states__col">
          <GSkeleton variant="text" width="46%" height="14px" />
          <GSkeleton variant="text" :lines="3" />
          <div class="states__skeleton-row">
            <GSkeleton variant="circle" />
            <GSkeleton variant="text" width="52%" />
          </div>
        </div>
        <GEmpty icon="folder" title="暂无数据" hint="创建第一个项目后，这里会展示它的工单与运行指标。">
          <GButton variant="secondary" size="sm">创建项目</GButton>
        </GEmpty>
      </div>
    </GCard>

    <GCard title="错误提示" subtitle="内联错误用于表单，柔和面板用于页面级状态">
      <div class="row">
        <div class="error">令牌无效或已撤销</div>
        <div class="error error--warning">配置未持久化，重启后将恢复默认值</div>
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
  color: var(--text);
}
.row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.row__sep {
  width: 1px;
  height: 20px;
  background: var(--border);
}
.form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}
.form__checks {
  display: flex;
  align-items: center;
  gap: 22px;
  align-self: end;
  padding-bottom: 6px;
}
.states {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 18px;
  align-items: start;
}
.states__col {
  display: grid;
  gap: 10px;
}
.states__skeleton-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.states__skeleton-row .g-skeleton--text {
  flex: 1;
}
.error {
  padding: 10px 12px;
  color: var(--danger);
  background: var(--danger-soft);
  border-radius: var(--radius-sm);
  font-size: 13px;
  font-weight: 650;
}
.error--warning {
  color: var(--warning);
  background: var(--warning-soft);
}
@media (max-width: 680px) {
  .sg {
    padding: 18px 14px 24px;
  }
  .form,
  .states {
    grid-template-columns: 1fr;
  }
}
</style>
