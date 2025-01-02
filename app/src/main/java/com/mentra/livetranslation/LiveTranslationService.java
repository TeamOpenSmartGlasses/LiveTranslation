package com.mentra.livetranslation;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;
import com.teamopensmartglasses.augmentoslib.AugmentOSLib;
import com.teamopensmartglasses.augmentoslib.DataStreamType;
import com.teamopensmartglasses.augmentoslib.SmartGlassesAndroidService;
import com.teamopensmartglasses.augmentoslib.TranscriptProcessor;
import com.teamopensmartglasses.augmentoslib.events.StartAsrStreamRequestEvent;
import com.teamopensmartglasses.augmentoslib.events.StopAsrStreamRequestEvent;
import com.teamopensmartglasses.augmentoslib.events.TranslateOutputEvent;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.EventBusException;
import org.greenrobot.eventbus.Subscribe;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class LiveTranslationService extends SmartGlassesAndroidService {
    public static final String TAG = "LiveTranslationService";

    public AugmentOSLib augmentOSLib;
    ArrayList<String> responsesBuffer;
    ArrayList<String> transcriptsBuffer;
    ArrayList<String> responsesToShare;
    Handler debugTranscriptsHandler = new Handler(Looper.getMainLooper());
    private boolean debugTranscriptsRunning = false;

    private String translationText = "";

    private String finalTranslationText = "";
    private boolean segmenterLoaded = false;
    private boolean segmenterLoading = false;
    private boolean hasUserBeenNotified = false;

    private DisplayQueue displayQueue;

    private Handler transcribeLanguageCheckHandler;
    private String lastTranscribeLanguage = null;
    private Handler translateLanguageCheckHandler;
    private String lastTranslateLanguage = null;
    private final int maxNormalTextCharsPerTranscript = 30;
    private final int maxCharsPerHanziTranscript = 12;
    private final int maxLines = 3;
    private final TranscriptProcessor normalTextTranscriptProcessor = new TranscriptProcessor(maxNormalTextCharsPerTranscript, maxLines);
    private final TranscriptProcessor hanziTextTranscriptProcessor = new TranscriptProcessor(maxCharsPerHanziTranscript, maxLines);
    private final Handler callTimeoutHandler = new Handler(Looper.getMainLooper());
    private Runnable timeoutRunnable;
    public LiveTranslationService() {
        super();
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // Create AugmentOSLib instance with context: this
        augmentOSLib = new AugmentOSLib(this);

        // Subscribe to a data stream (ex: transcription), and specify a callback function
        // Initialize the language check handler
        transcribeLanguageCheckHandler = new Handler(Looper.getMainLooper());
        translateLanguageCheckHandler = new Handler(Looper.getMainLooper());

        // Start periodic language checking
        startTranscribeLanguageCheckTask();
        startTranslateLanguageCheckTask();

        //setup event bus subscribers
        setupEventBusSubscribers();

        displayQueue = new DisplayQueue();

        //make responses holder
        responsesBuffer = new ArrayList<>();
        responsesToShare = new ArrayList<>();
        responsesBuffer.add("Welcome to AugmentOS.");

        //make responses holder
        transcriptsBuffer = new ArrayList<>();

//        requestTranslation();

        Log.d(TAG, "Convoscope service started");
        completeInitialization();

    }

    private void requestTranslation() {
        String transcriptionLanguage = getChosenTranscribeLanguage(this);
        String translationLanguage = getChosenSourceLanguage(this);
        augmentOSLib.subscribe(new StartAsrStreamRequestEvent(transcriptionLanguage, translationLanguage));
    }

    private void stopTranslation() {
        augmentOSLib.subscribe(new StopAsrStreamRequestEvent());
    }

    protected void setupEventBusSubscribers() {
        try {
            EventBus.getDefault().register(this);
        }
        catch(EventBusException e){
            e.printStackTrace();
        }
    }

    public void processTranscriptionCallback(String transcript, String languageCode, long timestamp, boolean isFinal) {
        Log.d(TAG, "Got a transcript: " + transcript + ", which is FINAL? " + isFinal + " and has language code: " + languageCode);

    }

    public void processTranslationCallback(String transcript, String languageCode, long timestamp, boolean isFinal, boolean foo) {
        Log.d(TAG, "Got a translation: " + transcript + ", which is FINAL? " + isFinal + " and has language code: " + languageCode);
    }

    public void completeInitialization(){
        Log.d(TAG, "COMPLETE CONVOSCOPE INITIALIZATION");

        displayQueue.startQueue();
//        augmentOSLib.subscribe(DataStreamType.START_TRANSLATION_STREAM, this::processTranscriptionCallback);
    }

    @Override
    public void onDestroy(){
        Log.d(TAG, "onDestroy: Called");
        Log.d(TAG, "Deinit augmentOSLib");
//        augmentOSLib.subscribe(DataStreamType.KILL_TRANSLATION_STREAM, this::processTranscriptionCallback);

        augmentOSLib.deinit();
        Log.d(TAG, "csePoll handler remove");
        Log.d(TAG, "displayPoll handler remove");
        Log.d(TAG, "debugTranscriptsHnalderPoll handler remove");
        if (debugTranscriptsRunning) {
            debugTranscriptsHandler.removeCallbacksAndMessages(null);
        }
        Log.d(TAG, "locationSystem remove");
        EventBus.getDefault().unregister(this);

        if (displayQueue != null) displayQueue.stopQueue();

        stopTranslation();
        Log.d(TAG, "ran onDestroy");
        super.onDestroy();
    }

    @Subscribe
    public void onTranslateTranscript(TranslateOutputEvent event) {
        String text = event.text;
        String languageCode = event.languageCode;
        long time = event.timestamp;
        boolean isFinal = event.isFinal;
        boolean isTranslated = event.isTranslated;

        if (isFinal && !isTranslated) {
            transcriptsBuffer.add(text);
        }
        if (isTranslated) {
            debounceAndShowTranscriptOnGlasses(text, isFinal);
        }
    }

    private Handler glassesTranscriptDebounceHandler = new Handler(Looper.getMainLooper());
    private Runnable glassesTranscriptDebounceRunnable;
    private long glassesTranscriptLastSentTime = 0;
    private long glassesTranslatedTranscriptLastSentTime = 0;
    private final long GLASSES_TRANSCRIPTS_DEBOUNCE_DELAY = 400; // in milliseconds

    private void debounceAndShowTranscriptOnGlasses(String transcript, boolean isFinal) {
        glassesTranscriptDebounceHandler.removeCallbacks(glassesTranscriptDebounceRunnable);
        long currentTime = System.currentTimeMillis();

        if (isFinal) {
            showTranscriptsToUser(transcript, true);
            return;
        }

        if (currentTime - glassesTranslatedTranscriptLastSentTime >= GLASSES_TRANSCRIPTS_DEBOUNCE_DELAY) {
            showTranscriptsToUser(transcript, false);
            glassesTranslatedTranscriptLastSentTime = currentTime;
        } else {
            glassesTranscriptDebounceRunnable = () -> {
                showTranscriptsToUser(transcript, false);
                glassesTranslatedTranscriptLastSentTime = System.currentTimeMillis();
            };
            glassesTranscriptDebounceHandler.postDelayed(glassesTranscriptDebounceRunnable, GLASSES_TRANSCRIPTS_DEBOUNCE_DELAY);
        }
    }

    private void showTranscriptsToUser(final String transcript, final boolean isFinal) {
        String processed_transcript = transcript;

        if (getChosenSourceLanguage(this).equals("Chinese (Pinyin)")) {
            if(segmenterLoaded) {
                processed_transcript = convertToPinyin(transcript);
            } else if (!segmenterLoading) {
                new Thread(this::loadSegmenter).start();
                hasUserBeenNotified = true;
                displayQueue.addTask(new DisplayQueue.Task(() -> augmentOSLib.sendTextWall("Loading Pinyin Converter, Please Wait..."), true, false, false));
            } else if (!hasUserBeenNotified) {  //tell user we are loading the pinyin converter
                hasUserBeenNotified = true;
                displayQueue.addTask(new DisplayQueue.Task(() -> augmentOSLib.sendTextWall("Loading Pinyin Converter, Please Wait..."), true, false, false));
            }
        }

        sendTextWallLiveTranslationLiveCaption(processed_transcript, isFinal);
    }

    private void loadSegmenter() {
        segmenterLoading = true;
        final JiebaSegmenter segmenter = new JiebaSegmenter();
        segmenterLoaded = true;
        segmenterLoading = false;
//        displayQueue.addTask(new DisplayQueue.Task(() -> sendTextWall("Pinyin Converter Loaded!"), true, false));
    }

    private String convertToPinyin(final String chineseText) {
        final JiebaSegmenter segmenter = new JiebaSegmenter();

        final List<SegToken> tokens = segmenter.process(chineseText, JiebaSegmenter.SegMode.SEARCH);

        final HanyuPinyinOutputFormat format = new HanyuPinyinOutputFormat();
        format.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        format.setToneType(HanyuPinyinToneType.WITH_TONE_MARK);
        format.setVCharType(HanyuPinyinVCharType.WITH_U_UNICODE);

        StringBuilder pinyinText = new StringBuilder();

        for (SegToken token : tokens) {
            StringBuilder tokenPinyin = new StringBuilder();
            for (char character : token.word.toCharArray()) {
                try {
                    String[] pinyinArray = PinyinHelper.toHanyuPinyinStringArray(character, format);
                    if (pinyinArray != null) {
                        // Use the first Pinyin representation if there are multiple
                        tokenPinyin.append(pinyinArray[0]);
                    } else {
                        // If character is not a Chinese character, append it as is
                        tokenPinyin.append(character);
                    }
                } catch (BadHanyuPinyinOutputFormatCombination e) {
                    e.printStackTrace();
                }
            }
            // Ensure the token is concatenated with a space only if it's not empty
            if (tokenPinyin.length() > 0) {
                pinyinText.append(tokenPinyin.toString()).append(" ");
            }
        }

        // Replace multiple spaces with a single space, but preserve newlines
        String cleanText = pinyinText.toString().trim().replaceAll("[ \\t]+", " ");  // Replace spaces and tabs only

        return cleanText;
    }

    public void sendTextWallLiveTranslationLiveCaption(final String newText, final boolean isFinal) {
        callTimeoutHandler.removeCallbacks(timeoutRunnable);

        timeoutRunnable = () -> {
            // Call your desired function here
            augmentOSLib.sendHomeScreen();
        };
        callTimeoutHandler.postDelayed(timeoutRunnable, 16000);

        if (!newText.isEmpty()) {
                if (getChosenSourceLanguage(this).equals("Chinese (Hanzi)") ||
                        getChosenSourceLanguage(this).equals("Chinese (Pinyin)") && !segmenterLoaded) {
                    translationText = hanziTextTranscriptProcessor.processString(finalTranslationText + " " + newText, isFinal);
                } else {
                    translationText = normalTextTranscriptProcessor.processString(finalTranslationText + " " + newText, isFinal);
                }

                if (isFinal) {
                    finalTranslationText = newText;
                }

                // Limit the length of the final translation text
                if (finalTranslationText.length() > 5000) {
                    finalTranslationText = finalTranslationText.substring(finalTranslationText.length() - 5000);
                }
        }

        String textBubble = "\uD83D\uDDE8";

        final String finalLiveTranslationDisplayText;
        if (!translationText.isEmpty()) {
            finalLiveTranslationDisplayText = textBubble + translationText + "\n";
        } else {
            finalLiveTranslationDisplayText = "";
        }

        displayQueue.addTask(new DisplayQueue.Task(() -> augmentOSLib.sendDoubleTextWall(finalLiveTranslationDisplayText, ""), true, false, true));
    }

    public static void saveChosenSourceLanguage(Context context, String sourceLanguageString) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putString(context.getResources().getString(R.string.SHARED_PREF_SOURCE_LANGUAGE), sourceLanguageString)
                .apply();
    }

    public static String getChosenSourceLanguage(Context context) {
        String sourceLanguageString = PreferenceManager.getDefaultSharedPreferences(context).getString(context.getResources().getString(R.string.SHARED_PREF_SOURCE_LANGUAGE), "");
        if (sourceLanguageString.equals("")){
            saveChosenSourceLanguage(context, "English");
            sourceLanguageString = "English";
        }
        return sourceLanguageString;
    }

    public static void saveChosenTranscribeLanguage(Context context, String transcribeLanguageString) {
        Log.d(TAG, "set saveChosenTranscribeLanguage");
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putString(context.getResources().getString(R.string.SHARED_PREF_TRANSCRIBE_LANGUAGE), transcribeLanguageString)
                .apply();
    }

    public static String getChosenTranscribeLanguage(Context context) {
        String transcribeLanguageString = PreferenceManager.getDefaultSharedPreferences(context).getString(context.getResources().getString(R.string.SHARED_PREF_TRANSCRIBE_LANGUAGE), "");
        if (transcribeLanguageString.equals("")){
            saveChosenTranscribeLanguage(context, "Chinese");
            transcribeLanguageString = "Chinese";
        }
        return transcribeLanguageString;
    }

    private void startTranscribeLanguageCheckTask() {
        transcribeLanguageCheckHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // Get the currently selected transcription language
                String currentTranscribeLanguage = getChosenTranscribeLanguage(getApplicationContext());

                // If the language has changed or this is the first call
                if (lastTranscribeLanguage == null || !lastTranscribeLanguage.equals(currentTranscribeLanguage)) {
                    if (lastTranscribeLanguage != null) {
                        requestTranslation();
                        finalTranslationText = "";
                    }

                    lastTranscribeLanguage = currentTranscribeLanguage;
                }

                // Schedule the next check
                transcribeLanguageCheckHandler.postDelayed(this, 333); // Approximately 3 times a second
            }
        }, 50);
    }

    private void startTranslateLanguageCheckTask() {
        translateLanguageCheckHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                String currentTranslateLanguage = getChosenSourceLanguage(getApplicationContext());

                // If the language has changed or this is the first call
                if (lastTranslateLanguage == null || !lastTranslateLanguage.equals(currentTranslateLanguage)) {
                    if (lastTranscribeLanguage != null) {
                        requestTranslation();
                        finalTranslationText = "";
                    }

                    lastTranslateLanguage = currentTranslateLanguage;
                }

                // Schedule the next check
                translateLanguageCheckHandler.postDelayed(this, 333); // Approximately 3 times a second
            }
        }, 50);
    }
}
