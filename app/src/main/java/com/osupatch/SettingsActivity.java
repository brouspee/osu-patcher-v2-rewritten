package com.osupatch;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Settings Activity for configuring cheat parameters
 * 
 * Settings stored in SharedPreferences:
 * - aim_assist: 0-100 (default 85)
 * - auto_tap: boolean (default false)
 * - auto_tap_percent: 0-100 (default 90)
 * - slider_assist: 0-100 (default 70)
 * - miss_chance: boolean (default true)
 */
public class SettingsActivity extends Activity {

    private static final String PREFS_NAME = "cheat_config";
    
    // Preference keys
    private static final String KEY_AIM_ASSIST = "aim_assist";
    private static final String KEY_AUTO_TAP = "auto_tap";
    private static final String KEY_AUTO_TAP_PERCENT = "auto_tap_percent";
    private static final String KEY_SLIDER_ASSIST = "slider_assist";
    private static final String KEY_MISS_CHANCE = "miss_chance";
    
    // Default values (Legit 80%)
    private static final int DEFAULT_AIM_ASSIST = 85;
    private static final boolean DEFAULT_AUTO_TAP = false;
    private static final int DEFAULT_AUTO_TAP_PERCENT = 90;
    private static final int DEFAULT_SLIDER_ASSIST = 70;
    private static final boolean DEFAULT_MISS_CHANCE = true;
    
    private SharedPreferences prefs;
    
    // UI elements
    private SeekBar sbAimAssist, sbAutoTapPercent, sbSliderAssist;
    private TextView tvAimValue, tvAutoTapValue, tvSliderValue;
    private Switch swAutoTap, swMissChance;
    private Button btnSave, btnReset;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        
        initViews();
        loadSettings();
        setupListeners();
    }
    
    private void initViews() {
        sbAimAssist = findViewById(R.id.sb_aim_assist);
        sbAutoTapPercent = findViewById(R.id.sb_auto_tap_percent);
        sbSliderAssist = findViewById(R.id.sb_slider_assist);
        
        tvAimValue = findViewById(R.id.tv_aim_value);
        tvAutoTapValue = findViewById(R.id.tv_auto_tap_value);
        tvSliderValue = findViewById(R.id.tv_slider_value);
        
        swAutoTap = findViewById(R.id.sw_auto_tap);
        swMissChance = findViewById(R.id.sw_miss_chance);
        
        btnSave = findViewById(R.id.btn_save);
        btnReset = findViewById(R.id.btn_reset);
    }
    
    private void loadSettings() {
        int aimAssist = prefs.getInt(KEY_AIM_ASSIST, DEFAULT_AIM_ASSIST);
        boolean autoTap = prefs.getBoolean(KEY_AUTO_TAP, DEFAULT_AUTO_TAP);
        int autoTapPercent = prefs.getInt(KEY_AUTO_TAP_PERCENT, DEFAULT_AUTO_TAP_PERCENT);
        int sliderAssist = prefs.getInt(KEY_SLIDER_ASSIST, DEFAULT_SLIDER_ASSIST);
        boolean missChance = prefs.getBoolean(KEY_MISS_CHANCE, DEFAULT_MISS_CHANCE);
        
        sbAimAssist.setProgress(aimAssist);
        tvAimValue.setText(aimAssist + "%");
        
        swAutoTap.setChecked(autoTap);
        sbAutoTapPercent.setProgress(autoTapPercent);
        tvAutoTapValue.setText(autoTapPercent + "%");
        
        sbSliderAssist.setProgress(sliderAssist);
        tvSliderValue.setText(sliderAssist + "%");
        
        swMissChance.setChecked(missChance);
    }
    
    private void setupListeners() {
        // AIM ASSIST
        sbAimAssist.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvAimValue.setText(progress + "%");
            }
            public void onStartTrackingTouch(SeekBar seekBar) {}
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        
        // AUTO TAP PERCENT
        sbAutoTapPercent.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvAutoTapValue.setText(progress + "%");
            }
            public void onStartTrackingTouch(SeekBar seekBar) {}
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        
        // SLIDER ASSIST
        sbSliderAssist.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvSliderValue.setText(progress + "%");
            }
            public void onStartTrackingTouch(SeekBar seekBar) {}
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        
        // Auto tap toggle enables/disables percent seekbar
        swAutoTap.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sbAutoTapPercent.setEnabled(isChecked);
            sbAutoTapPercent.setAlpha(isChecked ? 1.0f : 0.5f);
        });
        
        // SAVE button
        btnSave.setOnClickListener(v -> {
            saveSettings();
            finish();
        });
        
        // RESET button
        btnReset.setOnClickListener(v -> {
            resetSettings();
        });
    }
    
    private void saveSettings() {
        SharedPreferences.Editor editor = prefs.edit();
        
        editor.putInt(KEY_AIM_ASSIST, sbAimAssist.getProgress());
        editor.putBoolean(KEY_AUTO_TAP, swAutoTap.isChecked());
        editor.putInt(KEY_AUTO_TAP_PERCENT, sbAutoTapPercent.getProgress());
        editor.putInt(KEY_SLIDER_ASSIST, sbSliderAssist.getProgress());
        editor.putBoolean(KEY_MISS_CHANCE, swMissChance.isChecked());
        
        editor.apply();
        
        // Log the saved values for debugging
        android.util.Log.d("Settings", "Saved: aim=" + sbAimAssist.getProgress() + 
            ", autoTap=" + swAutoTap.isChecked() + 
            ", autoTapPercent=" + sbAutoTapPercent.getProgress() +
            ", slider=" + sbSliderAssist.getProgress() +
            ", missChance=" + swMissChance.isChecked());
    }
    
    private void resetSettings() {
        sbAimAssist.setProgress(DEFAULT_AIM_ASSIST);
        swAutoTap.setChecked(DEFAULT_AUTO_TAP);
        sbAutoTapPercent.setProgress(DEFAULT_AUTO_TAP_PERCENT);
        sbSliderAssist.setProgress(DEFAULT_SLIDER_ASSIST);
        swMissChance.setChecked(DEFAULT_MISS_CHANCE);
        
        tvAimValue.setText(DEFAULT_AIM_ASSIST + "%");
        tvAutoTapValue.setText(DEFAULT_AUTO_TAP_PERCENT + "%");
        tvSliderValue.setText(DEFAULT_SLIDER_ASSIST + "%");
    }
    
    /**
     * Static methods to read settings from anywhere
     */
    public static float getAimAssist(SharedPreferences prefs) {
        return prefs.getInt(KEY_AIM_ASSIST, DEFAULT_AIM_ASSIST) / 100f;
    }
    
    public static boolean isAutoTapEnabled(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_AUTO_TAP, DEFAULT_AUTO_TAP);
    }
    
    public static int getAutoTapPercent(SharedPreferences prefs) {
        return prefs.getInt(KEY_AUTO_TAP_PERCENT, DEFAULT_AUTO_TAP_PERCENT);
    }
    
    public static float getSliderAssist(SharedPreferences prefs) {
        return prefs.getInt(KEY_SLIDER_ASSIST, DEFAULT_SLIDER_ASSIST) / 100f;
    }
    
    public static boolean isMissChanceEnabled(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_MISS_CHANCE, DEFAULT_MISS_CHANCE);
    }
}