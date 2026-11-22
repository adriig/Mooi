const SAMPLE_RATE = 16000;
const HEADER_BYTES = 44;

/** Wraps PCM16 mono 16 kHz frames, in capture order, into one uncompressed RIFF/WAVE file. */
export const encodeWav = (frames: readonly ArrayBuffer[]): Blob => {
  const dataBytes = frames.reduce((total, frame) => total + frame.byteLength, 0);
  const header = new DataView(new ArrayBuffer(HEADER_BYTES));
  const ascii = (offset: number, text: string) => {
    for (let index = 0; index < text.length; index += 1) header.setUint8(offset + index, text.charCodeAt(index));
  };
  ascii(0, 'RIFF');
  header.setUint32(4, HEADER_BYTES - 8 + dataBytes, true);
  ascii(8, 'WAVE');
  ascii(12, 'fmt ');
  header.setUint32(16, 16, true);
  header.setUint16(20, 1, true); // PCM
  header.setUint16(22, 1, true); // mono
  header.setUint32(24, SAMPLE_RATE, true);
  header.setUint32(28, SAMPLE_RATE * 2, true);
  header.setUint16(32, 2, true);
  header.setUint16(34, 16, true);
  ascii(36, 'data');
  header.setUint32(40, dataBytes, true);
  return new Blob([header.buffer, ...frames], { type: 'audio/wav' });
};
