import { useEffect } from "react";
import { clearToast, useApp } from "@/store";

export function Toast() {
  const toast = useApp((s) => s.toast);

  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(clearToast, 2400);
    return () => clearTimeout(t);
  }, [toast?.id, toast?.nonce]);

  if (!toast) return null;

  return (
    <div className="fixed bottom-6 left-1/2 -translate-x-1/2 z-50 animate-rise pointer-events-none">
      <div className="rounded-lg border border-edge-strong bg-overlay px-4 py-2 text-[13px] shadow-xl shadow-black/40">
        {toast.text}
      </div>
    </div>
  );
}
