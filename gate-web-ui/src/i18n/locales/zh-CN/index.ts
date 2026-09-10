/**
 * 默认语言（zh-CN）字典聚合：按域拆分维护，这里合并为单一扁平字典。
 * MsgKey 是全站文案键的联合类型真源。
 */
import { zhCommon } from "./common";
import { zhTicket } from "./ticket";
import { zhGate } from "./gate";
import { zhSettings } from "./settings";
import { zhSession } from "./session";
import { zhProject } from "./project";
import { zhAgent } from "./agent";
import { zhAssistant } from "./assistant";
import { zhPlugins } from "./plugins";
import { zhOnboarding } from "./onboarding";

export const zhCN = {
  ...zhCommon,
  ...zhTicket,
  ...zhGate,
  ...zhSettings,
  ...zhSession,
  ...zhProject,
  ...zhAgent,
  ...zhAssistant,
  ...zhPlugins,
  ...zhOnboarding,
} as const;

export type MsgKey = keyof typeof zhCN;
