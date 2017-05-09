package buffmail.shadowchatter;

import android.app.Activity;
import android.app.ProgressDialog;
import android.os.Handler;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ImageButton;
import android.widget.RelativeLayout;
import android.widget.TextView;

import java.io.File;

import buffmail.shadowchatter.soundfile.SoundFile;

public class MainActivity extends Activity
        implements MarkerView.MarkerListener,
        WaveformView.WaveformListener {

    private final String TAG = "MainActivity";

    private String mFilename = "/storage/sdcard/media/audio/music/aaa.m4a";
    private ProgressDialog mProgressDialog;
    private long mLoadingLastUpdateTime;
    private Boolean mLoadingKeepGoing;
    private SoundFile mSoundFile;
    private File mFile;
    private SamplePlayer mPlayer;
    private boolean mTouchDragging;
    private float mTouchStart;
    private int mTouchInitialOffset;
    private long mWaveformTouchStartMsec;
    private int mPlayStartMsec;
    private int mPlayEndMsec;
    private Handler mHandler;
    private boolean mIsPlaying;
    private WaveformView mWaveformView;
    private MarkerView mStartMarker;
    private MarkerView mEndMarker;

    private float mDensity;
    private int mMarkerLeftInset;
    private int mMarkerRightInset;
    private int mMarkerTopOffset;
    private int mMarkerBottomOffset;

    private int mOffset;
    private int mOffsetGoal;
    private int mFlingVelocity;

    private int mWidth;
    private int mMaxPos;
    private int mStartPos;
    private int mEndPos;
    private int mPlayChunkIdx;
    private boolean mStartVisible;
    private boolean mEndVisible;

    private boolean mKeyDown;

    private ImageButton mPlayButton;
    private ImageButton mRewindButton;
    private ImageButton mFfwdButton;

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

        mLoadingKeepGoing = true;
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
                        return mLoadingKeepGoing;
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
                if (mLoadingKeepGoing) {
                    Runnable runnable = new Runnable() {
                        public void run() {
                            finishOpeningSoundFile();
                        }
                    };
                    mHandler.post(runnable);
                }
            }
        };
        mLoadingSoundFileThread.start();
    }

    private void finishOpeningSoundFile() {
        mWaveformView.setSoundFile(mSoundFile);
        mWaveformView.recomputeHeights(mDensity);

        mMaxPos = mWaveformView.maxPos();

        mTouchDragging = false;

        mOffset = 0;
        mOffsetGoal = 0;
        mFlingVelocity = 0;
        resetPositions();
        if (mEndPos > mMaxPos)
            mEndPos = mMaxPos;
    }

    public void markerTouchStart(MarkerView marker, float pos) {}
    public void markerTouchMove(MarkerView marker, float pos) {}
    public void markerTouchEnd(MarkerView marker) {}
    public void markerFocus(MarkerView marker) {
        mKeyDown = false;
        if (marker == mStartMarker) {
            setOffsetGoalStartNoUpdate();
        } else {
            setOffsetGoalEndNoUpdate();
        }
        mHandler.postDelayed(new Runnable() {
            public void run() {
                updateDisplay();
            }
        }, 100);
    }
    public void markerLeft(MarkerView marker, int velocity) {}
    public void markerRight(MarkerView marker, int velocity) {}
    public void markerEnter(MarkerView marker) {}
    public void markerKeyUp() {}
    public void markerDraw() {}

    public void waveformTouchStart(float x) {
        mTouchDragging = true;
        mTouchStart = x;
        mTouchInitialOffset = mOffset;
        mFlingVelocity = 0;
        mWaveformTouchStartMsec = getCurrentTime();
        Log.i(TAG, String.format("waveformTouchStart : %.1f", x));
    }

    public void waveformTouchMove(float x) {
        mOffset = trap((int)(mTouchInitialOffset * (mTouchStart - x)));
        updateDisplay();
        Log.i(TAG, String.format("waveformTouchMove : %.1f", x));
    }
    public void waveformTouchEnd() {
        mTouchDragging = false;
        mOffsetGoal = mOffset;

        long elapsedMsec = getCurrentTime() - mWaveformTouchStartMsec;
        if (elapsedMsec < 300) {
            if (mIsPlaying) {
                int seekMsec = mWaveformView.pixelsToMillisecs(
                        (int)(mTouchStart + mOffset));
                if (seekMsec >= mPlayStartMsec &&
                        seekMsec < mPlayEndMsec) {
                    mPlayer.seekTo(seekMsec);
                } else {
                    handlePause();
                }
            } else {
                onPlay((int)(mTouchStart + mOffset));
            }
        }
        Log.i(TAG, String.format("waveformTouchEnd"));
    }

    public void waveformFling(float vx) {
        mTouchDragging = false;
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

        mMarkerLeftInset = (int)(46 * mDensity);
        mMarkerRightInset = (int)(48 * mDensity);
        mMarkerTopOffset = (int)(10 * mDensity);
        mMarkerBottomOffset = (int)(10 * mDensity);

        mPlayButton = (ImageButton)findViewById(R.id.play);
        mPlayButton.setOnClickListener(mPlayListener);
        mRewindButton = (ImageButton)findViewById(R.id.rew);
        mRewindButton.setOnClickListener(mRewindListener);
        mFfwdButton = (ImageButton)findViewById(R.id.ffwd);
        mFfwdButton.setOnClickListener(mFfwdListener);
        mPositionText = (TextView)findViewById(R.id.position_text);
        mWaveformView = (WaveformView)findViewById(R.id.waveform);
        mWaveformView.setListener(this);

        enableDisableButtons();

        mMaxPos = 0;
        if (mSoundFile != null && !mWaveformView.hasSoundFile()) {
            mWaveformView.setSoundFile(mSoundFile);
            mWaveformView.recomputeHeights(mDensity);
            mMaxPos = mWaveformView.maxPos();
        }

        mStartMarker = (MarkerView)findViewById(R.id.startmarker);
        mStartMarker.setListener(this);
        mStartMarker.setAlpha(1f);
        mStartMarker.setFocusable(true);
        mStartMarker.setFocusableInTouchMode(true);
        mStartVisible = true;

        mEndMarker = (MarkerView)findViewById(R.id.endmarker);
        mEndMarker.setListener(this);
        mEndMarker.setAlpha(1f);
        mEndMarker.setFocusable(true);
        mEndMarker.setFocusableInTouchMode(true);
        mEndVisible = true;

        updateDisplay();
    }

    private synchronized void updateDisplay() {
        if (mIsPlaying) {
            int now = mPlayer.getCurrentPosition();
            int frames = mWaveformView.millisecsToPixels(now);
            mWaveformView.setPlayback(frames);
            setOffsetGoalNoUpdate(frames - mWidth / 2);
            if (now >= mPlayEndMsec) {
                handlePause();
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        onPlay(mStartPos);
                    }
                });
            }
        }

        if (!mTouchDragging)
        {
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
        }

        mWaveformView.setParameters(mStartPos, mEndPos, mOffset);
        mWaveformView.invalidate();

        mStartMarker.setContentDescription(
                getResources().getText(R.string.start_marker) + " " +
                        formatTime(mStartPos));
        mEndMarker.setContentDescription(
                getResources().getText(R.string.end_marker) + " " +
                        formatTime(mEndPos));

        int startX = mStartPos - mOffset - mMarkerLeftInset;
        if (startX + mStartMarker.getWidth() >= 0) {
            if (!mStartVisible) {
                mHandler.postDelayed(new Runnable() {
                    public void run() {
                        mStartVisible = true;
                        mStartMarker.setAlpha(1f);
                    }
                }, 0);
            }
        } else {
            if (mStartVisible) {
                mStartMarker.setAlpha(0f);
                mStartVisible = false;
            }
            startX = 0;
        }

        int endX = mEndPos - mOffset - mEndMarker.getWidth() + mMarkerRightInset;
        if (endX + mEndMarker.getWidth() >= 0) {
            if (!mEndVisible) {
                // Delay this to avoid flicker
                mHandler.postDelayed(new Runnable() {
                    public void run() {
                        mEndVisible = true;
                        mEndMarker.setAlpha(1f);
                    }
                }, 0);
            }
        } else {
            if (mEndVisible) {
                mEndMarker.setAlpha(0f);
                mEndVisible = false;
            }
            endX = 0;
        }

        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(
                startX,
                mMarkerTopOffset,
                -mStartMarker.getWidth(),
                -mStartMarker.getHeight());
        mStartMarker.setLayoutParams(params);

        params = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(
                endX,
                mWaveformView.getMeasuredHeight() - mEndMarker.getHeight() - mMarkerBottomOffset,
                -mStartMarker.getWidth(),
                -mStartMarker.getHeight());
        mEndMarker.setLayoutParams(params);
    }

    private synchronized void handlePause() {
        if (mPlayer != null && mPlayer.isPlaying()) {
            mPlayer.pause();
        }
        mWaveformView.setPlayback(-1);
        mIsPlaying = false;
        enableDisableButtons();
    }

    private synchronized void onPlay(int startPosition) {
        if (mIsPlaying) {
            handlePause();
            return;
        }

        if (mPlayer == null) {
            // Not initialized yet
            return;
        }

        try {
            mPlayStartMsec = mWaveformView.pixelsToMillisecs(startPosition);
            if (startPosition < mStartPos) {
                mPlayEndMsec = mWaveformView.pixelsToMillisecs(mStartPos);
            } else if (startPosition > mEndPos) {
                mPlayEndMsec = mWaveformView.pixelsToMillisecs(mMaxPos);
            } else {
                mPlayEndMsec = mWaveformView.pixelsToMillisecs(mEndPos);
            }
            mPlayer.setOnCompletionListener(new SamplePlayer.OnCompletionListener() {
                @Override
                public void onCompletion() {
                    handlePause();
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            onPlay(mStartPos);
                        }
                    });
                }
            });
            mIsPlaying = true;

            mPlayer.seekTo(mPlayStartMsec);
            mPlayer.start();
            updateDisplay();
            enableDisableButtons();
        } catch (Exception e) {
            return;
        }
    }
    private void resetPositions() {
        WaveformView.PlayChunk chunk = mWaveformView.getChunk(mPlayChunkIdx);
        if (chunk == null){
            mStartPos = mWaveformView.secondsToPixels(0.0);
            mEndPos = mWaveformView.secondsToPixels(15.0);
            return;
        }

        mStartPos = mWaveformView.secondsToPixels(chunk.startSec);
        mEndPos = mWaveformView.secondsToPixels(chunk.endSec);

        String position = String.format("%d / %d", mPlayChunkIdx + 1, mWaveformView.getChunkNums());
        mPositionText.setText(position);
    }

    private long getCurrentTime() {
        return System.nanoTime() / 1000000;
    }

    private void setOffsetGoalNoUpdate(int offset) {
        if (mTouchDragging) {
            return;
        }

        mOffsetGoal = offset;
        if (mOffsetGoal + mWidth / 2 > mMaxPos)
            mOffsetGoal = mMaxPos - mWidth / 2;
        if (mOffsetGoal < 0)
            mOffsetGoal = 0;
    }

    private void setOffsetGoalStart() {
        setOffsetGoal(mStartPos - mWidth / 2);
    }

    private void setOffsetGoalStartNoUpdate() {
        setOffsetGoalNoUpdate(mStartPos - mWidth / 2);
    }

    private void setOffsetGoalEnd() {
        setOffsetGoal(mEndPos - mWidth / 2);
    }

    private void setOffsetGoalEndNoUpdate() {
        setOffsetGoalNoUpdate(mEndPos - mWidth / 2);
    }

    private void setOffsetGoal(int offset) {
        setOffsetGoalNoUpdate(offset);
        updateDisplay();
    }

    private String formatTime(int pixels) {
        if (mWaveformView != null && mWaveformView.isInitialized()) {
            return formatDecimal(mWaveformView.pixelsToSeconds(pixels));
        } else {
            return "";
        }
    }

    private String formatDecimal(double x) {
        int xWhole = (int)x;
        int xFrac = (int)(100 * (x - xWhole) + 0.5);

        if (xFrac >= 100) {
            xWhole++; //Round up
            xFrac -= 100; //Now we need the remainder after the round up
            if (xFrac < 10) {
                xFrac *= 10; //we need a fraction that is 2 digits long
            }
        }

        if (xFrac < 10)
            return xWhole + ".0" + xFrac;
        else
            return xWhole + "." + xFrac;
    }

    private int trap(int pos) {
        if (pos < 0)
            return 0;
        if (pos > mMaxPos)
            return mMaxPos;
        return pos;
    }

    private OnClickListener mPlayListener = new OnClickListener() {
        public void onClick(View sender) {
            onPlay(mStartPos);
        }
    };

    private OnClickListener mFfwdListener = new OnClickListener() {
        public void onClick(View sender) {
            if (mIsPlaying) {
                final int chunkCount = mWaveformView.getChunkNums();
                if (mPlayChunkIdx + 1 >= chunkCount)
                    return;

                handlePause();
                ++mPlayChunkIdx;
                resetPositions();
                onPlay(mStartPos);
            }
        }
    };

    private OnClickListener mRewindListener = new OnClickListener() {
        public void onClick(View sender) {
            if (mIsPlaying) {
                if (mPlayChunkIdx == 0)
                    return;

                handlePause();
                --mPlayChunkIdx;
                resetPositions();
                onPlay(mStartPos);
            }
        }
    };

    private void enableDisableButtons() {
        if (mIsPlaying) {
            mPlayButton.setImageResource(android.R.drawable.ic_media_pause);
            mPlayButton.setContentDescription(getResources().getText(R.string.stop));
        } else {
            mPlayButton.setImageResource(android.R.drawable.ic_media_play);
            mPlayButton.setContentDescription(getResources().getText(R.string.play));
        }
    }

}
