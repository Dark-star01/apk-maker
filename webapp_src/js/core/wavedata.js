// Wave data = the audio analysis made by Kotlin (analysis/WaveData.kt), decoded once into typed arrays.
//
// Layout: `count` points, `sampleRate` points per second (30). Point i is at time i / sampleRate seconds.
// Four series, one byte per point (0..255 = 0.0..1.0):
//   amplitude  loudness: RMS of the whole signal in that 1/30 s window
//   bass       RMS of the band below ~250 Hz
//   mid        RMS of the 250 Hz .. ~4 kHz band
//   treble     RMS of the band above ~4 kHz
// Every series is relative to ITS OWN loud level in this track (99th percentile = 1.0): it says "how loud is this
// moment compared with the loud parts of the same band", not absolute loudness. Silence stays 0.
//
// Memory: 4 bytes per point (3 min = ~21 KB). point(i) builds the {time, amplitude, bass, mid, treble} shape
// on demand; nothing creates one object per point.

export function decodeWaveData(res) {
  const n = Number(res && res.count);
  const sampleRate = Number(res && res.sampleRate);
  if (!Number.isInteger(n) || n <= 0 || !(sampleRate > 0) || typeof res.data !== 'string') throw badData();
  const bin = atob(res.data);
  if (bin.length !== n * 4) throw badData();
  const bytes = new Uint8Array(n * 4);
  for (let i = 0; i < bytes.length; i++) bytes[i] = bin.charCodeAt(i);
  const amplitude = bytes.subarray(0, n);
  const bass = bytes.subarray(n, 2 * n);
  const mid = bytes.subarray(2 * n, 3 * n);
  const treble = bytes.subarray(3 * n, 4 * n);
  return {
    sampleRate,
    count: n,
    durationMs: Number(res.durationMs) || Math.round((n * 1000) / sampleRate),
    amplitude, bass, mid, treble,
    indexAt(ms) { return Math.min(n - 1, Math.max(0, Math.floor((ms / 1000) * sampleRate))); },
    point(i) {
      return { time: i / sampleRate, amplitude: amplitude[i] / 255, bass: bass[i] / 255, mid: mid[i] / 255, treble: treble[i] / 255 };
    },
  };
}

function badData() {
  return Object.assign(new Error('Bad wave data'), { code: 'ANALYSIS_FAILED' });
}
