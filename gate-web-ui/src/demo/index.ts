/**
 * 演示模式（demo）公共出口：无后端时的引擎、场景数据与种子。
 */
export { seedDemo } from "./seed";
export {
  demoSendPrompt,
  demoReturnWithFindings,
  demoPresubmit,
  demoReview,
  demoPublish,
  findingsToPromptText,
} from "./engine";
export { DIFF_T104_R1, DIFF_T104_R2, FINDINGS_R1, t102Diff } from "./scenario";
export * as scenario from "./scenario";
