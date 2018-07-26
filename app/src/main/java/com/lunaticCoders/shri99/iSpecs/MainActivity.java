package com.lunaticCoders.shri99.iSpecs;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.preference.PreferenceManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import com.lunaticCoders.shri99.iSpecs.classifier.Classifier;
import com.lunaticCoders.shri99.iSpecs.classifier.TensorFlowImageClassifier;
import com.google.android.things.pio.PeripheralManager;
import com.google.assistant.embedded.v1alpha1.ConverseResponse;
import com.google.auth.oauth2.UserCredentials;
import com.google.rpc.Status;

import org.json.JSONException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity implements ImageReader.OnImageAvailableListener {
    private static final String TAG = "MainActivity";

    private ImagePreprocessor mImagePreprocessor;
    private TextToSpeech mTtsEngine;
    private TtsSpeaker mTtsSpeaker;
    private CameraHandler mCameraHandler;
    private TensorFlowImageClassifier mTensorFlowClassifier;

    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;

    private ImageView mImage;
    private TextView[] mResultViews;

    private AtomicBoolean mReady = new AtomicBoolean(false);


    // Audio constants.
    private static final String PREF_CURRENT_VOLUME = "current_volume";
    private static final int SAMPLE_RATE = 16000;
    private static final int DEFAULT_VOLUME = 100;

    // Hardware peripherals.
    private com.google.android.things.contrib.driver.button.Button mButton;
    private android.widget.Button mButtonWidget;

    // List & adapter to store and display the history of Assistant Requests.
    private EmbeddedAssistant mEmbeddedAssistant;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);
        mImage = (ImageView) findViewById(R.id.imageView);
        mResultViews = new TextView[3];
        mResultViews[0] = (TextView) findViewById(R.id.result1);
        mResultViews[1] = (TextView) findViewById(R.id.result2);
        mResultViews[2] = (TextView) findViewById(R.id.result3);
        mButtonWidget = (android.widget.Button)findViewById(R.id.ass);
        init();
    }

    private void init() {

        mBackgroundThread = new HandlerThread("BackgroundThread");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
        mBackgroundHandler.post(mInitializeOnBackground);

        // Audio routing configuration: use default routing.
        AudioDeviceInfo audioInputDevice = findAudioDevice(AudioManager.GET_DEVICES_INPUTS, AudioDeviceInfo.TYPE_USB_DEVICE);
        AudioDeviceInfo audioOutputDevice = findAudioDevice(AudioManager.GET_DEVICES_OUTPUTS, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
            if (audioInputDevice == null) {
                Log.e(TAG, "failed to find I2S audio input device, using default");
            }
            if (audioOutputDevice == null) {
                Log.e(TAG, "failed to found I2S audio output device, using default");
            }

        try {

            PeripheralManager pioService = PeripheralManager.getInstance();;
            mButton = new com.google.android.things.contrib.driver.button.Button(BoardDefaults.getGPIOForButton(),
                        com.google.android.things.contrib.driver.button.Button.LogicState.PRESSED_WHEN_HIGH);
        } catch (IOException e) {
            Log.e(TAG, "error configuring peripherals:", e);
            return;
        }
        mButton.setOnButtonEventListener(new com.google.android.things.contrib.driver.button.Button.OnButtonEventListener() {
            @Override
            public void onButtonEvent(com.google.android.things.contrib.driver.button.Button button, boolean pressed) {
               if(pressed) {
                   mBackgroundHandler.post(mBackgroundClickHandler);
                   mEmbeddedAssistant.startConversation();
               }
            }
        });
        // Set volume from preferences
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        int initVolume = preferences.getInt(PREF_CURRENT_VOLUME, DEFAULT_VOLUME);
        Log.i(TAG, "setting audio track volume to: " + initVolume);

        UserCredentials userCredentials = null;
        try {
            userCredentials =
                    EmbeddedAssistant.generateCredentials(this, R.raw.credentials);
        } catch (IOException | JSONException e) {
            Log.e(TAG, "error getting user credentials", e);
        }
        mEmbeddedAssistant = new EmbeddedAssistant.Builder()
                .setCredentials(userCredentials)
                .setAudioInputDevice(audioInputDevice)
                .setAudioOutputDevice(audioOutputDevice)
                .setAudioSampleRate(SAMPLE_RATE)
                .setAudioVolume(initVolume)
                .setRequestCallback(new EmbeddedAssistant.RequestCallback() {
                    @Override
                    public void onRequestStart() {
                        Log.i(TAG, "starting assistant request, enable microphones");
                        mButtonWidget.setText(R.string.button_listening);
                        mButtonWidget.setEnabled(false);
                    }

                    @Override
                    public void onSpeechRecognition(String utterance) {
                    }
                })
                .setConversationCallback(new EmbeddedAssistant.ConversationCallback() {
                    @Override
                    public void onResponseStarted() {

                    }

                    @Override
                    public void onResponseFinished() {

                    }

                    @Override
                    public void onConversationEvent(ConverseResponse.EventType eventType) {
                        Log.d(TAG, "converse response event: " + eventType);
                    }

                    @Override
                    public void onAudioSample(ByteBuffer audioSample) {
                    }

                    @Override
                    public void onConversationError(Status error) {
                        Log.e(TAG, "converse response error: " + error);
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        Log.e(TAG, "converse error", throwable);
                        // Reset display
                        mButtonWidget.setText(R.string.button_retry);
                        mButtonWidget.setEnabled(true);
                    }

                    @Override
                    public void onVolumeChanged(int percentage) {
                        Log.i(TAG, "assistant volume changed: " + percentage);
                        // Update our shared preferences
                        SharedPreferences.Editor editor = PreferenceManager
                                .getDefaultSharedPreferences(MainActivity.this)
                                .edit();
                        editor.putInt(PREF_CURRENT_VOLUME, percentage);
                        editor.apply();
                    }

                    @Override
                    public void onConversationFinished() {
                        Log.i(TAG, "assistant conversation finished");
                        mButtonWidget.setText(R.string.button_new_request);
                        mButtonWidget.setEnabled(true);
                    }
                })
                .build();
        mEmbeddedAssistant.connect();
    }
    private AudioDeviceInfo findAudioDevice(int deviceFlag, int deviceType) {
        AudioManager manager = (AudioManager) this.getSystemService(Context.AUDIO_SERVICE);
        AudioDeviceInfo[] adis = manager.getDevices(deviceFlag);
        for (AudioDeviceInfo adi : adis) {
            if (adi.getType() == deviceType) {
                return adi;
            }
        }
        return null;
    }


    private Runnable mInitializeOnBackground = new Runnable() {
        @Override
        public void run() {
            mImagePreprocessor = new ImagePreprocessor();

            mTtsSpeaker = new TtsSpeaker();
            mTtsSpeaker.setHasSenseOfHumor(true);
            mTtsEngine = new TextToSpeech(MainActivity.this,
                    new TextToSpeech.OnInitListener() {
                        @Override
                        public void onInit(int status) {
                            if (status == TextToSpeech.SUCCESS) {
                                mTtsEngine.setLanguage(Locale.US);
                                mTtsEngine.setOnUtteranceProgressListener(utteranceListener);
                                mTtsSpeaker.speakReady(mTtsEngine);
                            } else {
                                Log.w(TAG, "Could not open TTS Engine (onInit status=" + status
                                        + "). Ignoring text to speech");
                                mTtsEngine = null;
                            }
                        }
                    });
            mCameraHandler = CameraHandler.getInstance();
            mCameraHandler.initializeCamera(
                    MainActivity.this, mBackgroundHandler,
                    MainActivity.this);

            mTensorFlowClassifier = new TensorFlowImageClassifier(MainActivity.this);

            setReady(true);
        }
    };

    private Runnable mBackgroundClickHandler = new Runnable() {
        @Override
        public void run() {
            if (mTtsEngine != null) {
                mTtsSpeaker.speakShutterSound(mTtsEngine);
            }
            mCameraHandler.takePicture();
        }
    };

    private UtteranceProgressListener utteranceListener = new UtteranceProgressListener() {
        @Override
        public void onStart(String utteranceId) {
            setReady(false);
        }

        @Override
        public void onDone(String utteranceId) {
            setReady(true);
        }

        @Override
        public void onError(String utteranceId) {
            setReady(true);
        }
    };

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        Log.d(TAG, "Received key up: " + keyCode + ". Ready = " + mReady.get());
        if (keyCode == KeyEvent.KEYCODE_ENTER) {
            if (mReady.get()) {
                setReady(false);

            } else {
                Log.i(TAG, "Sorry, processing hasn't finished. Try again in a few seconds");
            }
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void setReady(boolean ready) {
        mReady.set(ready);
    }

    @Override
    public void onImageAvailable(ImageReader reader) {
        final Bitmap bitmap;
        try (Image image = reader.acquireNextImage()) {
            bitmap = mImagePreprocessor.preprocessImage(image);
        }

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mImage.setImageBitmap(bitmap);
            }
        });

        final List<Classifier.Recognition> results = mTensorFlowClassifier.doRecognize(bitmap);

        Log.d(TAG, "Got the following results from Tensorflow: " + results);
        if (mTtsEngine != null) {
            // speak out loud the result of the image recognition
            mTtsSpeaker.speakResults(mTtsEngine, results);
        } else {
            // if theres no TTS, we don't need to wait until the utterance is spoken, so we set
            // to ready right away.
            setReady(true);
        }

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < mResultViews.length; i++) {
                    if (results.size() > i) {
                        Classifier.Recognition r = results.get(i);
                        mResultViews[i].setText(r.getTitle() + " : " + r.getConfidence().toString());
                    } else {
                        mResultViews[i].setText(null);
                    }
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (mBackgroundThread != null) mBackgroundThread.quit();
        } catch (Throwable t) {
            // close quietly
        }
        mBackgroundThread = null;
        mBackgroundHandler = null;

        try {
            if (mCameraHandler != null) mCameraHandler.shutDown();
        } catch (Throwable t) {
            // close quietly
        }
        try {
            if (mTensorFlowClassifier != null) mTensorFlowClassifier.destroyClassifier();
        } catch (Throwable t) {
            // close quietly
        }

        if (mTtsEngine != null) {
            mTtsEngine.stop();
            mTtsEngine.shutdown();
        }
        mEmbeddedAssistant.destroy();
    }

    public void test(View view) {
        mBackgroundHandler.post(mBackgroundClickHandler);
    }
}
