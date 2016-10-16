package in.omerjerk.boomerrang;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Environment;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Created by umair on 15/10/16.
 */

public class ReverseCodec {

    private static final String TAG = ReverseCodec.class.getSimpleName();

    private static final String MIME_TYPE = "video/avc";    // H.264 Advanced Video Coding

    private int height;
    private int width;

    private ArrayList<ByteBuffer> decodedFrames = new ArrayList<>();
    private ArrayList<Integer> infoList = new ArrayList<>();
    final long kTimeOutUs = 5000;

    int frameRate;
    int bitRate = 1000000;

    public void decodeReverseEncode(String inputfile, boolean reconfigure) throws IOException {
        MediaExtractor extractor;
        MediaCodec codec;
        ByteBuffer[] codecInputBuffers;
        ByteBuffer[] codecOutputBuffers;
        extractor = new MediaExtractor();
        extractor.setDataSource(inputfile);
        MediaFormat format = extractor.getTrackFormat(0);
        int frameCount;

        Log.d(TAG, "Input FILE = " + inputfile);

        this.frameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE);
        this.width = format.getInteger(MediaFormat.KEY_WIDTH);
        this.height = format.getInteger(MediaFormat.KEY_HEIGHT);

        String mime = format.getString(MediaFormat.KEY_MIME);
        codec = MediaCodec.createDecoderByType(mime);
        codec.configure(format, null /* surface */, null /* crypto */, 0 /* flags */);
        codec.start();
        codecInputBuffers = codec.getInputBuffers();
        codecOutputBuffers = codec.getOutputBuffers();
        if (reconfigure) {
            codec.stop();
            codec.configure(format, null /* surface */, null /* crypto */, 0 /* flags */);
            codec.start();
            codecInputBuffers = codec.getInputBuffers();
            codecOutputBuffers = codec.getOutputBuffers();
        }
        extractor.selectTrack(0);
        // start decoding

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean sawInputEOS = false;
        boolean sawOutputEOS = false;
        int noOutputCounter = 0;
        while (!sawOutputEOS && noOutputCounter < 50) {
            noOutputCounter++;
            if (!sawInputEOS) {
                int inputBufIndex = codec.dequeueInputBuffer(kTimeOutUs);
                if (inputBufIndex >= 0) {
                    ByteBuffer dstBuf = codecInputBuffers[inputBufIndex];
                    int sampleSize =
                            extractor.readSampleData(dstBuf, 0 /* offset */);
                    long presentationTimeUs = 0;
                    if (sampleSize < 0) {
                        Log.d(TAG, "saw input EOS.");
                        sawInputEOS = true;
                        sampleSize = 0;
                    } else {
                        presentationTimeUs = extractor.getSampleTime();
                    }
                    codec.queueInputBuffer(
                            inputBufIndex,
                            0 /* offset */,
                            sampleSize,
                            presentationTimeUs,
                            sawInputEOS ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                    if (!sawInputEOS) {
                        extractor.advance();
                    }
                }
            }
            int res = codec.dequeueOutputBuffer(info, kTimeOutUs);
            if (res >= 0) {
                //Log.d(TAG, "got frame, size " + info.size + "/" + info.presentationTimeUs);
                if (info.size > 0) {
                    noOutputCounter = 0;
                }
                if (info.size > 0 && reconfigure) {
                    // once we've gotten some data out of the decoder, reconfigure it again
                    reconfigure = false;
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_NEXT_SYNC);
                    sawInputEOS = false;
                    codec.stop();
                    codec.configure(format, null /* surface */, null /* crypto */, 0 /* flags */);
                    codec.start();
                    codecInputBuffers = codec.getInputBuffers();
                    codecOutputBuffers = codec.getOutputBuffers();
                    continue;
                }
                int outputBufIndex = res;
                ByteBuffer buf = codecOutputBuffers[outputBufIndex];
                buf.rewind();

                ByteBuffer copy = ByteBuffer.allocate(buf.capacity());
                copy.clear();
                copy.put(buf);
                copy.rewind();
/*
                byte[] derp = new byte[info.size];
                copy.get(derp);
                copy.rewind();
                Log.e(TAG, "derp = " + Arrays.toString(derp));
*/
//                Log.e(TAG, "Decoded info size = " + info.size);
                decodedFrames.add(copy);
                infoList.add(info.size);

                codec.releaseOutputBuffer(outputBufIndex, false /* render */);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Log.d(TAG, "saw output EOS.");
                    sawOutputEOS = true;
                    Log.e(TAG, "decoding done. Frame count = " + decodedFrames.size());
                }
            } else if (res == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                codecOutputBuffers = codec.getOutputBuffers();
                Log.d(TAG, "output buffers have changed.");
            } else if (res == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat oformat = codec.getOutputFormat();
                Log.d(TAG, "output format has changed to " + oformat);
            } else {
                Log.d(TAG, "dequeueOutputBuffer returned " + res);
            }
        }
        codec.stop();
        codec.release();

        frameCount = decodedFrames.size();
        for (int i = 0; i < frameCount; ++i) {
            ByteBuffer orig = decodedFrames.get(frameCount - i - 1);
            orig.rewind();
            ByteBuffer copy = ByteBuffer.allocate(orig.capacity());
            copy.clear();
            copy.put(orig);
            copy.rewind();
            decodedFrames.add(copy);
            infoList.add(infoList.get(frameCount - i - 1));
        }
        reverseEncode();
    }

    private void reverseEncode() throws IOException {
        MediaCodec encoder;
        File outputFile = new File(Environment.getExternalStorageDirectory(), "boomerrag-reverse.mp4");
        boolean outputDone = false;
        boolean inputDone = false;
        boolean encoderDone = false;

        MediaCodecInfo codecInfo = selectCodec(MIME_TYPE);
        int colorFormat = selectColorFormat(codecInfo, MIME_TYPE);

        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, this.width, this.height);
        // Set some properties.  Failing to specify some of these can cause the MediaCodec
        // configure() call to throw an unhelpful exception.
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
        format.setInteger(MediaFormat.KEY_BIT_RATE, this.bitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, this.frameRate);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 30);

        encoder = MediaCodec.createEncoderByType(MIME_TYPE);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();

        ByteBuffer[] encoderInputBuffers = encoder.getInputBuffers();
        ByteBuffer[] encoderOutputBuffers = encoder.getOutputBuffers();

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        MediaMuxer muxer = new MediaMuxer(outputFile.toString(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int mTrackIndex = -1;
        boolean mMuxerStarted = false;

        int generateIndex = 0;

        while (!outputDone) {
            if (!inputDone) {
                int inputBufIndex = encoder.dequeueInputBuffer(-1);
                Log.d(TAG, "inputBufIndex=" + inputBufIndex);
                if (inputBufIndex >= 0) {
                    long ptsUsec = computePresentationTime(generateIndex);
                    Log.d(TAG, "presentation time = " + ptsUsec);
                    if (decodedFrames.size() == 0) {
                        encoder.queueInputBuffer(inputBufIndex, 0, 0, ptsUsec,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
//                        encoder.signalEndOfInputStream();
                        inputDone = true;
                    } else {
                        ByteBuffer src = decodedFrames.get(decodedFrames.size() - 1);
                        Integer size = infoList.get(infoList.size() - 1);
                        decodedFrames.remove(decodedFrames.size() - 1);
                        infoList.remove(infoList.size() - 1);

                        src.rewind();
                        ByteBuffer inputBuf = encoderInputBuffers[inputBufIndex];
                        inputBuf.clear();
                        inputBuf.put(src);

                        encoder.queueInputBuffer(inputBufIndex, 0, size, ptsUsec, 0);
                        Log.d(TAG, "submitted frame " + generateIndex + " to enc with size = " + size);
                    }

                    generateIndex++;
                } else {
                    // either all in use, or we timed out during initial setup
                    Log.d(TAG, "input buffer not available");
                }
            }

            if (!encoderDone) {
                int encoderStatus = encoder.dequeueOutputBuffer(info, kTimeOutUs);
                if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // no output available yet
                    Log.d(TAG, "no output from encoder available");
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    // not expected for an encoder
                    Log.d(TAG, "encoder output buffers changed");
                    encoderOutputBuffers = encoder.getOutputBuffers();
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = encoder.getOutputFormat();
                    Log.d(TAG, "encoder output format changed: " + newFormat);

                    // now that we have the Magic Goodies, start the muxer
                    mTrackIndex = muxer.addTrack(newFormat);
                    muxer.start();
                    mMuxerStarted = true;
                } else if (encoderStatus < 0) {
                    Log.e(TAG, "unexpected result from encoder.dequeueOutputBuffer: " + encoderStatus);
                } else { // encoderStatus >= 0
                    ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                    if (encodedData == null) {
                        Log.e(TAG, "Null encoded data received");
                    }

                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        // The codec config data was pulled out and fed to the muxer when we got
                        // the INFO_OUTPUT_FORMAT_CHANGED status.  Ignore it.
                        Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                        info.size = 0;
                    }

                    if (info.size != 0) {

                        if (!mMuxerStarted) {
                            throw new RuntimeException("muxer hasn't started");
                        }

                        encodedData.position(info.offset);
                        encodedData.limit(info.offset + info.size);

                        muxer.writeSampleData(mTrackIndex, encodedData, info);

                        Log.d(TAG, "sent " + info.size + " bytes to muxer, ts=" +
                                info.presentationTimeUs);
                    }
                    encoderDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    Log.e(TAG, "Releasing output buffers size = " + info.size);
                    encoder.releaseOutputBuffer(encoderStatus, false);
                }
            } else {
                Log.d(TAG, "=======ENCODER DONE==================");
                muxer.stop();
                muxer.release();
                muxer = null;

                outputDone = true;

                encoder.stop();
                encoder.release();
                encoder = null;
            }
        }
    }

    private long computePresentationTime(int frameIndex) {
        return 132 + frameIndex * 1000000 / this.frameRate;
    }

    private int selectColorFormat(MediaCodecInfo codecInfo, String mimeType) {
        MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(mimeType);
        for (int i = 0; i < capabilities.colorFormats.length; i++) {
            int colorFormat = capabilities.colorFormats[i];
            if (isRecognizedFormat(colorFormat)) {
                return colorFormat;
            }
        }
        Log.e(TAG, "couldn't find a good color format for " + codecInfo.getName() + " / " + mimeType);
        return 0;   // not reached
    }
    /**
     * Returns true if this is a color format that this test code understands (i.e. we know how
     * to read and generate frames in this format).
     */
    private boolean isRecognizedFormat(int colorFormat) {
        switch (colorFormat) {
            // these are the formats we know how to handle for this test
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar:
                return true;
            default:
                return false;
        }
    }

    /**
     * Returns the first codec capable of encoding the specified MIME type, or null if no
     * match was found.
     */
    private static MediaCodecInfo selectCodec(String mimeType) {
        int numCodecs = MediaCodecList.getCodecCount();
        for (int i = 0; i < numCodecs; i++) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
            if (!codecInfo.isEncoder()) {
                continue;
            }
            String[] types = codecInfo.getSupportedTypes();
            for (int j = 0; j < types.length; j++) {
                if (types[j].equalsIgnoreCase(mimeType)) {
                    return codecInfo;
                }
            }
        }
        return null;
    }

}
