/** mic-speech's answer to one uploaded recording: the text, or a user-facing failure. */
export type DictationTranscription = { text: string } | { message: string };
