/**
 * 英文（en）字典聚合：域文件与 zh-CN 一一对应，这里合并为单一扁平字典。
 * 类型收口 Record<MsgKey, string>：zh-CN 有而 en 缺的键在编译期报错。
 */
import type { MsgKey } from "../zh-CN";
import { enCommon } from "./common";
import { enTicket } from "./ticket";
import { enGate } from "./gate";
import { enSettings } from "./settings";
import { enSession } from "./session";
import { enProject } from "./project";
import { enAgent } from "./agent";
import { enAssistant } from "./assistant";
import { enPlugins } from "./plugins";

export const en: Record<MsgKey, string> = {
  ...enCommon,
  ...enTicket,
  ...enGate,
  ...enSettings,
  ...enSession,
  ...enProject,
  ...enAgent,
  ...enAssistant,
  ...enPlugins,
};
