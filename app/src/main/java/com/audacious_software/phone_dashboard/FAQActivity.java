package com.audacious_software.phone_dashboard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.Patterns;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.audacious_software.passive_data_kit.Logger;
import com.audacious_software.passive_data_kit.generators.device.ForegroundApplication;
import com.audacious_software.passive_data_kit.generators.device.NotificationEvents;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

public class FAQActivity extends AppCompatActivity {
    private AppApplication mApp;

    private Toolbar mToolbar;
    private MenuItem mNextItem = null;
    private View.OnClickListener mNextListener = null;
    private SharedPreferences mPreferences = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        this.setContentView(R.layout.activity_faq);
        // this.mToolbar = findViewById(R.id.toolbar);
        // this.setSupportActionBar(this.mToolbar);
        // this.getSupportActionBar().setTitle(R.string.title_faq);

        this.setTitle(R.string.title_faq);

        this.getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        this.getSupportActionBar().setDisplayShowHomeEnabled(true);

        this.mApp = (AppApplication) this.getApplication();
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onResume() {
        super.onResume();

        WebView webView = this.findViewById(R.id.faq_webview);

        webView.getSettings().setJavaScriptEnabled(true);
        webView.loadUrl("file:///android_asset/html/faq.html");

        this.mApp.logAppAppearance(System.currentTimeMillis(), "faq-activity");
    }

    @SuppressLint("InflateParams")
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home)
        {
            AppLogger.getInstance(this).log("settings_menu_back");

            this.finish();
        }

        return true;
    }

}
