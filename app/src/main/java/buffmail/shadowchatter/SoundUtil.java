package buffmail.shadowchatter;

import java.util.ArrayList;

public class SoundUtil {
    static class PlayChunk {
        double startSec;
        double endSec;
        PlayChunk(double startSec, double endSec) {
            this.startSec = startSec;
            this.endSec = endSec;
        }
    };

    private static double[] GetNormalizedHeights(final int[] frameGains)
    {
        int numFrames = frameGains.length;
        double[] smoothedGains = new double[numFrames];
        if (numFrames == 1) {
            smoothedGains[0] = frameGains[0];
        } else if (numFrames == 2) {
            smoothedGains[0] = frameGains[0];
            smoothedGains[1] = frameGains[1];
        } else if (numFrames > 2) {
            smoothedGains[0] = (double)( (frameGains[0] / 2.0) + (frameGains[1] / 2.0));
            for (int i = 1; i < numFrames - 1; i++) {
                smoothedGains[i] = (double)(
                        (frameGains[i - 1] / 3.0) +
                                (frameGains[i    ] / 3.0) +
                                (frameGains[i + 1] / 3.0));
            }
            smoothedGains[numFrames - 1] = (double)(
                    (frameGains[numFrames - 2] / 2.0) + (frameGains[numFrames - 1] / 2.0));
        }

        // Make sure the range is no more than 0 - 255
        double maxGain = 1.0;
        for (int i = 0; i < numFrames; ++i)
            maxGain = Math.max(maxGain, smoothedGains[i]);
        double scaleFactor = (maxGain > 255.0) ? 255 / maxGain : 1;

        // Build histogram of 256 bins and figure out the new scaled max
        maxGain = 0;
        int gainHist[] = new int[256];
        for (int i = 0; i < numFrames; i++) {
            int smoothedGain = (int)(smoothedGains[i] * scaleFactor);
            if (smoothedGain < 0)
                smoothedGain = 0;
            if (smoothedGain > 255)
                smoothedGain = 255;

            if (smoothedGain > maxGain)
                maxGain = smoothedGain;

            gainHist[smoothedGain]++;
        }

        // Re-calibrate the min to be 5%
        double minGain = 0;
        int sum = 0;
        while (minGain < 255 && sum < numFrames / 20) {
            sum += gainHist[(int)minGain];
            minGain++;
        }

        // Re-calibrate the max to be 99%
        sum = 0;
        while (maxGain > 2 && sum < numFrames / 100) {
            sum += gainHist[(int)maxGain];
            maxGain--;
        }

        // Compute the heights
        double[] heights = new double[numFrames];
        double range = maxGain - minGain;
        for (int i = 0; i < numFrames; i++) {
            double value = (smoothedGains[i] * scaleFactor - minGain) / range;
            if (value < 0.0)
                value = 0.0;
            if (value > 1.0)
                value = 1.0;
            heights[i] = value * value;
        }

        return heights;
    }

    private static double framesToSeconds(int frames, int sampleRate, int samplesPerFrame) {
        final double samples = 1.f * samplesPerFrame * frames;
        final double sec = samples / sampleRate;
        return sec;
    }

    private static int secondsToFrames(double seconds, int sampleRate, int samplesPerFrame) {
        return (int)(1.0 * seconds * sampleRate / samplesPerFrame + 0.5);
    }

    public static PlayChunk[] GetPlayChunks(final int[] frameGains, int sampleRate, int samplesPerFrame) {
        final double[] heights = GetNormalizedHeights(frameGains);

        final double silenceSpanSec = 0.2f;
        final double silenceValue = 0.01f;
        final int silenceFrames = secondsToFrames(silenceSpanSec, sampleRate, samplesPerFrame);

        ArrayList<Double> silenceSecs = new ArrayList<>();

        int silenceStartFrame = -1;
        for (int i = 0; i < heights.length; ++i) {
            double value = heights[i];
            if (value < silenceValue) {
                if (silenceStartFrame != -1)
                    continue;
                silenceStartFrame = i;
                continue;
            } else {
                if (silenceStartFrame != -1){
                    final int currFrame = i;
                    if (currFrame - silenceStartFrame >= silenceFrames) {
                        final int middleFrame = (currFrame + silenceStartFrame) / 2;
                        silenceSecs.add(framesToSeconds(middleFrame, sampleRate, samplesPerFrame));
                    }
                    silenceStartFrame = -1;
                }
            }
        }

        if (heights.length == 0)
            return null;

        if (silenceSecs.isEmpty()){
            return null;
        }

        final double totalSec = framesToSeconds(heights.length - 1, sampleRate, samplesPerFrame);

        final int chunkSize = silenceSecs.size() + 1;
        PlayChunk[] playChunks = new PlayChunk[chunkSize];
        for (int i = 0; i < playChunks.length; ++i) {
            final double startSec = (i==0) ? 0 : playChunks[i-1].endSec;
            final double endSec = (i < silenceSecs.size()) ? silenceSecs.get(i) : totalSec;
            playChunks[i] = new PlayChunk(startSec, endSec);
        }
        return playChunks;
    }

    public static PlayChunk[] MergePlayChunks(PlayChunk[] prevChunks, int idx) {
        if (idx == 0)
            return prevChunks;

        final int prevLength = prevChunks.length;
        PlayChunk[] newPlayChunks = new PlayChunk[prevLength - 1];
        for (int i = 0; i < idx - 1; ++i)
            newPlayChunks[i] = prevChunks[i];

        newPlayChunks[idx - 1] = new PlayChunk(
                prevChunks[idx - 1].startSec,
                prevChunks[idx].endSec);

        for (int i = idx+1; i < prevLength; ++i)
            newPlayChunks[i-1] = prevChunks[i];

        return newPlayChunks;
    }
}
