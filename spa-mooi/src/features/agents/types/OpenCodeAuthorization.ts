export interface OpenCodeAuthorization {
  attemptId: string;
  verificationUrl: string;
  userCode: string;
  expiresIn: number;
}
