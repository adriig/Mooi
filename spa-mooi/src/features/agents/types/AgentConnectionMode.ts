/** How the credential behind a connection was obtained: full OAuth2, a pasted setup token, or a sign-in
 * owned by the provider's own binary (Codex device code, Antigravity Google sign-in), or an OpenCode account (Go included) signed in
 * through the OpenCode CLI's device flow. */
export type AgentConnectionMode = 'oauth' | 'setup_token' | 'device_oauth' | 'agent_oauth' | 'api_key' | 'account';
