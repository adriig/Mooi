export interface GoogleConnection {
  id: string;
  email: string;
  scopes: string[];
  linkedAt: string;
  reauthRequired: boolean;
}
