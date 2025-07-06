package com.example.tcpclipboard;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.widget.*;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.atilika.kuromoji.ipadic.Token;
import com.atilika.kuromoji.ipadic.Tokenizer;



public class MainActivity extends AppCompatActivity {
    // Unicode hair space
    final String hairSpace = "\u200A"; // or "\u2009" for thin space

    private EditText ipInput, portInput;
    private Button connectButton, stopButton;
    private Switch useJishoSwitch;

    private TextView tcpOutput;
    private TextView definitionOutput; // New TextView for definitions
    private ExecutorService executorService;
    private Map<String, Boolean> wordCache = new HashMap<>();
    Tokenizer tokenizer = new Tokenizer(); // reuse this

    private static class JishoResult {
        public boolean isValid;
        public String word;
        public String reading;
        public String definition;
        public String partOfSpeech;
    }

    private final Map<String, JishoResult> resultCache = new HashMap<>();

    private static final String DB_NAME = "japanese.db";
    private SQLiteDatabase db;

    private void copyDbIfNeeded() {
        File dbFile = getDatabasePath(DB_NAME);
        if (dbFile.exists()) return;
        else Log.d("Tcp", "No DB FILE!");
        dbFile.getParentFile().mkdirs();
        try (InputStream is = getAssets().open(DB_NAME);
             OutputStream os = new FileOutputStream(dbFile)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) > 0) os.write(buf, 0, len);
        } catch (IOException e) {
            Log.e("DBCopy", "Error copying DB", e);
        }
    }

    private void openDatabase() {
        File dbFile = getDatabasePath(DB_NAME);
        db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
    }
    // BroadcastReceiver to listen for TCP data
    private final BroadcastReceiver tcpReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d("TcpReceiver", "Broadcast received!");
            if ("com.example.tcpclipboard.NEW_DATA".equals(intent.getAction())) {
                String message = intent.getStringExtra("data");
                Log.d("TcpReceiver", "Message received: " + message);
                displayJapaneseText(message);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Copy & open the DB
        copyDbIfNeeded();
        openDatabase();

        setContentView(R.layout.activity_main);

        useJishoSwitch = findViewById(R.id.use_jisho_switch);


        ipInput = findViewById(R.id.ip_input);

        SharedPreferences prefs = getSharedPreferences("tcp_prefs", MODE_PRIVATE);
        String savedIp = prefs.getString("last_ip", "");
        ipInput.setText(savedIp);


        portInput = findViewById(R.id.port_input);
        connectButton = findViewById(R.id.connect_button);
        stopButton = findViewById(R.id.stop_button);
        tcpOutput = findViewById(R.id.tcp_output);
        definitionOutput = findViewById(R.id.definition_output); // Initialize the new TextView
        executorService = Executors.newCachedThreadPool();

        // Make the text bigger
        tcpOutput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40); // Increased from default (~14sp) to 24sp
        definitionOutput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24); // Slightly smaller for definitions

        // Confirm TextView is working
        tcpOutput.setText("If you see this, the TextView is working");
        definitionOutput.setText("Definitions will appear here when you tap Japanese words above");

        connectButton.setOnClickListener(v -> {

            String ip = ipInput.getText().toString();
            getSharedPreferences("tcp_prefs", MODE_PRIVATE).edit().putString("last_ip", ip).apply();

//            String ip = ipInput.getText().toString();
            String portStr = portInput.getText().toString();
            int port = portStr.isEmpty() ? 80 : Integer.parseInt(portStr);

            Intent serviceIntent = new Intent(this, TcpService.class);
            serviceIntent.putExtra("ip", ip);
            serviceIntent.putExtra("port", port);
            startForegroundService(serviceIntent);
        });

        stopButton.setOnClickListener(v -> {
            Intent stopIntent = new Intent(this, TcpService.class);
            stopService(stopIntent);
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter("com.example.tcpclipboard.NEW_DATA");

        // Use LocalBroadcastManager for app-internal communication
        LocalBroadcastManager.getInstance(this).registerReceiver(tcpReceiver, filter);

        Log.d("MainActivity", "Registered LOCAL broadcast receiver");

        // Test broadcast to verify receiver is working
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> {
            Log.d("MainActivity", "Sending test LOCAL broadcast");
            Intent testIntent = new Intent("com.example.tcpclipboard.NEW_DATA");
            testIntent.putExtra("data", "お腹がすいた");
            LocalBroadcastManager.getInstance(this).sendBroadcast(testIntent);
        }, 2000);
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(tcpReceiver);
        } catch (IllegalArgumentException e) {
            Log.w("MainActivity", "Receiver was not registered", e);
        }
        Log.d("MainActivity", "Unregistered LOCAL broadcast receiver");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executorService != null) {
            executorService.shutdown();
        }
    }

    private String lookupLocalDefinition(String word) {
        String sql = "SELECT kanji, reading, meaning FROM dict_index WHERE kanji = ? OR reading = ? LIMIT 1";
        try (Cursor c = db.rawQuery(sql, new String[]{word, word})) {
            if (c.moveToFirst()) {
                String surface = c.getString(0); // kanji
                String reading = c.getString(1); // reading
                String meaning = c.getString(2); // meaning
                if (meaning != null && !meaning.isEmpty()) {
                    return surface + " [" + reading + "]\n" + meaning;
                }
            }
        }
        return null;
    }



    private void preloadJishoCandidates(String text) {
        executorService.submit(() -> {
            int maxLength = 10; // Max word length to check

            for (int start = 0; start < text.length(); start++) {
                for (int end = start + 1; end <= text.length() && end - start <= maxLength; end++) {
                    String candidate = text.substring(start, end);

                    if (!isAllJapanese(candidate)) break;
                    String cleaned = removeTrailingParticle(candidate);
                    if (cleaned.isEmpty()) break;

                    if (!resultCache.containsKey(cleaned)) {
                        JishoResult result = fetchJishoData(cleaned);
                        if (result.isValid) {
                            resultCache.put(cleaned, result);
                        }
                    }
                }
            }
        });
    }

    private void showDefinitionInTextView(String word) {
        runOnUiThread(() -> definitionOutput.setText(R.string.loading));

        new Thread(() -> {
            try {
                String apiUrl = "https://jisho.org/api/v1/search/words?keyword=" +
                        URLEncoder.encode(word, "UTF-8");

                URL url = new URL(apiUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder result = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    result.append(line);
                }
                reader.close();

                JSONObject json = new JSONObject(result.toString());
                JSONArray data = json.getJSONArray("data");

                if (data.length() > 0) {
                    JSONObject entry = data.getJSONObject(0);
                    JSONArray japanese = entry.getJSONArray("japanese");
                    JSONArray senses = entry.getJSONArray("senses");

                    String reading = japanese.getJSONObject(0).optString("reading", "");
                    String definition = senses.getJSONObject(0).getJSONArray("english_definitions").join(", ").replace("\"", "");

                    StringBuilder messageBuilder = new StringBuilder();
                    messageBuilder.append(word);
                    if (!reading.isEmpty()) {
                        messageBuilder.append(" [").append(reading).append("]");
                    }
                    messageBuilder.append("\nMeaning: ").append(definition);

                    runOnUiThread(() -> definitionOutput.setText(messageBuilder.toString()));
                } else {
                    runOnUiThread(() -> definitionOutput.setText("No definition found for: " + word));
                }

            } catch (Exception e) {
                Log.e("MainActivity", "Error fetching definition for: " + word, e);
                runOnUiThread(() -> definitionOutput.setText(R.string.definition_error));
            }
        }).start();
    }



    private void displayJapaneseText(String message) {
        List<Token> tokens = tokenizer.tokenize(message);
        SpannableStringBuilder builder = new SpannableStringBuilder();

        for (Token token : tokens) {
            String surface = token.getSurface();              // Actual visible text
            String baseForm = token.getBaseForm();            // Dictionary base form
            String clickableWord = baseForm != null && !baseForm.equals("*") ? baseForm : surface;

            int start = builder.length();
            builder.append(hairSpace).append(surface); // Insert space before word

            builder.setSpan(new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    if (useJishoSwitch.isChecked()) {
                        showDefinitionInTextView(clickableWord);
                    } else {
                        String def = lookupLocalDefinition(clickableWord);
                        if (def == null) def = getString(R.string.no_definition);
                        definitionOutput.setText(def);
                    }
                }

                @Override
                public void updateDrawState(android.text.TextPaint ds) {
                    super.updateDrawState(ds);
                    ds.setUnderlineText(false);
                }
            }, start, start + 1 + surface.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        runOnUiThread(() -> {
            tcpOutput.setText(builder);
            tcpOutput.setMovementMethod(LinkMovementMethod.getInstance());
            tcpOutput.setHighlightColor(0x6633B5E5); // Light blue highlight on click
        });
    }

    private boolean isJapaneseCharacter(char c) {
        return Character.UnicodeBlock.of(c) == Character.UnicodeBlock.HIRAGANA ||
                Character.UnicodeBlock.of(c) == Character.UnicodeBlock.KATAKANA ||
                Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                c == 'ー' || c == '々';
    }

    private void findAndShowWord(String fullText, int clickedIndex) {
        executorService.submit(() -> {
            String bestWord = null;
            int maxLength = 0;
            JishoResult bestResult = null;

            // Check cached results first (fast)
            for (int end = clickedIndex + 1; end <= fullText.length(); end++) {
                String candidate = fullText.substring(clickedIndex, end);
                if (!isAllJapanese(candidate)) break;

                String cleanCandidate = removeTrailingParticle(candidate);
                if (cleanCandidate.isEmpty()) break;

                JishoResult cached = resultCache.get(cleanCandidate);
                if (cached != null && cached.isValid && cleanCandidate.length() > maxLength) {
                    bestWord = cleanCandidate;
                    bestResult = cached;
                    maxLength = cleanCandidate.length();
                    final String message = buildDefinitionText(bestResult);
                    runOnUiThread(() -> definitionOutput.setText(message));
                }

                cached = resultCache.get(candidate);
                if (cached != null && cached.isValid && candidate.length() > maxLength) {
                    bestWord = candidate;
                    bestResult = cached;
                    maxLength = candidate.length();
                    final String message = buildDefinitionText(bestResult);
                    runOnUiThread(() -> definitionOutput.setText(message));
                }
            }

            // Fallback API calls with throttling
            if (bestResult == null) {
                for (int end = clickedIndex + 1; end <= fullText.length(); end++) {
                    String candidate = fullText.substring(clickedIndex, end);
                    if (!isAllJapanese(candidate)) break;

                    String cleanCandidate = removeTrailingParticle(candidate);
                    if (cleanCandidate.isEmpty()) break;

                    try {
                        // Throttle calls: sleep 80ms between API calls
                        Thread.sleep(80);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }

                    if (!resultCache.containsKey(cleanCandidate)) {
                        JishoResult result = fetchJishoData(cleanCandidate);
                        if (result.isValid && cleanCandidate.length() > maxLength) {
                            bestWord = cleanCandidate;
                            bestResult = result;
                            maxLength = cleanCandidate.length();
                            resultCache.put(cleanCandidate, result);
                            final String message = buildDefinitionText(bestResult);
                            runOnUiThread(() -> definitionOutput.setText(message));

                            if (maxLength >= 5) break; // Good enough, stop
                        }
                    }

                    if (!resultCache.containsKey(candidate)) {
                        JishoResult result = fetchJishoData(candidate);
                        if (result.isValid && candidate.length() > maxLength) {
                            bestWord = candidate;
                            bestResult = result;
                            maxLength = candidate.length();
                            resultCache.put(candidate, result);
                            final String message = buildDefinitionText(bestResult);
                            runOnUiThread(() -> definitionOutput.setText(message));

                            if (maxLength >= 5) break; // Good enough, stop
                        }
                    }
                }
            }

            if (bestResult == null) {
                runOnUiThread(() -> definitionOutput.setText(R.string.no_definition));
            }
        });
    }





    private String removeTrailingParticle(String text) {
        // Common Japanese particles that should not be included in words
        String[] particles = {
                "は", "が", "を", "に", "へ", "と", "で", "から", "まで", "も", "の",
                "だ", "である", "です", "ます", "だった", "でした", "じゃない", "ではない",
                "って", "という", "といった", "として", "により", "によって", "について",
                "に対して", "に関して", "において", "にて", "より", "や", "とか", "など",
                "なんか", "なんて", "か", "かい", "かな", "かも", "だろう", "でしょう",
                "よね", "よな", "ね", "な", "わ", "よ", "ぞ", "ぜ", "さ", "だけ", "しか",
                "くらい", "ぐらい", "ほど", "ばかり", "っぽい", "らしい", "みたい", "ような"
        };

        // Check if text ends with any particle
        for (String particle : particles) {
            if (text.endsWith(particle)) {
                String withoutParticle = text.substring(0, text.length() - particle.length());
                if (!withoutParticle.isEmpty()) {
                    return withoutParticle;
                }
            }
        }

        return text;
    }

    private boolean isAllJapanese(String text) {
        for (char c : text.toCharArray()) {
            if (!isJapaneseCharacter(c)) {
                return false;
            }
        }
        return true;
    }

    private boolean isPossibleInflection(String word) {
        // Common Japanese inflection patterns
        String[] verbEndings = {
                "た", "だ", "って", "で", "い", "かった", "がった", "くて", "いて", "して",
                "ます", "ました", "ません", "ませんでした", "ている", "ていた", "てる", "てた",
                "れる", "られる", "せる", "させる", "ない", "なかった", "なくて", "ば", "れば",
                "ろう", "よう", "そう", "らしい", "っぽい", "みたい", "ぽい"
        };

        String[] adjectiveEndings = {
                "い", "くて", "く", "かった", "くない", "くなかった", "そう", "げ", "っぽい",
                "らしい", "みたい", "ぽい", "な", "だった", "じゃない", "ではない", "でない"
        };

        // Check if word ends with common inflection patterns
        for (String ending : verbEndings) {
            if (word.endsWith(ending) && word.length() > ending.length()) {
                return true;
            }
        }

        for (String ending : adjectiveEndings) {
            if (word.endsWith(ending) && word.length() > ending.length()) {
                return true;
            }
        }

        return false;
    }

    private JishoResult fetchJishoData(String word) {
        JishoResult result = new JishoResult();
        try {
            String apiUrl = "https://jisho.org/api/v1/search/words?keyword=" + URLEncoder.encode(word, "UTF-8");

            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);

            if (conn.getResponseCode() != 200) return result;

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) response.append(line);
            reader.close();

            JSONObject json = new JSONObject(response.toString());
            JSONArray data = json.getJSONArray("data");

            if (data.length() == 0) return result;

            JSONObject wordEntry = data.getJSONObject(0);
            JSONArray japanese = wordEntry.getJSONArray("japanese");
            JSONArray senses = wordEntry.getJSONArray("senses");

            result.word = japanese.getJSONObject(0).optString("word", word);
            result.reading = japanese.getJSONObject(0).optString("reading", "");
            result.definition = senses.getJSONObject(0).getJSONArray("english_definitions").join(", ").replace("\"", "");
            JSONArray pos = senses.getJSONObject(0).optJSONArray("parts_of_speech");
            result.partOfSpeech = (pos != null && pos.length() > 0) ? pos.join(", ").replace("\"", "") : "";
            result.isValid = true;
        } catch (Exception e) {
            Log.e("MainActivity", "Error fetching Jisho data for: " + word, e);
        }

        return result;
    }

    private String buildDefinitionText(JishoResult result) {
        return result.word + " [" + result.reading + "]\n" +
                "Meaning: " + result.definition + "\n" +
                (result.partOfSpeech.isEmpty() ? "" : "Type: " + result.partOfSpeech);
    }
}