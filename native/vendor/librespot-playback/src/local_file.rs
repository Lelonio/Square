use crate::{
    NUM_CHANNELS, SAMPLE_RATE,
    decoder::{AudioDecoder, AudioPacket, SymphoniaDecoder},
    player::NormalisationData,
    symphonia_util,
};
use librespot_core::{Error, SpotifyUri};
use std::{
    collections::HashMap,
    fs,
    fs::File,
    io,
    path::{Path, PathBuf},
    time::Duration,
};
use symphonia::core::{
    formats::FormatOptions,
    io::MediaSourceStream,
    meta::{MetadataOptions, StandardTagKey, Tag},
    probe::{Hint, ProbeResult},
};

// "Spotify supports .mp3, .mp4, and .m4p files. It doesn’t support .mp4 files that contain video,
// or the iTunes lossless format (M4A)."
// https://community.spotify.com/t5/FAQs/Local-Files/ta-p/5186118
//
// There are some indications online that FLAC is supported, so check for this as well.
const SUPPORTED_FILE_EXTENSIONS: &[&str; 4] = &["mp3", "mp4", "m4p", "flac"];

#[derive(Default)]
pub struct LocalFileLookup(HashMap<SpotifyUri, PathBuf>);

impl LocalFileLookup {
    pub fn get(&self, uri: &SpotifyUri) -> Option<&Path> {
        self.0.get(uri).map(|p| p.as_path())
    }

    /// LOCAL PATCH: the app's own match first, then the scanned directories.
    pub fn path_for(&self, uri: &SpotifyUri) -> Option<PathBuf> {
        APP_FILES
            .read()
            .ok()
            .and_then(|files| files.as_ref()?.get(uri).cloned())
            .or_else(|| self.get(uri).map(Path::to_path_buf))
    }
}

/// LOCAL PATCH: which file each local entry of a playlist is, as the app
/// matched it.
///
/// The scan below wants a file's tags to spell out the entry exactly, which a
/// copy on a phone rarely does; the app matches more forgivingly and with the
/// phone's own media index, and hands the result over here.
static APP_FILES: std::sync::RwLock<Option<HashMap<SpotifyUri, PathBuf>>> =
    std::sync::RwLock::new(None);

/// LOCAL PATCH: replaces the app's matches; see [APP_FILES].
///
/// Files not measured yet are measured in the background, so they are at the
/// level of the streamed tracks by the time they play; see [loudness].
pub fn set_app_files(files: HashMap<SpotifyUri, PathBuf>) {
    let pending: Vec<PathBuf> = files
        .values()
        .filter(|path| measured(path).is_none())
        .cloned()
        .collect();
    if let Ok(mut current) = APP_FILES.write() {
        *current = Some(files);
    }
    if !pending.is_empty() {
        let _ = std::thread::Builder::new()
            .name("local-loudness".into())
            .spawn(move || {
                for path in pending {
                    let _ = loudness(&path);
                }
            });
    }
}

/// LOCAL PATCH: how loud each local file is, once measured.
static LOUDNESS: std::sync::RwLock<Option<HashMap<PathBuf, NormalisationData>>> =
    std::sync::RwLock::new(None);

/// The loudness Spotify normalises its own tracks to, in LUFS: a file is given
/// the gain that brings it there, as a Spotify track is by the gain it carries.
const TARGET_LUFS: f64 = -14.0;

fn measured(path: &Path) -> Option<NormalisationData> {
    LOUDNESS.read().ok()?.as_ref()?.get(path).copied()
}

/// LOCAL PATCH: normalisation data for a local file that has no ReplayGain
/// tags of its own: the gain to [TARGET_LUFS] from its integrated loudness
/// (EBU R128), and its sample peak. Measured once, by decoding the whole file,
/// then remembered.
///
/// Without it a local file played at whatever level it was mastered at, next
/// to streamed tracks that are all brought to one level, and the difference
/// was plain to hear.
pub fn loudness(path: &Path) -> Option<NormalisationData> {
    if let Some(known) = measured(path) {
        return Some(known);
    }
    let mut hint = Hint::new();
    if let Some(extension) = path.extension().and_then(|e| e.to_str()) {
        hint.with_extension(extension);
    }
    let mut decoder = SymphoniaDecoder::new(File::open(path).ok()?, hint).ok()?;
    // Everything the decoder hands out is 44.1 kHz stereo; see resample.rs.
    let mut meter = ebur128::EbuR128::new(
        NUM_CHANNELS as u32,
        SAMPLE_RATE,
        ebur128::Mode::I | ebur128::Mode::SAMPLE_PEAK,
    )
    .ok()?;
    while let Ok(Some((_, packet))) = decoder.next_packet() {
        if let AudioPacket::Samples(samples) = packet {
            meter.add_frames_f64(&samples).ok()?;
        }
    }
    let lufs = meter.loudness_global().ok()?;
    if !lufs.is_finite() {
        return None;
    }
    let peak = (0..NUM_CHANNELS as u32)
        .filter_map(|channel| meter.sample_peak(channel).ok())
        .fold(0.0, f64::max)
        .max(f64::MIN_POSITIVE);
    let data = NormalisationData {
        track_gain_db: TARGET_LUFS - lufs,
        track_peak: peak,
        album_gain_db: TARGET_LUFS - lufs,
        album_peak: peak,
    };
    info!(
        "local file {} measured at {lufs:.1} LUFS, gain {:.1} dB",
        path.display(),
        data.track_gain_db
    );
    if let Ok(mut known) = LOUDNESS.write() {
        known.get_or_insert_with(HashMap::new).insert(path.to_path_buf(), data);
    }
    Some(data)
}

