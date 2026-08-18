/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 'true' 时开启 MSW mock (UI 原型开发). 其他值或缺省 = 直连真实后端. */
  readonly VITE_ENABLE_MSW?: string;
  /** 真后端 URL (默认 http://127.0.0.1:4097), dev proxy target. */
  readonly VITE_BACKEND_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
