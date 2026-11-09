/** Toast 的唯一调用入口（DESIGN §5.12）：useToast().success('已保存') */
import { useUiStore, type ToastTone } from '@/stores/ui'

interface ToastOptions {
  tone?: ToastTone
  duration?: number
}

export function useToast() {
  const ui = useUiStore()

  function show(message: string, options: ToastOptions = {}): number {
    return ui.pushToast({ message, tone: options.tone ?? 'info', duration: options.duration })
  }

  return {
    show,
    success: (message: string, duration?: number) => show(message, { tone: 'success', duration }),
    error: (message: string, duration?: number) => show(message, { tone: 'danger', duration }),
    warning: (message: string, duration?: number) => show(message, { tone: 'warning', duration }),
    info: (message: string, duration?: number) => show(message, { tone: 'info', duration }),
    dismiss: ui.dismissToast,
  }
}
