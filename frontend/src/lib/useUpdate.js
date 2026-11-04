import { useEffect, useState } from 'react'
import {
  UPDATE_EVENT,
  getUpdateState,
  hasUnseenNotice,
  isDismissed,
  currentNoticeKey,
} from './updateStore'

// Re-renders whenever the update state changes. `notice` is true while
// there is something the user should hear about (a new APK, or a UI update
// that finished downloading), `dismissed` means they already tapped "Later".
export function useUpdate() {
  const [, bump] = useState(0)
  useEffect(() => {
    const on = () => bump((n) => n + 1)
    window.addEventListener(UPDATE_EVENT, on)
    return () => window.removeEventListener(UPDATE_EVENT, on)
  }, [])
  const s = getUpdateState()
  return {
    ...s,
    notice: !!currentNoticeKey(),
    unseen: hasUnseenNotice(),
    dismissed: isDismissed(),
  }
}
