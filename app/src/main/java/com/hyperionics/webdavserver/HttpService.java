package com.hyperionics.webdavserver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;

import io.milton.config.HttpManagerBuilder;
import io.milton.http.HttpManager;
import io.milton.http.fs.FileSystemResourceFactory;
import io.milton.http.fs.NullSecurityManager;
import io.milton.simpleton.SimpletonServer;

// local service program reference
// http://developer.android.com/reference/android/app/Service.html#LocalServiceSample
// http://www.techotopia.com/index.php/Android_Local_Bound_Services_%E2%80%93_A_Worked_Example

public class HttpService extends Service {
	//region Fields
	private static final String CHANNEL_ID = "WebDAVServiceChannel";
	static final String TAG = "wdSrv";
	private WifiManager.WifiLock mWifiLock = null;
	private PowerManager.WakeLock mWakeLock = null;
	private final IBinder mBinder = new LocalBinder();
	public boolean running = false;
	//endregion

	public class LocalBinder extends Binder {
		HttpService getService() {
			return HttpService.this;
		}
	}

	@Override
	public IBinder onBind(Intent arg0) {
		//Log.d(TAG, "service bind");
		return mBinder;
	}

	@Override
	public boolean onUnbind(Intent intent) {
		//Log.d(TAG, "service onUnbind");
		return super.onUnbind(intent);
	}

	@Override
	public void onCreate() {
		//Log.d(TAG, "service created");
	}

	@Override
	public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
		//Log.d(TAG, "service start  - http server start");

		// ref http://stackoverflow.com/questions/8897535/android-socket-gets-killed-imidiatelly-after-screen-goes-blank/18916511#18916511
		if (mWifiLock == null) {
			WifiManager wMgr = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
			mWifiLock = wMgr.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, getApplicationContext().getPackageName() + ":MyWifiLock");
			mWifiLock.setReferenceCounted(false);
		}

		PowerManager pMgr = (PowerManager) getSystemService(Context.POWER_SERVICE);
		if (mWakeLock == null)
			mWakeLock = pMgr.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getApplicationContext().getPackageName() + ":MyWakeLock");
		if (mWakeLock != null && !mWakeLock.isHeld())
			mWakeLock.acquire();

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			NotificationChannel serviceChannel = new NotificationChannel(
					CHANNEL_ID,
					"WebDAV Server",
					NotificationManager.IMPORTANCE_LOW
			);
			serviceChannel.setSound(null, null);
			serviceChannel.setShowBadge(false);
			NotificationManager manager = getSystemService(NotificationManager.class);
			manager.createNotificationChannel(serviceChannel);
		}
		Intent notificationIntent = new Intent(this, ServerSettingsActivity.class);
		PendingIntent pendingIntent = PendingIntent.getActivity(this,
				0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
		Notification notification = new Notification.Builder(this, CHANNEL_ID)
				.setContentTitle(getString(com.hyperionics.webdavserver.R.string.wds_app_name))
				.setContentText(getString(com.hyperionics.webdavserver.R.string.srv_running))
				.setSmallIcon(com.hyperionics.webdavserver.R.drawable.ic_clip)
				.setContentIntent(pendingIntent)
				.setVisibility(Notification.VISIBILITY_PUBLIC)
				.setCategory(NotificationCompat.CATEGORY_SERVICE)
				.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
				.setOngoing(true)
				.build();
		startForeground(1, notification);
		if (!running) {
			(new connect_client()).start(); // Start http service
		}
		return START_NOT_STICKY;
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		//Log.d(TAG, "service destroy");

		// Close the server service
		try {
			if (ss != null)
				ss.stop();
		} catch (Exception e) {
			e.printStackTrace();
		}
		if (mWakeLock != null && mWakeLock.isHeld())
			mWakeLock.release();
		running = false;
	}

	private SimpletonServer ss;
	class connect_client extends Thread {

		public void run() {
			if (mWifiLock != null && !mWifiLock.isHeld()) {
				mWifiLock.acquire();
			}

			String homeFolder = Environment.getExternalStorageDirectory().getPath();
			int port = getSharedPreferences("WebDav", MODE_PRIVATE).getInt("port", 8080);
			FileSystemResourceFactory resourceFactory = new FileSystemResourceFactory(new File(homeFolder),
					new NullSecurityManager(), "/");
			resourceFactory.setAllowDirectoryBrowsing(true);
			HttpManagerBuilder b = new HttpManagerBuilder();
			b.setEnableFormAuth(false);
			b.setResourceFactory(resourceFactory);
			HttpManager httpManager = b.buildHttpManager();
			ss = new SimpletonServer(httpManager, b.getOuterWebdavResponseHandler(), 100, 10);
			ss.setHttpPort(port);
			ss.start();

			if (mWifiLock != null && mWifiLock.isHeld()) {
				mWifiLock.release();
			}
			running = true;
			//Log.d(TAG, "http server close");
		}
	}
}
