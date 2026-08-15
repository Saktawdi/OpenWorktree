/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 'false' 时关闭 MSW mock, 切真后端. 其他值或缺省 = 开启 mock. */
  readonly VITE_ENABLE_MSW?: string;
  /** 真后端 URL (默认 http://127.0.0.1:4097), dev proxy target. */
  readonly VITE_BACKEND_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
