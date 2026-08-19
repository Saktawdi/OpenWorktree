# ticket 能力

状态：Implementing；目标 owner：ticket；最低实施等级：L2；复核/批准等级：L3。负责工单创建、编辑、查询和生命周期状态机。当前 API 为 `/api/projects/{id}/tickets/**`；事实表为 `ticket`。review/publish 不得直接写 stage，目标使用 ticket transition port。验收需覆盖状态机、幂等、RBAC、审计和契约测试。
