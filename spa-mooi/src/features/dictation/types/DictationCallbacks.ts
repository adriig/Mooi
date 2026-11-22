export interface DictationCallbacks {
  onReady: () => void;
  onStopping: () => void;
  onFinal: (text: string) => void;
  onError: (message: string) => void;
}
