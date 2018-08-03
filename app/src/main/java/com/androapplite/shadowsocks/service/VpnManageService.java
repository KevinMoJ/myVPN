package com.androapplite.shadowsocks.service;

import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.androapplite.shadowsocks.ShadowsocksApplication;
import com.androapplite.shadowsocks.activity.SplashActivity;
import com.androapplite.shadowsocks.broadcast.Action;
import com.androapplite.shadowsocks.connect.ConnectVpnHelper;
import com.androapplite.shadowsocks.model.VpnState;
import com.androapplite.shadowsocks.utils.RuntimeSettings;
import com.androapplite.shadowsocks.utils.ServiceUtils;
import com.androapplite.vpn3.R;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.vm.shadowsocks.core.LocalVpnService;
import com.vm.shadowsocks.core.TcpTrafficMonitor;
import com.vm.shadowsocks.core.VpnNotification;

import java.lang.ref.WeakReference;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static android.content.Intent.ACTION_TIME_TICK;

public class VpnManageService extends Service implements Runnable,
        LocalVpnService.onStatusChangedListener, Handler.Callback, OnCompleteListener<Void> {
    private ScheduledExecutorService mService;
    private ScheduledFuture mFuture;
    private volatile long mTimeStart;
    private Intent mUseTimeIntent;
    private long mConnectStartTime;
    private volatile Looper mServiceLooper;
    private volatile Handler mServiceHandler;
    private static final int MSG_1_MINUTE = 1;
    private static final int MSG_1_HOUR = 2;
    private ScreenActionReceiver mScreenActionReceiver;
    private TimeTickReceiver mTimeTickReceiver;
    private static final int NOTIFICATION_ID_GRAP_SPEED = 10;
    private static final int MSG_CANCEL_NOTIFICATION = 3;
    private long mRemoteFetchStartTime;
    private boolean mIsRemoteFetchSuccess;
    private TcpTrafficMonitor mLastTcpTrafficMonitor;
    private static int sStopReason = 0;
    private static final int MSG_20_MINUTE = 4;

    public VpnManageService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mUseTimeIntent = new Intent(Action.ACTION_TIME_USE);
        mService = Executors.newSingleThreadScheduledExecutor();
        if (LocalVpnService.IsRunning) {
            startScheduler(TimeUnit.SECONDS);
            registerScreenActionReceiver();
        } else {
            registerTimeTickReceiver();
        }
        LocalVpnService.addOnStatusChangedListener(this);
        HandlerThread thread = new HandlerThread("VpnManageService");
        thread.start();
        mServiceLooper = thread.getLooper();
        mServiceHandler = new Handler(mServiceLooper, this);
        mServiceHandler.sendEmptyMessageDelayed(MSG_1_MINUTE, TimeUnit.MINUTES.toMillis(1));
        mServiceHandler.sendEmptyMessageDelayed(MSG_1_HOUR, TimeUnit.HOURS.toMillis(1));
        mServiceHandler.sendEmptyMessageDelayed(MSG_20_MINUTE, TimeUnit.MINUTES.toMillis(20));
        long useTime = RuntimeSettings.getUseTime();
        long freeUseTime = RuntimeSettings.getLuckPanGetRecord();
        if (useTime < 0) {
            RuntimeSettings.setUseTime(0);
        } else if (freeUseTime < 0) {
            RuntimeSettings.setLuckPanGetRecord(0);
        }
    }

    private void registerScreenActionReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_SCREEN_ON);
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
        mScreenActionReceiver = new ScreenActionReceiver(this);
        registerReceiver(mScreenActionReceiver, intentFilter, null, mServiceHandler);
    }

    private void unregisterScreenActionReceiver() {
        if (mScreenActionReceiver != null) {
            unregisterReceiver(mScreenActionReceiver);
            mScreenActionReceiver = null;
        }
    }

    @Override
    public void onDestroy() {
        LocalVpnService.removeOnStatusChangedListener(this);
        unregisterScreenActionReceiver();
        unregisterTimeTickReceiver();
        if (mFuture != null) {
            mFuture.cancel(true);
            mFuture = null;
        }
        mService.shutdown();
        mServiceLooper.quit();
        super.onDestroy();
    }

    private static class ScreenActionReceiver extends BroadcastReceiver {
        private WeakReference<VpnManageService> mReference;

        ScreenActionReceiver(VpnManageService service) {
            mReference = new WeakReference<>(service);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            VpnManageService service = mReference.get();
            if (service != null) {
                String action = intent.getAction();
                switch (action) {
                    case Intent.ACTION_SCREEN_ON:
                        service.stopScheduler();
                        service.startScheduler(TimeUnit.SECONDS);
                        break;
                    case Intent.ACTION_SCREEN_OFF:
                        service.stopScheduler();
                        service.startScheduler(TimeUnit.MINUTES);
                        break;
                }
            }

        }
    }

    private void startScheduler(TimeUnit timeUnit) {
        try {
            mFuture = mService.scheduleAtFixedRate(this, 1, 1, timeUnit);
        } catch (Exception e) {
            ShadowsocksApplication.handleException(e);
        }
    }

    private void stopScheduler() {
        if (mFuture != null) {
            mFuture.cancel(true);
            mFuture = null;
        }
    }

    @Override
    public void onStatusChanged(String status, Boolean isRunning) {
        stopScheduler();
        String[] parts = LocalVpnService.ProxyUrl != null ? LocalVpnService.ProxyUrl.split("@") : null;
        String server = parts != null && parts.length > 1 ? parts[1] : "没有服务器";
        if (isRunning) {
            mTimeStart = System.currentTimeMillis();
            startScheduler(TimeUnit.SECONDS);
            registerScreenActionReceiver();
            unregisterTimeTickReceiver();
            mConnectStartTime = System.currentTimeMillis();
            if (!mIsRemoteFetchSuccess) {
                fetchRemoteConfig();
            }
            ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
            if (networkInfo != null) {
            } else {
            }
        } else {
            unregisterScreenActionReceiver();
            registerTimeTickReceiver();
            if (mConnectStartTime > 0) {
                long usetime = (System.currentTimeMillis() - mConnectStartTime) / 1000;
            }
            mIsRemoteFetchSuccess = false;
            RuntimeSettings.setVPNState(VpnState.Stopped.ordinal());
            switch (sStopReason) {
                case 0:
                    break;
                case 1:
                    break;
                case 2:
                    break;
                case 3:
                    break;
                case 4:
                    break;
                case 5:
                    break;
            }
            sStopReason = 0;
        }
    }

    @Override
    public void onLogReceived(String logString) {

    }

    @Override
    public void onTrafficUpdated(@Nullable TcpTrafficMonitor tcpTrafficMonitor) {
        if (tcpTrafficMonitor != null) {
//            PromotionTracking.getInstance(this).reportUsageByte(tcpTrafficMonitor);
            String[] networkErrors = getResources().getStringArray(R.array.network_errors);
            if (tcpTrafficMonitor.pPayloadReceivedSpeed <= 0 && tcpTrafficMonitor.pNetworkError >= 0
                    && tcpTrafficMonitor.pNetworkError < networkErrors.length) {
                if (mLastTcpTrafficMonitor == null
                        || mLastTcpTrafficMonitor.pNetworkError != tcpTrafficMonitor.pNetworkError) {
                    String[] parts = LocalVpnService.ProxyUrl.split("@");
                    if (parts.length > 1) {
                    } else {
                    }
                }
            }
            mLastTcpTrafficMonitor = tcpTrafficMonitor;
        }
    }

    @Override
    public void run() { // 没秒钟都要更新用时
        long start = System.currentTimeMillis();
        long luckFreeDay = RuntimeSettings.getLuckPanGetRecord();
        long freeUseTime = RuntimeSettings.getNewUserFreeUseTime();
        ; // freeUseTime是秒

        if (!RuntimeSettings.isVIP()) {
            if (luckFreeDay <= 0 && freeUseTime > 0) {
                long differ = (start - mTimeStart) / 1000;  // differ是秒
                if (differ < 0) {
                    differ = 1;
                    mTimeStart = start;
                } else if (differ > 60) {
                    differ = 60;
                    mTimeStart = start;
                }

                freeUseTime -= differ;
                RuntimeSettings.setNewUserFreeUseTime(freeUseTime); // 以秒的形式存储

                if (freeUseTime <= 0) {
                    stopVpnForFreeTimeOver(this, ConnectVpnHelper.FREE_OVER_DIALOG_AUTO);
                    RuntimeSettings.setNewUserFreeUseTime(0);
                }
            }
        } else {
            long useTime = RuntimeSettings.getUseTime();

            long differ = (start - mTimeStart) / 1000;  // differ是秒
            if (differ < 0) {
                differ = 1;
                mTimeStart = start;
            } else if (differ > 60) {
                differ = 60;
                mTimeStart = start;
            }

            useTime += differ;
            RuntimeSettings.setUseTime(useTime);
        }

        mTimeStart = start;
        LocalBroadcastManager.getInstance(this).sendBroadcast(mUseTimeIntent);
        Log.d("VpnManageService", "use time");
    }

    public static void start(Context context) {
        ServiceUtils.startService(context, new Intent(context, VpnManageService.class));
    }

    @Override
    public boolean handleMessage(Message msg) {
//        PromotionTracking promotionTracking = PromotionTracking.getInstance(this);
        switch (msg.what) {
            case MSG_1_MINUTE:
//                promotionTracking.reportUninstallDayCount();
//                promotionTracking.reportAppInstall();
//                promotionTracking.reportPhoneModelAndAndroidOS();
//                grabSppedCheck();
                break;
            case MSG_1_HOUR:
//                promotionTracking.reportUninstallDayCount();
//                promotionTracking.reportAppInstall();
//                promotionTracking.reportPhoneModelAndAndroidOS();
                mServiceHandler.sendEmptyMessageDelayed(MSG_1_HOUR, TimeUnit.HOURS.toMillis(1));
                break;
            case MSG_CANCEL_NOTIFICATION:
                NotificationManagerCompat.from(getApplicationContext()).cancel(NOTIFICATION_ID_GRAP_SPEED);
                showGrabSpeedNotification(false);
                break;
            case MSG_20_MINUTE:
//                grabSppedCheck();
                mServiceHandler.sendEmptyMessageDelayed(MSG_20_MINUTE, TimeUnit.MINUTES.toMillis(20));
                break;
        }
        return true;
    }

    private static class TimeTickReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            switch (action) {
                case ACTION_TIME_TICK:
                    if (!LocalVpnService.IsRunning) {
                        VpnNotification.showVpnStoppedNotificationGlobe(context, false);
                    }
                    break;
            }
        }
    }

    private void registerTimeTickReceiver() {
        mTimeTickReceiver = new TimeTickReceiver();
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ACTION_TIME_TICK);
        registerReceiver(mTimeTickReceiver, intentFilter, null, mServiceHandler);
    }

    private void unregisterTimeTickReceiver() {
        if (mTimeTickReceiver != null) {
            unregisterReceiver(mTimeTickReceiver);
            mTimeTickReceiver = null;
        }
    }

