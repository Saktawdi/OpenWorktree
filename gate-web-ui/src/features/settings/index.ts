/**
 * 设置域（settings）公共出口：gate.toml/MCP/LLM Provider/应用信息 API 与设置页组件。
 */
export {
  fetchGateToml,
  updateGateToml,
  fetchMcpStatus,
  fetchProviders,
  createProvider,
  updateProvider,
  deleteProvider,
  setProviderCredential,
  clearProviderCredential,
  updateProviderModels,
  fetchUpstreamModels,
  probeUpstreamModels,
} from "./api";
export { fetchAppInfo, checkAppUpdate, fetchUpdateNotes } from "./app";
export {
  fetchStorageOverview,
  fetchStorageCaches,
  cleanStorageCache,
  fetchStorageWorkspaces,
  pruneStorageWorkspace,
  openStorageDir,
} from "./storage";
export { SettingsPage } from "./components/SettingsPage";
