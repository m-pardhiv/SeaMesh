import { useEffect, useState } from 'react';

/** Current time (adjusted by a clock offset), re-rendering every second. */
export function useNow(offsetMs = 0): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, []);
  return now + offsetMs;
}
