// Alarm tone generated with Web Audio, so there is no sound file to ship.
// It plays as a looping buffer so it keeps going even when timers are throttled
// in a background tab. Browsers only allow audio after a user click: call
// enable() from a click handler first.

const PATTERN_SECONDS = 1.2;

export class Alarm {
  private ctx: AudioContext | null = null;
  private buffer: AudioBuffer | null = null;
  private source: AudioBufferSourceNode | null = null;

  get ready(): boolean {
    return this.ctx !== null && this.ctx.state === 'running';
  }

  /** Must be called from a user gesture. Resolves to true if audio is available. */
  async enable(): Promise<boolean> {
    try {
      const Ctx = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
      if (!Ctx) return false;
      if (!this.ctx) this.ctx = new Ctx();
      await this.ctx.resume();
      this.buffer ??= this.render(this.ctx);
      return this.ctx.state === 'running';
    } catch {
      return false;
    }
  }

  start(): void {
    if (this.source || !this.ctx || !this.buffer) return;
    const src = this.ctx.createBufferSource();
    src.buffer = this.buffer;
    src.loop = true;
    src.connect(this.ctx.destination);
    src.start();
    this.source = src;
  }

  stop(): void {
    if (!this.source) return;
    try {
      this.source.stop();
    } catch {
      /* already stopped */
    }
    this.source.disconnect();
    this.source = null;
  }

  /** Two-tone pulse (880 Hz then 660 Hz) followed by a short pause. */
  private render(ctx: AudioContext): AudioBuffer {
    const rate = ctx.sampleRate;
    const buf = ctx.createBuffer(1, Math.floor(rate * PATTERN_SECONDS), rate);
    const data = buf.getChannelData(0);
    const tones: [number, number, number][] = [
      [880, 0.0, 0.28],
      [660, 0.34, 0.28],
    ];
    for (const [freq, start, dur] of tones) {
      const from = Math.floor(start * rate);
      const len = Math.floor(dur * rate);
      const fade = Math.floor(0.015 * rate);
      for (let i = 0; i < len; i++) {
        const t = i / rate;
        const env = Math.min(1, i / fade, (len - i) / fade);
        // Fundamental plus a third harmonic for a harder, more noticeable tone.
        data[from + i] = env * 0.32 * (Math.sin(2 * Math.PI * freq * t) + 0.3 * Math.sin(6 * Math.PI * freq * t));
      }
    }
    return buf;
  }
}
