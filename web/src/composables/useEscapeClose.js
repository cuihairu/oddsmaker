import { onBeforeUnmount, onMounted } from 'vue'

/**
 * 挂载 window 级 Escape 键监听：当 `isOpen` 为真时按 Esc 调用 `onClose`。
 * 用于让弹窗遮罩可通过键盘关闭（WCAG 2.1：模态对话框需可键盘关闭）。
 *
 * @param {import('vue').Ref} isOpen 弹窗打开状态（布尔 ref 或对象 ref，真值即为打开）
 * @param {() => void} onClose  Escape 触发的关闭动作
 */
export function useEscapeClose(isOpen, onClose) {
  function handler(e) {
    if (e.key === 'Escape' && isOpen.value) {
      onClose()
    }
  }
  onMounted(() => window.addEventListener('keydown', handler))
  onBeforeUnmount(() => window.removeEventListener('keydown', handler))
}
