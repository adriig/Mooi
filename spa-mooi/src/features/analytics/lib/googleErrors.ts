export type GoogleErrorAction = 'connect' | 'relink' | 'pick' | 'retry';

export interface GoogleErrorState {
  title: string;
  description: string;
  action: GoogleErrorAction;
  actionLabel: string;
}

/** Raised by the Google-backed reports; `code` is the API's `google_*` reason. */
export class GoogleReportError extends Error {
  readonly code: string | null;

  constructor(message: string, code: string | null) {
    super(message);
    this.code = code;
  }
}

const STATES: Record<string, GoogleErrorState> = {
  google_not_connected: {
    title: 'Google is not connected',
    description: 'Connect a Google account to read this project’s Analytics and Search Console data.',
    action: 'connect',
    actionLabel: 'Connect Google',
  },
  google_reauth_required: {
    title: 'Google access expired',
    description: 'Google revoked or expired this link. Re-link the account to keep reading your data.',
    action: 'relink',
    actionLabel: 'Re-link Google',
  },
  google_permission_denied: {
    title: 'No permission for this property',
    description: 'The linked Google account cannot read the selected property. Pick another one, or link an account that can.',
    action: 'pick',
    actionLabel: 'Pick another property',
  },
  google_quota_exceeded: {
    title: 'Google quota reached',
    description: 'Google is rate-limiting requests for now. Reports are cached; try again in a few minutes.',
    action: 'retry',
    actionLabel: 'Try again',
  },
  google_property_not_selected: {
    title: 'No property selected',
    description: 'Choose the property to report on in the Google setup.',
    action: 'pick',
    actionLabel: 'Choose a property',
  },
  google_api_disabled: {
    title: 'Google API disabled',
    description: 'The Google API behind this report is not enabled for the Mooi project. Ask an operator to enable it, then try again.',
    action: 'retry',
    actionLabel: 'Try again',
  },
};

const FALLBACK: GoogleErrorState = {
  title: 'Could not load Google data',
  description: 'Something went wrong while reading Google. Try again.',
  action: 'retry',
  actionLabel: 'Try again',
};

export const googleErrorState = (code: string | null, message?: string): GoogleErrorState =>
  (code && STATES[code]) || { ...FALLBACK, description: message && !message.startsWith('google_') ? message : FALLBACK.description };