pub fn create_local_file_lookup(directories: &[PathBuf]) -> LocalFileLookup {
    let mut lookup = LocalFileLookup(HashMap::new());

    for path in directories {
        if !path.is_dir() {
            warn!(
                "Ignoring local file source {}: not a directory",
                path.display()
            );
            continue;
        }

        if let Err(e) = visit_dir(path, &mut lookup) {
            warn!(
                "Failed to load entries from local file source {}: {}",
                path.display(),
                e
            );
        }
    }

    lookup
}

fn visit_dir(dir: &Path, accumulator: &mut LocalFileLookup) -> io::Result<()> {
    for entry in fs::read_dir(dir)? {
        let path = entry?.path();
        if path.is_dir() {
            visit_dir(&path, accumulator)?;
        } else {
            let Some(file_extension) = path.extension().and_then(|e| e.to_str()) else {
                continue;
            };

            let lowercase_extension = file_extension.to_lowercase();

            if SUPPORTED_FILE_EXTENSIONS.contains(&lowercase_extension.as_str()) {
                let uri = match get_uri_from_file(path.as_path(), file_extension) {
                    Ok(uri) => uri,
                    Err(e) => {
                        warn!(
                            "Failed to determine URI of local file {}: {}",
                            path.display(),
                            e
                        );
                        continue;
                    }
                };

                accumulator.0.insert(uri, path);
            }
        }
    }

    Ok(())
}

fn get_uri_from_file(audio_path: &Path, file_extension: &str) -> Result<SpotifyUri, Error> {
    let src = File::open(audio_path)?;
    let mss = MediaSourceStream::new(Box::new(src), Default::default());

    let mut hint = Hint::new();
    hint.with_extension(file_extension);

    let meta_opts: MetadataOptions = Default::default();
    let fmt_opts: FormatOptions = Default::default();

    let mut probed = symphonia::default::get_probe()
        .format(&hint, mss, &fmt_opts, &meta_opts)
        .map_err(|_| Error::internal("Failed to probe file"))?;

    let mut artist: Option<String> = None;
    let mut album_title: Option<String> = None;
    let mut track_title: Option<String> = None;

    fn get_tags(probed: &mut ProbeResult) -> Option<Vec<Tag>> {
        let metadata = symphonia_util::get_latest_metadata(probed)?;
        let metadata_rev = metadata.current()?;
        Some(metadata_rev.tags().to_vec())
    }

    for tag in get_tags(&mut probed).ok_or(Error::internal("Failed to probe audio tags"))? {
        if let Some(std_key) = tag.std_key {
            match std_key {
                StandardTagKey::Album => {
                    album_title.replace(tag.value.to_string());
                }
                StandardTagKey::Artist => {
                    artist.replace(tag.value.to_string());
                }
                StandardTagKey::TrackTitle => {
                    track_title.replace(tag.value.to_string());
                }
                _ => {
                    continue;
                }
            }
        }
    }

    let first_track = probed
        .format
        .default_track()
        .ok_or(Error::internal("Failed to find an audio track"))?;

    let time_base = first_track
        .codec_params
        .time_base
        .ok_or(Error::internal("Failed to calculate track duration"))?;

    let num_frames = first_track
        .codec_params
        .n_frames
        .ok_or(Error::internal("Failed to calculate track duration"))?;

    let time = time_base.calc_time(num_frames);

    fn format_uri_part(input: Option<String>) -> String {
        input
            .map(|s| {
                let bytes = s.into_bytes();
                let encoded = form_urlencoded::byte_serialize(bytes.as_slice());
                encoded.collect::<String>()
            })
            .unwrap_or("".to_owned())
    }

    Ok(SpotifyUri::Local {
        artist: format_uri_part(artist),
        album_title: format_uri_part(album_title),
        track_title: format_uri_part(track_title),
        duration: Duration::from_secs(time.seconds),
    })
}
