package buffmail.shadowchatter;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ImageButton;
import android.widget.TextView;

import java.io.File;

import buffmail.shadowchatter.soundfile.SoundFile;
import buffmail.shadowchatter.SoundUtil.PlayChunk;

public class MainActivity extends Activity
        implements WaveformView.WaveformListener {

    private final String TAG = "MainActivity";
    private final String PLAYCHUNK_IDX_KEY = "PLAYCHUNK_IDX_KEY";

    private ProgressDialog mProgressDialog;
    private long mLoadingLastUpdateTime;
    private SoundFile mSoundFile;
    private File mFile;
    private SamplePlayer mPlayer;
    private Handler mHandler;
    private boolean mIsPlaying;
    private WaveformView mWaveformView;

    private float mDensity;

    private int mOffset;
    private int mOffsetGoal;
    private int mFlingVelocity;

    private int mWidth;
    private int mMaxPos;
    private int mStartPos;
    private int mEndPos;
    private int mPlayChunkIdx;
    private PlayChunk[] mPlayChunks;

    private boolean mKeyDown;

    private ImageButton mPlayButton;
    private ImageButton mRewindButton;
    private ImageButton mFfwdButton;
    private ImageButton mMergeButton;

    private TextView mPositionText;

    private Thread mLoadingSoundFileThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mPlayer = null;
        mIsPlaying = false;
        mProgressDialog = null;
        mLoadingSoundFileThread = null;

        mSoundFile = null;
        mKeyDown = false;

        mHandler = new Handler();

        mPlayChunkIdx = 0;
        mPlayChunks = null;

        loadGui();

        FileChooser chooser = new FileChooser(this);
        chooser.setExtension(".mp3");
        chooser.setFileListener(new FileChooser.FileSelectedListener() {
            @Override
            public void fileSelected(File file) {
                loadFromFile(file.getAbsolutePath());
            }
        });
        chooser.showDialog();
    }

    @Override
    protected void onDestroy() {
        if (mProgressDialog != null) {
            mProgressDialog.dismiss();
            mProgressDialog = null;
        }

        if (mPlayer != null) {
            if (mPlayer.isPlaying() || mPlayer.isPaused()) {
                mPlayer.stop();
            }
            mPlayer.release();
            mPlayer = null;
        }

        closeThread(mLoadingSoundFileThread);
        super.onDestroy();
    }
    private void closeThread(Thread thread) {
        if (thread != null && thread.isAlive()) {
            try {
                thread.join();
            } catch (InterruptedException e) {
            }
        }
    }

    private void loadFromFile(String filename) {
        mFile = new File(filename);
        mLoadingLastUpdateTime = getCurrentTime();

        mProgressDialog = new ProgressDialog(MainActivity.this);
        mProgressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        mProgressDialog.setTitle("Loading...");
        mProgressDialog.show();

        final SoundFile.ProgressListener listener =
                new SoundFile.ProgressListener(){
                    public boolean reportProgress(double fractionComplete) {
                        long now = getCurrentTime();
                        if (now - mLoadingLastUpdateTime > 100){
                            mProgressDialog.setProgress(
                                    (int)(mProgressDialog.getMax() * fractionComplete));
                            mLoadingLastUpdateTime = now;
                        }
                        return true;
                    }
                };

        mLoadingSoundFileThread = new Thread() {
            public void run() {
                try {
                    mSoundFile = SoundFile.create(mFile.getAbsolutePath(), listener);

                    if (mSoundFile == null) {
                        return;
                    }
                    mPlayer =  new SamplePlayer(mSoundFile);
                } catch (final Exception e) {
                    mProgressDialog.dismiss();
                    e.printStackTrace();
                }

                mProgressDialog.dismiss();
                mHandler.post(new Runnable() {
                    public void run() {
                        finishOpeningSoundFile();
                    }
                });
            }
        };
        mLoadingSoundFileThread.start();
    }

    private void finishOpeningSoundFile() {
        mPlayChunks = SoundUtil.GetPlayChunks(
                mSoundFile.getFrameGains(), mSoundFile.getSampleRate(), mSoundFile.getSamplesPerFrame());
        mWaveformView.setSoundFile(mSoundFile);
        mWaveformView.recomputeHeights(mDensity);
        mWaveformView.updatePlayChunks(mPlayChunks);

        mMaxPos = mWaveformView.maxPos();

        mOffset = 0;
        mOffsetGoal = 0;
        mFlingVelocity = 0;
        mPlayChunkIdx = getPreferences(Context.MODE_PRIVATE).getInt(PLAYCHUNK_IDX_KEY, 0);
        resetPositions();
        if (mEndPos > mMaxPos)
            mEndPos = mMaxPos;
    }

    public void waveformFling(float vx) {
        mOffsetGoal = mOffset;
        mFlingVelocity = (int)(-vx);
        updateDisplay();
    }

    public void waveformDraw() {
        mWidth = mWaveformView.getMeasuredWidth();
        if (mOffsetGoal != mOffset && !mKeyDown)
            updateDisplay();
        else if (mIsPlaying) {
            updateDisplay();
        } else if (mFlingVelocity != 0) {
            updateDisplay();
        }
    }

    public void waveformZoomIn() {
        mWaveformView.zoomIn();
        mStartPos = mWaveformView.getStart();
        mEndPos = mWaveformView.getEnd();
        mMaxPos = mWaveformView.maxPos();
        mOffset = mWaveformView.getOffset();
        mOffsetGoal = mOffset;
        updateDisplay();
    }

    public void waveformZoomOut() {
        mWaveformView.zoomOut();
        mStartPos = mWaveformView.getStart();
        mEndPos = mWaveformView.getEnd();
        mMaxPos = mWaveformView.maxPos();
        mOffset = mWaveformView.getOffset();
        mOffsetGoal = mOffset;
        updateDisplay();
    }

    private void loadGui() {
        setContentView(R.layout.activity_main);

        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);
        mDensity = metrics.density;

        mPlayButton = (ImageButton)findViewById(R.id.play);
        mPlayButton.setOnClickListener(mPlayListener);
        mRewindButton = (ImageButton)findViewById(R.id.rew);
        mRewindButton.setOnClickListener(mRewindListener);
        mFfwdButton = (ImageButton)findViewById(R.id.ffwd);
        mFfwdButton.setOnClickListener(mFfwdListener);
        mMergeButton = (ImageButton)findViewById(R.id.merge);
        mMergeButton.setOnClickListener(mMergeListener);
        mPositionText = (TextView)findViewById(R.id.position_text);
        mWaveformView = (WaveformView)findViewById(R.id.waveform);
        mWaveformView.setListener(this);

        enableDisableButtons();

        mMaxPos = 0;
        updateDisplay();
    }

    private synchronized void updateDisplay() {
        if (mIsPlaying) {
            int now = mPlayer.getCurrentPosition();
            int frames = mWaveformView.millisecsToPixels(now);
            mWaveformView.setPlayback(frames);
            setOffsetGoalNoUpdate(frames - mWidth / 2);
            int endMsec = mWaveformView.pixelsToMillisecs(mWaveformView.getEnd());
            if (now >= endMsec) {
                handlePause();
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        PlayChunk chunk = mPlayChunks[mPlayChunkIdx];
                        onPlay(chunk.startSec);
                    }
                });
            }
        }

        int offsetDelta;

        if (mFlingVelocity != 0) {
            offsetDelta = mFlingVelocity / 30;
            if (mFlingVelocity > 80) {
                mFlingVelocity -= 80;
            } else if (mFlingVelocity < -80) {
                mFlingVelocity += 80;
            } else {
                mFlingVelocity = 0;
            }

            mOffset += offsetDelta;

            if (mOffset + mWidth / 2 > mMaxPos) {
                mOffset = mMaxPos - mWidth / 2;
                mFlingVelocity = 0;
            }
            if (mOffset < 0) {
                mOffset = 0;
                mFlingVelocity = 0;
            }
            mOffsetGoal = mOffset;
        } else {
            offsetDelta = mOffsetGoal - mOffset;

            if (offsetDelta > 10)
                offsetDelta = offsetDelta / 10;
            else if (offsetDelta > 0)
                offsetDelta = 1;
            else if (offsetDelta < -10)
                offsetDelta = offsetDelta / 10;
            else if (offsetDelta < 0)
                offsetDelta = -1;
            else
                offsetDelta = 0;

            mOffset += offsetDelta;
        }

        mWaveformView.setParameters(mStartPos, mEndPos, mOffset);
        mWaveformView.invalidate();
    }

    private synchronized void handlePause() {
        if (mPlayer != null && mPlayer.isPlaying()) {
            mPlayer.pause();
        }
        mWaveformView.setPlayback(-1);
        mIsPlaying = false;
        enableDisableButtons();
    }

    private synchronized void onPlay(double startSec) {
        if (mIsPlaying) {
            handlePause();
            return;
        }

        if (mPlayer == null) {
            // Not initialized yet
            return;
        }

        try {
            final double start = startSec;
            mPlayer.setOnCompletionListener(new SamplePlayer.OnCompletionListener() {
                @Override
                public void onCompletion() {
                    handlePause();
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            onPlay(start);
                        }
                    });
                }
            });
            mIsPlaying = true;

            final int startMsec = (int)(startSec * 1000);
            mPlayer.seekTo(startMsec);
            mPlayer.start();
            updateDisplay();
            enableDisableButtons();
        } catch (Exception e) {
            return;
        }
    }
    private void resetPositions() {
        assert mPlayChunks != null;

        PlayChunk chunk = mPlayChunks[mPlayChunkIdx];
        if (chunk == null){
            mStartPos = mWaveformView.secondsToPixels(0.0);
            mEndPos = mWaveformView.secondsToPixels(15.0);
            mPlayChunkIdx = 0;
            SharedPreferences.Editor edit = getPreferences(Context.MODE_PRIVATE).edit();
            edit.putInt(PLAYCHUNK_IDX_KEY, mPlayChunkIdx);
            edit.commit();
            return;
        }

        mStartPos = mWaveformView.secondsToPixels(chunk.startSec);
        mEndPos = mWaveformView.secondsToPixels(chunk.endSec);

        String position = String.format("%d / %d", mPlayChunkIdx + 1, mPlayChunks.length);
        mPositionText.setText(position);
    }

    private long getCurrentTime() {
        return System.nanoTime() / 1000000;
    }

    private void setOffsetGoalNoUpdate(int offset) {
        mOffsetGoal = offset;
        if (mOffsetGoal + mWidth / 2 > mMaxPos)
            mOffsetGoal = mMaxPos - mWidth / 2;
        if (mOffsetGoal < 0)
            mOffsetGoal = 0;
    }

    private OnClickListener mPlayListener = new OnClickListener() {
        public void onClick(View sender) {
            if (mPlayChunks == null)
                return;
            assert mPlayChunkIdx >= mPlayChunks.length;

            PlayChunk chunk = mPlayChunks[mPlayChunkIdx];
            onPlay(chunk.startSec);
        }
    };

    private OnClickListener mFfwdListener = new OnClickListener() {
        public void onClick(View sender) {
            if (mIsPlaying) {
                final int chunkCount = mPlayChunks.length;
                if (mPlayChunkIdx + 1 >= chunkCount)
                    return;

                handlePause();
                ++mPlayChunkIdx;
                SharedPreferences.Editor edit = getPreferences(Context.MODE_PRIVATE).edit();
                edit.putInt(PLAYCHUNK_IDX_KEY, mPlayChunkIdx);
                edit.commit();
                resetPositions();
                PlayChunk playChunk = mPlayChunks[mPlayChunkIdx];
                onPlay(playChunk.startSec);
            }
        }
    };

    private OnClickListener mMergeListener = new OnClickListener() {
        @Override
        public void onClick(View v) {
            if (mPlayChunks == null)
                return;
            if (mPlayChunkIdx <= 0 || mPlayChunks.length == 1)
                return;
            handlePause();

            mPlayChunks = SoundUtil.MergePlayChunks(mPlayChunks, mPlayChunkIdx);
            mWaveformView.updatePlayChunks(mPlayChunks);
            --mPlayChunkIdx;
            SharedPreferences.Editor edit = getPreferences(Context.MODE_PRIVATE).edit();
            edit.putInt(PLAYCHUNK_IDX_KEY, mPlayChunkIdx);
            edit.commit();
            resetPositions();
            PlayChunk playChunk = mPlayChunks[mPlayChunkIdx];
            onPlay(playChunk.startSec);
        }
    };

    private OnClickListener mRewindListener = new OnClickListener() {
        public void onClick(View sender) {
            if (mIsPlaying) {
                if (mPlayChunkIdx == 0)
                    return;

                handlePause();
                --mPlayChunkIdx;
                SharedPreferences.Editor edit = getPreferences(Context.MODE_PRIVATE).edit();
                edit.putInt(PLAYCHUNK_IDX_KEY, mPlayChunkIdx);
                edit.commit();
                resetPositions();
                resetPositions();
                PlayChunk playChunk = mPlayChunks[mPlayChunkIdx];
                onPlay(playChunk.startSec);
            }
        }
    };

    private void enableDisableButtons() {
        final int resId = mIsPlaying ?
                android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;
        final String desc = mIsPlaying ? "Stop" : "Play";
        mPlayButton.setImageResource(resId);
        mPlayButton.setContentDescription(desc);
    }
}
