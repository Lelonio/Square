//! LOCAL PATCH: local files at other rates and channel counts.
//!
//! The player runs at 44.1 kHz stereo throughout, which is everything Spotify
//! streams. A file on a phone is as often 48 kHz, and sometimes mono, and the
//! decoder used to refuse both. This brings such a file to the player's format:
//! a windowed-sinc resampler, cut off below the lower of the two Nyquist
//! frequencies so a downsample does not alias, and a mono channel played on
//! both sides.

use std::f64::consts::PI;

/// Taps on each side of the point being interpolated.
const HALF_TAPS: usize = 16;

pub struct Resampler {
    /// Input samples per output sample.
    step: f64,
    /// Below 1 when downsampling: the kernel is widened by its inverse.
    cutoff: f64,
    channels: usize,
    /// Input not yet consumed, per output channel, after [HALF_TAPS] of lead.
    history: [Vec<f64>; 2],
    /// Where the next output sample falls, in input samples into [history].
    position: f64,
}

impl Resampler {
    pub fn new(input_rate: u32, output_rate: u32, input_channels: usize) -> Self {
        let step = input_rate as f64 / output_rate as f64;
        let mut resampler = Self {
            step,
            cutoff: (1.0 / step).min(1.0) * 0.97,
            channels: input_channels,
            history: [Vec::new(), Vec::new()],
            position: 0.0,
        };
        resampler.reset();
        resampler
    }

    /// After a seek: nothing before the new position belongs to it.
    pub fn reset(&mut self) {
        for channel in &mut self.history {
            channel.clear();
            channel.resize(HALF_TAPS, 0.0);
        }
        self.position = HALF_TAPS as f64;
    }

    /// Interleaved input in, interleaved 44.1 kHz stereo out.
    pub fn process(&mut self, input: &[f64]) -> Vec<f64> {
        let channels = self.channels.max(1);
        for frame in input.chunks_exact(channels) {
            let left = frame[0];
            let right = if channels > 1 { frame[1] } else { left };
            self.history[0].push(left);
            self.history[1].push(right);
        }

        let available = self.history[0].len();
        let mut output = Vec::with_capacity(((input.len() / channels) as f64 / self.step) as usize * 2 + 4);
        while self.position + (HALF_TAPS as f64) < available as f64 {
            let centre = self.position.floor() as usize;
            for channel in &self.history {
                let mut sum = 0.0;
                for k in centre + 1 - HALF_TAPS..=centre + HALF_TAPS {
                    sum += channel[k] * self.kernel(self.position - k as f64);
                }
                output.push(sum);
            }
            self.position += self.step;
        }

        // Keep only what the next call can still need.
        let drop = (self.position.floor() as usize).saturating_sub(HALF_TAPS);
        if drop > 0 {
            for channel in &mut self.history {
                channel.drain(..drop.min(channel.len()));
            }
            self.position -= drop as f64;
        }
        output
    }

    fn kernel(&self, distance: f64) -> f64 {
        let reach = HALF_TAPS as f64;
        if distance.abs() >= reach {
            return 0.0;
        }
        let x = distance * self.cutoff;
        let sinc = if x.abs() < 1e-9 { 1.0 } else { (PI * x).sin() / (PI * x) };
        // Blackman, across the kernel's width.
        let w = 0.5 + 0.5 * distance / reach;
        let window = 0.42 - 0.5 * (2.0 * PI * w).cos() + 0.08 * (4.0 * PI * w).cos();
        self.cutoff * sinc * window
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A 1 kHz tone at 48 kHz comes out as a 1 kHz tone at 44.1 kHz, at the
    /// same level, in the number of samples the rates call for.
    #[test]
    fn resamples_a_tone() {
        let input: Vec<f64> = (0..48_000)
            .flat_map(|n| {
                let v = (2.0 * PI * 1000.0 * n as f64 / 48_000.0).sin() * 0.5;
                [v, v]
            })
            .collect();
        let mut resampler = Resampler::new(48_000, 44_100, 2);
        let mut output = Vec::new();
        for chunk in input.chunks(2 * 1152) {
            output.extend(resampler.process(chunk));
        }
        let frames = output.len() / 2;
        assert!((frames as i64 - 44_100).abs() < 64, "{frames} frames");

        // Compare against the ideal tone away from the edges.
        let worst = (2_000..40_000)
            .map(|n| {
                // Output n is the input at n / 44 100 s: no delay, the lead
                // of zeros is consumed before the first sample.
                let ideal = (2.0 * PI * 1000.0 * n as f64 / 44_100.0).sin() * 0.5;
                (output[n * 2] - ideal).abs()
            })
            .fold(0.0, f64::max);
        assert!(worst < 0.01, "worst error {worst}");
    }

    /// A mono file is played on both sides.
    #[test]
    fn mono_to_stereo() {
        let mut resampler = Resampler::new(44_100, 44_100, 1);
        let output = resampler.process(&vec![0.25; 4_096]);
        assert!(output.chunks_exact(2).skip(64).all(|f| (f[0] - f[1]).abs() < 1e-12));
    }
}