//    private void grabSppedCheck() {
//        SharedPreferences sp = DefaultSharedPrefeencesUtil.getDefaultSharedPreferences(this);
//        long lastFire = sp.getLong(SharedPreferenceKey.GRAB_SPEED_TIME, 0);
//        if (!DateUtils.isToday(lastFire)) {
//            String currentHourString = AdAppHelper.getInstance(this).getCustomCtrlValue("grab_speed", "-1");
//            try {
//                int currentHour = Integer.valueOf(currentHourString);
//                if (currentHour > -1 && currentHour < 24) {
//                    Calendar calendar = Calendar.getInstance();
//                    if (calendar.get(Calendar.HOUR_OF_DAY) == currentHour) {
//                        showGrabSpeedNotification(true);
//                        sp.edit().putLong(SharedPreferenceKey.GRAB_SPEED_TIME, System.currentTimeMillis()).apply();
//                    }
//                }
//            } catch (Exception e) {
//                ShadowsocksApplication.handleException(e);
//            }
//        }
//    }

    private void showGrabSpeedNotification(boolean showFullScreenIntent) {
        try {
            Intent intent = new Intent(this, SplashActivity.class);
            intent.putExtra("source", "notificaiton_grab_speed");
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 2, intent, PendingIntent.FLAG_UPDATE_CURRENT);
            Bitmap largeIcon = BitmapFactory.decodeResource(this.getResources(), R.drawable.notification_icon_grap_speed);
            NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this);
            notificationBuilder.setSmallIcon(R.drawable.notification_icon)
                    .setLargeIcon(largeIcon)
                    .setContentIntent(pendingIntent)
                    .setShowWhen(false)
                    .setContentTitle(getString(R.string.grab_speed_notification_title))
                    .setContentText(getString(R.string.grab_speed_notification_content));
            if (showFullScreenIntent) {
                PendingIntent fullScreenIntent = PendingIntent.getActivity(this, 2, intent, PendingIntent.FLAG_UPDATE_CURRENT);
                notificationBuilder.setFullScreenIntent(fullScreenIntent, true);
            }
            NotificationManagerCompat.from(getApplicationContext()).notify(NOTIFICATION_ID_GRAP_SPEED, notificationBuilder.build());
        } catch (Exception e) {
            ShadowsocksApplication.handleException(e);
        }
        if (showFullScreenIntent) {
            mServiceHandler.sendEmptyMessageDelayed(MSG_CANCEL_NOTIFICATION, TimeUnit.SECONDS.toMillis(5));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ServiceUtils.startForgound(this);
        fetchRemoteConfig();
        return super.onStartCommand(intent, flags, startId);
    }

    private void fetchRemoteConfig() {
        mRemoteFetchStartTime = System.currentTimeMillis();
    }

    @Override
    public void onComplete(@NonNull Task<Void> task) {
        mIsRemoteFetchSuccess = task.isSuccessful();
    }

    public static void stopVpnByUser() {
        sStopReason = 1;
        LocalVpnService.IsRunning = false;
    }

    public static void stopVpnByUserSwitchProxy() {
        sStopReason = 2;
        LocalVpnService.IsRunning = false;
    }

    public static void stopVpnForAutoSwitchProxy() {
        sStopReason = 3;
        LocalVpnService.IsRunning = false;
    }

    public static void stopVpnForSwitchProxyFailed() {
        sStopReason = 4;
        LocalVpnService.IsRunning = false;
    }

    public static void stopVpnForFreeTimeOver(Context context, int type) {
        sStopReason = 5;
        LocalVpnService.IsRunning = false;
//        context.startActivity(new Intent(context, FreeTimeOverActivity.class));
    }
}
