import { DictationAudioHealth } from '@/features/dictation/lib/DictationAudioHealth';
import { DictationCapture } from '@/features/dictation/lib/DictationCapture';
import { dictationMaxSeconds } from '@/features/dictation/lib/dictationLimits';
import { DICTATION_MESSAGES } from '@/features/dictation/lib/dictationMessages';
import { dictationUrl } from '@/features/dictation/lib/dictationUrl';
import { encodeWav } from '@/features/dictation/lib/encodeWav';
import type { DictationCallbacks } from '@/features/dictation/types/DictationCallbacks';
import type { DictationTranscription } from '@/features/dictation/types/DictationTranscription';
import { microphoneErrorMessage } from '@/features/dictation/lib/microphoneErrorMessage';
import { acquireAudioSession } from '@/features/dictation/lib/audioSession';

const START_TIMEOUT_MS = 15_000;
/** Flushing the capture, uploading the whole recording and transcribing it on the server. */
const TRANSCRIBE_TIMEOUT_MS = 120_000;
const BYTES_PER_SECOND = 32_000;

/**
 * One dictation, recorded whole and transcribed once (as GitHub Copilot's voice input does):
 * microphone → AudioWorklet (native rate in, PCM16 16 kHz frames out) → memory, then on stop a single
 * WAV upload to mic-speech, which answers with the text. Nothing travels while recording. The
 * microphone is requested first, inside the user gesture; the audio engine starts only once it is
 * open. Recording is announced once the microphone is heard; a capture without signal is rebuilt
 * once (fresh microphone and audio engine), then fails. Reaching the service's maximum length stops
 * the recording and transcribes it. Every exit path releases every resource, and once disposed no
 * callback fires again.
 */
export class DictationSession {
  private readonly callbacks: DictationCallbacks;
  private closed = false;
  private stopping = false;
  private heard = false;
  private ready = false;
  private audioFailed = false;
  private maxBytes = Infinity;
  private capturedBytes = 0;
  private frames: ArrayBuffer[] = [];
  private capture: DictationCapture | null = null;
  private readonly upload = new AbortController();
  private timer: number | undefined;
  private releaseAudioSession: (() => void) | null = null;
  private readonly health = new DictationAudioHealth(
    () => this.recoverCapture(),
    () => this.failAudio(DICTATION_MESSAGES.audioNoSignal),
  );

  constructor(callbacks: DictationCallbacks) {
    this.callbacks = callbacks;
  }

  async start(): Promise<void> {
    if (!navigator.mediaDevices?.getUserMedia) {
      this.fail(DICTATION_MESSAGES.insecureContext);
      return;
    }
    this.releaseAudioSession = acquireAudioSession();
    this.arm(START_TIMEOUT_MS, DICTATION_MESSAGES.audioBlocked);
    void dictationMaxSeconds().then((seconds) => {
      this.maxBytes = seconds * BYTES_PER_SECOND;
      this.enforceLimit();
    });
    const capture = this.createCapture();
    try {
      await capture.openMicrophone();
    } catch (error) {
      this.fail(microphoneErrorMessage(error));
      return;
    }
    if (this.closed) return;
    try {
      await capture.startAudio();
    } catch {
      this.failAudio(DICTATION_MESSAGES.audioBlocked);
      return;
    }
    if (!this.closed) this.health.start();
  }

  /**
   * Resumes the audio engine; call it from a user activation (`pointerup`, `click`, `keydown`).
   * Touch `pointerdown` is not an activation, so the engine may still be waiting for one. Before
   * the microphone is open there is nothing to resume: the engine never runs ahead of it.
   */
  resumeAudio(): void {
    if (!this.closed && !this.stopping) this.capture?.resume();
  }

  /** Releases capture synchronously; the worklet then flushes audio already buffered, and it all uploads. */
  stop(): void {
    if (this.closed || this.stopping) return;
    this.stopping = true;
    this.health.stop();
    if (!this.ready) {
      this.dispose();
      this.callbacks.onFinal('');
      return;
    }
    this.callbacks.onStopping();
    this.arm(TRANSCRIBE_TIMEOUT_MS, DICTATION_MESSAGES.finalizeTimeout);
    const capture = this.capture;
    void capture?.stop().then(() => this.finishCapture(capture));
  }

  dispose(): void {
    if (this.closed) return;
    this.closed = true;
    window.clearTimeout(this.timer);
    this.health.stop();
    this.capture?.release(this.audioFailed);
    this.capture = null;
    this.frames = [];
    this.releaseAudioSession?.();
    this.releaseAudioSession = null;
    this.upload.abort();
  }

  private createCapture(): DictationCapture {
    const capture: DictationCapture = new DictationCapture({
      onFrame: (frame) => {
        if (this.capture === capture) this.onFrame(frame);
      },
      onLost: () => {
        if (this.capture === capture && !this.stopping) this.failAudio(DICTATION_MESSAGES.microphoneDisconnected);
      },
    });
    this.capture = capture;
    return capture;
  }

  private onFrame(frame: ArrayBuffer): void {
    if (this.closed) return;
    this.frames.push(frame);
    this.capturedBytes += frame.byteLength;
    if (!this.stopping && this.health.frame(frame) && !this.heard) {
      this.heard = true;
      this.announce();
    }
    this.enforceLimit();
  }

  /** Recording starts once the microphone is heard: it is never shown over a dead microphone. */
  private announce(): void {
    if (this.ready || this.stopping || !this.heard) return;
    window.clearTimeout(this.timer);
    this.ready = true;
    this.callbacks.onReady();
  }

  /** The service rejects longer recordings, so the limit ends the recording and transcribes it. */
  private enforceLimit(): void {
    if (!this.stopping && this.capturedBytes >= this.maxBytes) this.stop();
  }

  /** The proven path of a page's first capture: a new microphone stream on a fresh audio engine. */
  private async recoverCapture(): Promise<void> {
    if (this.closed || this.stopping) return;
    this.capture?.release(true);
    const capture = this.createCapture();
    await capture.openMicrophone();
    await capture.startAudio();
  }

  private finishCapture(capture: DictationCapture | null): void {
    if (this.closed || this.capture !== capture) return;
    capture?.release();
    this.capture = null;
    const recording = encodeWav(this.frames);
    this.frames = [];
    void this.transcribe(recording);
  }

  private async transcribe(recording: Blob): Promise<void> {
    let response: Response;
    try {
      response = await fetch(dictationUrl('transcriptions'), {
        method: 'POST',
        body: recording,
        credentials: 'same-origin',
        signal: this.upload.signal,
      });
    } catch {
      this.fail(DICTATION_MESSAGES.connectFailed);
      return;
    }
    const answer = (await response.json().catch(() => null)) as DictationTranscription | null;
    if (this.closed) return;
    if (response.ok && answer && 'text' in answer && typeof answer.text === 'string') {
      this.dispose();
      this.callbacks.onFinal(answer.text);
    } else if (answer && 'message' in answer && typeof answer.message === 'string') {
      this.fail(answer.message);
    } else {
      this.fail(response.ok ? DICTATION_MESSAGES.invalidResponse : DICTATION_MESSAGES.connectFailed);
    }
  }

  private arm(delay: number, message: string): void {
    window.clearTimeout(this.timer);
    this.timer = window.setTimeout(() => this.fail(message), delay);
  }

  private failAudio(message: string): void {
    this.audioFailed = true;
    this.fail(message);
  }

  private fail(message: string): void {
    if (this.closed) return;
    this.dispose();
    this.callbacks.onError(message);
  }
}
