// An error the client receives as { "error": code, "message": ... } with this HTTP status.
export type ErrorStatus = 400 | 401 | 403 | 404 | 409 | 413 | 429 | 500 | 503;

export class ApiError extends Error {
  constructor(
    readonly status: ErrorStatus,
    readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

// D1's wording when a free-plan daily cap is hit: "Your account has exceeded D1's free tier daily row read limit."
// Durable Object caps need their own wording, checked when the party room is built.
const QUOTA_MESSAGE = /exceeded .*free tier daily/i;

// True for a platform error caused by a free-plan daily cap. Our own ApiError values never count.
export function isQuotaError(err: unknown): boolean {
  return err instanceof Error && !(err instanceof ApiError) && QUOTA_MESSAGE.test(err.message);
}
