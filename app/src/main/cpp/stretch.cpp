// JNI wrapper around Bungee's streaming time stretcher.
//
// Replaces AudioTrack.setPlaybackParams, which uses the platform's Sonic
// implementation: Sonic is cheap and fine for speech, but on music at anything
// beyond a few percent it smears transients and adds a metallic edge. Bungee is
// a phase-vocoder and holds up far better at the ratios this app offers.
//
// Audio arrives here as interleaved 16-bit PCM because that is what the Rust
// sink produces and what AudioTrack consumes; Bungee works in planar float, so
// this file is mostly the conversion either side of one process() call.

#include <jni.h>

#include <bungee/Bungee.h>
#include <bungee/Stream.h>

#include <algorithm>
#include <cmath>
#include <vector>

namespace {

using Edition = Bungee::Basic;

/// One stretcher plus the scratch buffers its planar float API needs.
struct Context
{
	Bungee::Stretcher<Edition> stretcher;
	Bungee::Stream<Edition> stream;
	int channels;
	int maxInputFrames;
	int maxOutputFrames;
	std::vector<float> input;
	std::vector<float> output;
	std::vector<const float *> inputPointers;
	std::vector<float *> outputPointers;

	Context(int sampleRate, int channelCount, int maxInput, int maxOutput) :
		stretcher({sampleRate, sampleRate}, channelCount),
		stream(stretcher, maxInput, channelCount),
		channels(channelCount),
		maxInputFrames(maxInput),
		maxOutputFrames(maxOutput),
		input(static_cast<size_t>(maxInput) * channelCount),
		output(static_cast<size_t>(maxOutput) * channelCount),
		inputPointers(channelCount),
		outputPointers(channelCount)
	{
		for (int c = 0; c < channelCount; ++c)
		{
			inputPointers[c] = input.data() + static_cast<size_t>(c) * maxInput;
			outputPointers[c] = output.data() + static_cast<size_t>(c) * maxOutput;
		}
	}
};

constexpr float kInt16Scale = 32768.f;

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_emanuele_spot_playback_Stretcher_nativeCreate(
	JNIEnv *, jclass, jint sampleRate, jint channels, jint maxInputFrames, jint maxOutputFrames)
{
	if (sampleRate <= 0 || channels <= 0 || maxInputFrames <= 0 || maxOutputFrames <= 0)
		return 0;

	auto *context = new (std::nothrow) Context(sampleRate, channels, maxInputFrames, maxOutputFrames);
	return reinterpret_cast<jlong>(context);
}

JNIEXPORT void JNICALL
Java_dev_emanuele_spot_playback_Stretcher_nativeDestroy(JNIEnv *, jclass, jlong handle)
{
	delete reinterpret_cast<Context *>(handle);
}

/**
 * Stretches one packet.
 *
 * @return frames written to `outputBuffer`, or -1 if the call was rejected.
 */
JNIEXPORT jint JNICALL
Java_dev_emanuele_spot_playback_Stretcher_nativeProcess(
	JNIEnv *env,
	jclass,
	jlong handle,
	jobject inputBuffer,
	jint inputFrames,
	jfloat speed,
	jfloat pitch,
	jobject outputBuffer)
{
	auto *context = reinterpret_cast<Context *>(handle);
	if (!context || inputFrames <= 0 || inputFrames > context->maxInputFrames)
		return -1;

	auto *in = static_cast<const int16_t *>(env->GetDirectBufferAddress(inputBuffer));
	auto *out = static_cast<int16_t *>(env->GetDirectBufferAddress(outputBuffer));
	if (!in || !out)
		return -1;

	const int channels = context->channels;

	// Interleaved 16-bit to planar float.
	for (int frame = 0; frame < inputFrames; ++frame)
		for (int c = 0; c < channels; ++c)
			context->input[static_cast<size_t>(c) * context->maxInputFrames + frame] =
				in[frame * channels + c] / kInt16Scale;

	// Bungee derives speed from the ratio of input to output frames, so this is
	// where tempo is actually set: fewer output frames than input means faster.
	double outputFrameCount = inputFrames / static_cast<double>(speed);
	if (outputFrameCount > context->maxOutputFrames)
		outputFrameCount = context->maxOutputFrames;

	const int produced = context->stream.process(
		context->inputPointers.data(),
		context->outputPointers.data(),
		inputFrames,
		outputFrameCount,
		pitch);

	const int frames = std::min(produced, context->maxOutputFrames);

	// Planar float back to interleaved 16-bit. Clamped rather than wrapped:
	// a phase vocoder can overshoot the input's peak, and letting that wrap
	// around turns a loud moment into a burst of noise.
	for (int frame = 0; frame < frames; ++frame)
		for (int c = 0; c < channels; ++c)
		{
			const float sample =
				context->outputPointers[c][frame] * kInt16Scale;
			out[frame * channels + c] = static_cast<int16_t>(
				std::clamp(sample, -kInt16Scale, kInt16Scale - 1.f));
		}

	return frames;
}

} // extern "C"
