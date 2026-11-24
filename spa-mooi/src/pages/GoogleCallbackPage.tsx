import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { LoaderCircle } from 'lucide-react';
import { ROUTES } from '@/app/routes';
import { completeGoogleAuthorization } from '@/features/google/api/googleApi';
import { Card } from '@/shared/components/Card';
import { buttonStyles } from '@/shared/styles/buttonStyles';

/** Redeems the code as the authenticated player, never as an identity asserted in the URL. */
export const GoogleCallbackPage = () => {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [failure, setFailure] = useState<string | null>(null);
  const redeemed = useRef(false);
  const accountUrl = `${ROUTES.account}?tab=google`;

  useEffect(() => {
    if (redeemed.current) return;
    redeemed.current = true;
    const code = params.get('code');
    const state = params.get('state');
    const denied = params.get('error');
    // Remove authorization material from the address bar and referrers immediately.
    window.history.replaceState(window.history.state, '', window.location.pathname);
    if (denied || !code || !state) {
      setFailure(denied === 'access_denied'
        ? 'You declined Google authorization. Your existing connections are unchanged.'
        : 'Google did not return a valid authorization. Please try again.');
      return;
    }
    completeGoogleAuthorization(code, state)
      .then(() => navigate(accountUrl, { replace: true }))
      .catch((error: unknown) => setFailure(error instanceof Error ? error.message : 'Could not link Google. Please try again.'));
  }, [params, navigate, accountUrl]);

  return (
    <div className="mx-auto w-full max-w-xl px-5 py-16">
      <Card className="p-6 text-center" >
        {failure ? (
          <>
            <h1 className="text-lg font-semibold text-ink">Google was not connected</h1>
            <p className="mt-3 text-sm text-danger" role="alert">{failure}</p>
            <Link to={accountUrl} className={buttonStyles('primary', 'md', 'mt-6')}>Back to Google accounts</Link>
          </>
        ) : (
          <div role="status">
            <LoaderCircle className="mx-auto size-6 animate-spin text-brand" />
            <p className="mt-4 text-sm text-ink-muted">Linking your Google account…</p>
          </div>
        )}
      </Card>
    </div>
  );
};
