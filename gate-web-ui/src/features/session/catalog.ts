/**
 * 会话域模型目录（session）：会话 serve 的 live 模型目录加载、选择种子与
 * 会话内实时切换（provider/model/variant 覆盖）。
 */
import { api } from "@/net";
import { appStore, showToast } from "@/store";
import { t } from "@/i18n";
import type { CatalogProvider } from "@/shared/types";
import {
  numericLimit,
  type RawCatalogProvider,
} from "./model";
import { setContextLimit, setSessionModelSel, setSessionModels } from "./state";
import { ticketNoOfSession } from "./api";

/**
 * Loads the live model catalog for the session's opencode serve and seeds the
 * picker selection: persisted session override first, then the AgentConfig
 * default (provider/model), mirroring OpenChamber's restore order.
 */
export async function loadSessionCatalog(no: string, sessionId: string) {
  const st = appStore.getState();
  try {
    const data = await api<{ providers: RawCatalogProvider[] }>(`/api/sessions/${sessionId}/models`);
    const providers: CatalogProvider[] = (data.providers ?? []).map((p) => ({
      id: p.id,
      name: p.name ?? p.id,
      models: (p.models ?? []).map((m) => ({
        id: m.id,
        name: m.name ?? m.id,
        variants: m.variants ?? [],
        imageInput: m.image_input === true,
        contextLimit: numericLimit(m.limit?.context),
        outputLimit: numericLimit(m.limit?.output),
      })),
    }));
    setSessionModels(sessionId, providers);
    // Seed the selection from the persisted session override when present.
    const sess = (st.sessions[no] ?? []).find((s) => s.id === sessionId);
    if (sess?.overrideProvider && sess.overrideModel) {
      setSessionModelSel(sessionId, {
        providerId: sess.overrideProvider,
        modelId: sess.overrideModel,
        variant: sess.overrideVariant ?? null,
      });
    }
    applySessionContextLimit(no, sessionId, providers);
  } catch {
    /* catalog is best-effort: the picker just stays empty */
  }
}

/**
 * 从目录中解析当前会话生效模型的上下文窗口上限并写入 store；
 * 找不到生效模型时保持 null（前端回退默认窗口）。
 */
function applySessionContextLimit(no: string, sessionId: string, providers: CatalogProvider[]) {
  const st = appStore.getState();
  const sel = st.sessionModelSel[sessionId];
  const sess = (st.sessions[no] ?? []).find((s) => s.id === sessionId);
  const agent = st.agents.find((a) => a.id === (sess?.agentConfigId ?? st.agentId));
  const providerId = sel?.providerId ?? sess?.overrideProvider ?? agent?.providerId ?? null;
  const modelId = sel?.modelId ?? sess?.overrideModel ?? agent?.model ?? null;
  if (!providerId || !modelId) return;
  const model = providers
    .find((p) => p.id === providerId)
    ?.models.find((m) => m.id === modelId);
  if (model?.contextLimit) setContextLimit(no, model.contextLimit);
}

/** Live switch: persists the override server-side; takes effect on the NEXT turn. */
export async function switchSessionModelLive(
  sessionId: string,
  sel: { providerId: string | null; modelId: string | null; variant: string | null },
): Promise<boolean> {
  try {
    await api(`/api/sessions/${sessionId}/model`, {
      method: "POST",
      body: JSON.stringify({
        provider_id: sel.providerId ?? undefined,
        model_id: sel.modelId ?? undefined,
        variant: sel.variant ?? undefined,
      }),
    });
    setSessionModelSel(sessionId, sel);
    // 模型切换后上下文窗口随之变化：从目录解析新上限（找不到则回退 null）。
    const no = ticketNoOfSession(sessionId);
    if (no) {
      const model = appStore
        .getState()
        .sessionModels[sessionId]?.find((p) => p.id === sel.providerId)
        ?.models.find((m) => m.id === sel.modelId);
      setContextLimit(no, model?.contextLimit ?? null);
    }
    return true;
  } catch (e) {
    showToast(t("sess.switchFailed", { err: (e as Error).message }));
    return false;
  }
}
