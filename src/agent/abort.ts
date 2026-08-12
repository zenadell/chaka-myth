/**
 * Shared run-control so any in-flight agent turn — including the screen
 * operator's internal loop, which runs inside a tool and can't be passed a
 * signal directly — can be cancelled from the UI.
 */

export class AbortError extends Error {
  constructor() {
    super("Stopped by user");
    this.name = "AbortError";
  }
}

export const runControl: { signal: AbortSignal | null } = { signal: null };

export function isAborted(): boolean {
  return !!runControl.signal?.aborted;
}

export function throwIfAborted(): void {
  if (isAborted()) throw new AbortError();
}

export function isAbortError(err: unknown): boolean {
  return err instanceof AbortError || (err as any)?.name === "AbortError";
}

/** Rejects if `p` doesn't settle within `ms`. Prevents a hung network call from freezing a turn. */
export function withTimeout<T>(p: Promise<T>, ms: number, label: string): Promise<T> {
  return Promise.race([
    p,
    new Promise<T>((_, reject) =>
      setTimeout(() => reject(new Error(`${label} timed out after ${Math.round(ms / 1000)}s`)), ms)
    ),
  ]);
}

/** Sleep that bails out early if the run is aborted. */
export async function abortableSleep(ms: number): Promise<void> {
  const step = 100;
  let waited = 0;
  while (waited < ms) {
    if (isAborted()) throw new AbortError();
    await new Promise((r) => setTimeout(r, Math.min(step, ms - waited)));
    waited += step;
  }
}
