// The timeline's single source of time is the native AudioEngine (Kotlin).
// renderStateAt() is the shape the Kotlin FrameRenderer will receive (Phase 6) for both Preview and Export;
// today only the web preview readout uses it. Waveform data (Phase 4) will plug in at `wave.data`.
export function renderStateAt(project, timeMs, { durationMs, playing } = {}) {
  const audio = project.media.audio;
  const dur = durationMs > 0 ? durationMs : (audio ? audio.durationMs : 0);
  const t = Math.min(Math.max(0, timeMs || 0), dur || 0);
  const bg = project.media.background;
  return {
    timeMs: t,
    durationMs: dur,
    progress: dur > 0 ? t / dur : 0,
    playing: !!playing,
    aspectRatio: project.aspectRatio,
    background: bg ? { file: bg.file, width: bg.width, height: bg.height, motion: project.backgroundMotion } : null,
    wave: { style: project.wave.style, position: project.wave.position, data: null /* Phase 4: analysis track */ },
  };
}
