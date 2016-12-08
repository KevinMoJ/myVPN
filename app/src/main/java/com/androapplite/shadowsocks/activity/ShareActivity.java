package com.androapplite.shadowsocks.activity;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;

import com.androapplite.shadowsocks.GAHelper;
import com.androapplite.shadowsocks.R;
import com.androapplite.shadowsocks.ShadowsocksApplication;
import com.facebook.share.model.ShareLinkContent;
import com.facebook.share.widget.ShareDialog;

import java.io.File;

public class ShareActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_share);
        ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayShowHomeEnabled(true);
        actionBar.setDisplayHomeAsUpEnabled(true);
        GAHelper.sendScreenView(this, "分享屏幕");

    }

    public void shareByFacebook(View view){
        ShareLinkContent content = new ShareLinkContent.Builder()
                .setContentUrl(Uri.parse("https://play.google.com/store/apps/details?id=com.androapplite.shadowsocks&referrer=utm_source%3Dclient%26utm_medium%3Dfacebook"))
                .build();

        ShareDialog shareDialog = new ShareDialog(this);
        shareDialog.show(content);
        GAHelper.sendEvent(this, "分享屏幕", "facebook分享");
    }

    public void shareByBluetooth(View view){
        ApplicationInfo app = getApplication().getApplicationInfo();
        String filePath = app.sourceDir;

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("*/*");

        intent.setPackage("com.android.bluetooth");

        intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(new File(filePath)));
        startActivity(Intent.createChooser(intent, "Share app"));
        GAHelper.sendEvent(this, "分享屏幕", "蓝牙分享");
    }

    public void moreShare(View view){
        String url = "https://play.google.com/store/apps/details?id=com.androapplite.shadowsocks&referrer=utm_source%3Dclient%26utm_medium%3Dcommon";
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.putExtra(Intent.EXTRA_TEXT, url);
        shareIntent.setType("text/plain");
        try {
            startActivity(Intent.createChooser(shareIntent, getResources().getString(R.string.share)));
        }catch(ActivityNotFoundException e){
            ShadowsocksApplication.handleException(e);
        }
        GAHelper.sendEvent(this, "分享屏幕", "更多分享");
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if(item.getItemId() == android.R.id.home){
            finish();
            GAHelper.sendEvent(this, "分享屏幕", "后退", "导航栏");
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        GAHelper.sendEvent(this, "分享屏幕", "后退", "按键");
        super.onBackPressed();
    }
}