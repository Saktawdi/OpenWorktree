-- V19: 快速模式（项目超级工单）+ 工单状态变更记录（状态变更理由同重启理由）.
--
-- 1) ticket.is_super：每个项目恰有一条系统自动创建的常驻超级工单——直接操作项目原
--    工作区（clone_path 即 workspace_path，不做克隆），永不关闭，不走门禁流转，
--    提交直达主分支。部分唯一索引保证一项目至多一条。
--
-- 2) ticket_restart → ticket_stage_change：重启历史推广为通用的工单状态变更记录。
--    新增 to_stage 列：重启行回 IN_PROGRESS（存量行该列为 NULL，语义等同
--    IN_PROGRESS）；用户强制拖到已完成 / 取消工单同样必须给出理由，各自成行。

ALTER TABLE ticket ADD COLUMN is_super INTEGER NOT NULL DEFAULT 0;

CREATE UNIQUE INDEX idx_ticket_super_project ON ticket(project_id) WHERE is_super = 1;

ALTER TABLE ticket_restart RENAME TO ticket_stage_change;

ALTER TABLE ticket_stage_change ADD COLUMN to_stage TEXT;
