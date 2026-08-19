# presubmit 能力

状态：Implementing；目标 owner：presubmit；最低实施等级：L2；复核/批准等级：L3。负责工作区快照、tree/base OID、diff Blob 和预提审轮次。当前实现由 GateService 与 Git adapter 共同承担；迁移需保留 TOCTOU 和 agent index 不变不变量。验收：快照、Blob digest、重建和过期工作区。
